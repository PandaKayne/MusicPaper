package com.musicpaper.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object WallpaperApplier {
    private const val RESTORE_FILE = "restore.jpg"

    // ---------- Imagen de restauración ----------

    fun hasRestoreImage(ctx: Context): Boolean = File(ctx.filesDir, RESTORE_FILE).exists()

    fun clearRestoreImage(ctx: Context) {
        File(ctx.filesDir, RESTORE_FILE).delete()
    }

    fun saveRestoreImage(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            File(ctx.filesDir, RESTORE_FILE).outputStream().use { input.copyTo(it) }
        } != null
    } catch (e: Exception) {
        false
    }

    // ---------- Aplicar / restaurar ----------

    fun applyArt(ctx: Context, source: Bitmap, title: String? = null, artist: String? = null) {
        val prefs = Prefs(ctx)
        val art = softwareCopy(source)

        // CD giratorio: lo dibuja el papel tapiz animado, aquí solo le pasamos la carátula
        if (prefs.effect == Prefs.EFFECT_CD_SPIN) {
            LiveCdWallpaperService.saveArt(ctx, art)
            LiveCdWallpaperService.notifyChanged()
            return
        }

        val (w, h) = screenSize(ctx)
        val out = when (prefs.effect) {
            Prefs.EFFECT_COVER -> {
                val c = centerCrop(art, w, h)
                if (prefs.darken > 0) darken(c, prefs.darken) else c
            }
            Prefs.EFFECT_CD -> renderCd(art, w, h, prefs)
            Prefs.EFFECT_SQUARE -> renderSquare(art, w, h, prefs)
            Prefs.EFFECT_GLASS -> renderGlass(art, w, h, prefs, title, artist)
            else -> {
                // Fondo desenfocado; la carátula nítida va encima si está activada
                var bg = blurred(art, w, h, prefs.blur)
                if (prefs.darken > 0) bg = darken(bg, prefs.darken)
                if (prefs.showArt) withSharpArt(bg, art, prefs) else bg
            }
        }

        setWallpaper(WallpaperManager.getInstance(ctx), out, prefs.target)
    }

    fun restore(ctx: Context) {
        val prefs = Prefs(ctx)
        if (prefs.effect == Prefs.EFFECT_CD_SPIN) return   // el CD se detiene solo al pausar
        val wm = WallpaperManager.getInstance(ctx)
        val file = File(ctx.filesDir, RESTORE_FILE)
        val (w, h) = screenSize(ctx)
        if (file.exists()) {
            val bmp = decodeSampled(file.path, w, h) ?: return
            setWallpaper(wm, centerCrop(bmp, w, h), prefs.target)
        } else {
            try {
                wm.clear(flags(prefs.target))
            } catch (e: Exception) {
                // sin imagen de restauración y sin poder limpiar: no hacemos nada
            }
        }
    }

    /**
     * Pone el fondo en pantalla principal y/o bloqueo con llamadas separadas.
     * En capas como MagicOS (Honor) es más fiable que mandar los dos flags juntos.
     * Primero la principal y al final el bloqueo.
     */
    private fun setWallpaper(wm: WallpaperManager, bmp: Bitmap, target: Int) {
        when (target) {
            Prefs.TARGET_HOME -> wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
            Prefs.TARGET_LOCK -> wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            else -> {
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM)
                wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
            }
        }
    }

    private fun flags(target: Int): Int = when (target) {
        Prefs.TARGET_LOCK -> WallpaperManager.FLAG_LOCK
        Prefs.TARGET_HOME -> WallpaperManager.FLAG_SYSTEM
        else -> WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM
    }

    // ---------- Efectos ----------

    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    private fun softwareCopy(b: Bitmap): Bitmap =
        if (Build.VERSION.SDK_INT >= 26 && b.config == Bitmap.Config.HARDWARE) {
            b.copy(Bitmap.Config.ARGB_8888, false)
        } else b

    private fun decodeSampled(path: String, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Escala la imagen para llenar w x h recortando el centro. */
    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = w / scale
        val sh = h / scale
        val left = ((src.width - sw) / 2f).toInt().coerceAtLeast(0)
        val top = ((src.height - sh) / 2f).toInt().coerceAtLeast(0)
        val right = (left + sw).toInt().coerceAtMost(src.width)
        val bottom = (top + sh).toInt().coerceAtMost(src.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src,
            Rect(left, top, right, bottom),
            Rect(0, 0, w, h),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }


    /** Dibuja la carátula en calidad completa, cuadrada, con esquinas redondeadas y sombra. */
    private fun withSharpArt(bg: Bitmap, art: Bitmap, prefs: Prefs): Bitmap {
        val out = if (bg.isMutable) bg else bg.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val side = (w * prefs.artSize.coerceIn(30, 100) / 100f).toInt().coerceAtMost(min(w, h))
        val sq = squareScaled(art, side)
        val cx = w * prefs.artX.coerceIn(0, 100) / 100f
        val cy = h * prefs.artY.coerceIn(0, 100) / 100f
        val left = (cx - side / 2f).coerceIn(0f, (w - side).coerceAtLeast(0).toFloat())
        val top = (cy - side / 2f).coerceIn(0f, (h - side).coerceAtLeast(0).toFloat())
        val radius = side * 0.05f
        val rect = RectF(left, top, left + side, top + side)
        val canvas = Canvas(out)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(side * 0.04f, 0f, side * 0.015f, Color.argb(150, 0, 0, 0))
        }
        canvas.drawRoundRect(rect, radius, radius, shadow)

        val shader = BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setTranslate(left, top) })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        canvas.drawRoundRect(rect, radius, radius, paint)
        return out
    }


    // ---------- Efectos nuevos: CD, Cuadrado, Glass Card ----------

    private fun objectSide(w: Int, h: Int, prefs: Prefs): Int =
        (w * prefs.artSize.coerceIn(30, 100) / 100f).toInt().coerceIn(64, min(w, h))

    /** Esquina superior izquierda de un objeto bw x bh centrado en (artX, artY) % de la pantalla. */
    private fun placement(w: Int, h: Int, bw: Int, bh: Int, prefs: Prefs): Pair<Float, Float> {
        val cx = w * prefs.artX.coerceIn(0, 100) / 100f
        val cy = h * prefs.artY.coerceIn(0, 100) / 100f
        val left = (cx - bw / 2f).coerceIn(0f, (w - bw).coerceAtLeast(0).toFloat())
        val top = (cy - bh / 2f).coerceIn(0f, (h - bh).coerceAtLeast(0).toFloat())
        return Pair(left, top)
    }

    private fun scaleColor(c: Int, f: Float): Int =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    /** Color medio de la carátula, oscurecido para que sirva de fondo. */
    private fun dominantColor(art: Bitmap): Int {
        val small = downscale(art, 16, 16)
        val px = Bitmap.createScaledBitmap(small, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        Color.colorToHSV(px, hsv)
        hsv[1] = min(1f, hsv[1] * 1.1f)
        hsv[2] = min(hsv[2], 0.45f).coerceAtLeast(0.18f)
        return Color.HSVToColor(hsv)
    }

    /** Fondo liso con degradado, del color de la carátula. */
    fun cdBackground(art: Bitmap?, w: Int, h: Int): Bitmap {
        val base = if (art != null) dominantColor(art) else Color.rgb(24, 24, 28)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint()
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), base, scaleColor(base, 0.45f), Shader.TileMode.CLAMP)
        Canvas(out).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return out
    }

    /** Disco de vinilo con la carátula como etiqueta central. Fondo transparente. */
    fun makeDisc(art: Bitmap?, side: Int): Bitmap {
        val s = side.coerceAtLeast(64)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = s / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // cuerpo del disco
        p.color = Color.rgb(16, 16, 18)
        c.drawCircle(r, r, r, p)

        // surcos
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, s / 400f)
        var rad = r * 0.42f
        var i = 0
        while (rad < r * 0.97f) {
            p.color = if (i % 2 == 0) Color.argb(40, 255, 255, 255) else Color.argb(18, 255, 255, 255)
            c.drawCircle(r, r, rad, p)
            rad += r * 0.035f
            i++
        }

        // reflejos
        p.style = Paint.Style.FILL
        p.shader = SweepGradient(
            r, r,
            intArrayOf(
                Color.argb(0, 255, 255, 255),
                Color.argb(60, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Color.argb(60, 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.12f, 0.5f, 0.62f, 1f)
        )
        c.drawCircle(r, r, r, p)
        p.shader = null

        // etiqueta con la carátula
        val labelSide = (r * 0.8f).toInt().coerceAtLeast(2)
        if (art != null) {
            val sq = squareScaled(art, labelSide)
            val shader = BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(r - labelSide / 2f, r - labelSide / 2f) })
            }
            val lp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
            c.drawCircle(r, r, labelSide / 2f, lp)
        } else {
            p.color = Color.DKGRAY
            c.drawCircle(r, r, labelSide / 2f, p)
        }

        // agujero central
        val hole = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        c.drawCircle(r, r, r * 0.045f, hole)
        return out
    }

    private fun renderCd(art: Bitmap, w: Int, h: Int, prefs: Prefs): Bitmap {
        val out = cdBackground(art, w, h)
        val side = objectSide(w, h, prefs)
        val disc = makeDisc(art, side)
        val (left, top) = placement(w, h, side, side, prefs)
        val canvas = Canvas(out)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(side * 0.05f, 0f, side * 0.02f, Color.argb(160, 0, 0, 0))
        }
        canvas.drawCircle(left + side / 2f, top + side / 2f, side / 2f, shadow)
        canvas.drawBitmap(disc, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun renderSquare(art: Bitmap, w: Int, h: Int, prefs: Prefs): Bitmap {
        val out = cdBackground(art, w, h)
        val side = objectSide(w, h, prefs)
        val sq = squareScaled(art, side)
        val (left, top) = placement(w, h, side, side, prefs)
        val canvas = Canvas(out)
        val rect = RectF(left, top, left + side, top + side)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(side * 0.05f, 0f, side * 0.02f, Color.argb(160, 0, 0, 0))
        }
        canvas.drawRect(rect, shadow)
        canvas.drawBitmap(sq, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Tarjeta de cristal sobre el fondo desenfocado, con la carátula y el título. */
    private fun renderGlass(art: Bitmap, w: Int, h: Int, prefs: Prefs, title: String?, artist: String?): Bitmap {
        var bg = blurred(art, w, h, prefs.blur)
        if (prefs.darken > 0) bg = darken(bg, prefs.darken)
        val out = if (bg.isMutable) bg else bg.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)

        val t = title.orEmpty()
        val a = artist.orEmpty()
        val hasText = t.isNotBlank()
        val cardW = objectSide(w, h, prefs)
        val pad = cardW * 0.06f
        val artSide = (cardW - 2 * pad).toInt().coerceAtLeast(32)
        val titleSize = cardW * 0.062f
        val artistSize = cardW * 0.048f
        val textBlock = if (hasText) {
            pad * 0.5f + titleSize * 1.25f + (if (a.isNotBlank()) artistSize * 1.3f else 0f)
        } else 0f
        val cardH = (pad + artSide + textBlock + pad).toInt()
        val (left, top) = placement(w, h, cardW, cardH, prefs)
        val rect = RectF(left, top, left + cardW, top + cardH)
        val radius = cardW * 0.07f

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 0, 0, 0)
            setShadowLayer(cardW * 0.05f, 0f, cardW * 0.02f, Color.argb(120, 0, 0, 0))
        }
        canvas.drawRoundRect(rect, radius, radius, shadow)

        val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(55, 255, 255, 255) }
        canvas.drawRoundRect(rect, radius, radius, glass)

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, cardW * 0.004f)
            color = Color.argb(110, 255, 255, 255)
        }
        canvas.drawRoundRect(rect, radius, radius, border)

        val ax = left + pad
        val ay = top + pad
        val sq = squareScaled(art, artSide)
        val shader = BitmapShader(sq, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setTranslate(ax, ay) })
        }
        val ap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        canvas.drawRoundRect(RectF(ax, ay, ax + artSide, ay + artSide), radius * 0.6f, radius * 0.6f, ap)

        if (hasText) {
            val avail = cardW - 2 * pad
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = titleSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            var y = ay + artSide + pad * 0.5f + titleSize
            canvas.drawText(TextUtils.ellipsize(t, tp, avail, TextUtils.TruncateAt.END).toString(), ax, y, tp)
            if (a.isNotBlank()) {
                val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(210, 255, 255, 255)
                    textSize = artistSize
                }
                y += artistSize * 1.3f
                canvas.drawText(TextUtils.ellipsize(a, sp, avail, TextUtils.TruncateAt.END).toString(), ax, y, sp)
            }
        }
        return out
    }

    /** Recorta al cuadrado central y escala a side x side con la mejor calidad posible. */
    private fun squareScaled(src: Bitmap, side: Int): Bitmap {
        val s = min(src.width, src.height)
        val x = (src.width - s) / 2
        val y = (src.height - s) / 2
        val sq = Bitmap.createBitmap(src, x, y, s, s)
        return if (s > side) downscale(sq, side, side)
        else Bitmap.createScaledBitmap(sq, side, side, true)
    }

    private fun darken(src: Bitmap, percent: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(Color.argb(255 * percent.coerceIn(0, 100) / 100, 0, 0, 0))
        return out
    }

    /** amount 0..100. Reduce la imagen, la difumina y la vuelve a ampliar. */
    private fun blurred(src: Bitmap, w: Int, h: Int, amount: Int): Bitmap {
        val base = centerCrop(src, w, h)
        if (amount <= 0) return base
        val t = amount.coerceIn(0, 100) / 100f
        val f = 0.5.pow(t * 6.0).toFloat()
        val tw = max(8, (w * f).toInt())
        val th = max(8, (h * f).toInt())
        var small = downscale(base, tw, th)
        small = boxBlur(boxBlur(small, 2), 2)
        return Bitmap.createScaledBitmap(small, w, h, true)
    }

    private fun downscale(src: Bitmap, tw: Int, th: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= tw && cur.height / 2 >= th) {
            cur = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
        }
        return Bitmap.createScaledBitmap(cur, tw, th, true)
    }

    private fun boxBlur(bmp: Bitmap, radius: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        val b = IntArray(w * h)
        pass(a, b, h, w, w, 1, radius)   // horizontal
        pass(b, a, w, h, 1, w, radius)   // vertical
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun pass(src: IntArray, dst: IntArray, lines: Int, len: Int, lineStep: Int, step: Int, radius: Int) {
        val n = radius * 2 + 1
        for (l in 0 until lines) {
            val base = l * lineStep
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val c = src[base + i.coerceIn(0, len - 1) * step]
                r += (c shr 16) and 255
                g += (c shr 8) and 255
                b += c and 255
            }
            for (i in 0 until len) {
                dst[base + i * step] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                val add = src[base + (i + radius + 1).coerceAtMost(len - 1) * step]
                val rem = src[base + (i - radius).coerceAtLeast(0) * step]
                r += ((add shr 16) and 255) - ((rem shr 16) and 255)
                g += ((add shr 8) and 255) - ((rem shr 8) and 255)
                b += (add and 255) - (rem and 255)
            }
        }
    }
}
