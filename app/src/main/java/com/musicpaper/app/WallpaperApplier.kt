package com.musicpaper.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import java.io.File
import kotlin.math.max
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

    fun applyArt(ctx: Context, source: Bitmap) {
        val prefs = Prefs(ctx)
        val (w, h) = screenSize(ctx)
        val art = softwareCopy(source)

        var out = if (prefs.effect == Prefs.EFFECT_COVER) {
            centerCrop(art, w, h)
        } else {
            blurred(art, w, h, prefs.blur)
        }
        if (prefs.darken > 0) out = darken(out, prefs.darken)

        WallpaperManager.getInstance(ctx).setBitmap(out, null, true, flags(prefs.target))
    }

    fun restore(ctx: Context) {
        val prefs = Prefs(ctx)
        val wm = WallpaperManager.getInstance(ctx)
        val file = File(ctx.filesDir, RESTORE_FILE)
        val (w, h) = screenSize(ctx)
        if (file.exists()) {
            val bmp = decodeSampled(file.path, w, h) ?: return
            wm.setBitmap(centerCrop(bmp, w, h), null, true, flags(prefs.target))
        } else {
            try {
                wm.clear(flags(prefs.target))
            } catch (e: Exception) {
                // sin imagen de restauración y sin poder limpiar: no hacemos nada
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
