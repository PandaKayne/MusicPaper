package com.musicpaper.app

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min

/**
 * Papel tapiz animado para el efecto "CD giratorio".
 * El servicio de música guarda la carátula en un archivo y avisa; aquí se dibuja
 * el disco girando mientras la música suena y se detiene al pausar.
 */
class LiveCdWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CdEngine()

    private inner class CdEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var visible = false
        private var sw = 0
        private var sh = 0
        private var angle = 0f
        private var cx = 0f
        private var cy = 0f
        private var bg: Bitmap? = null
        private var disc: Bitmap? = null

        private val frame = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible && playing) handler.postDelayed(this, 33)
            }
        }

        private val refresh = Runnable {
            handler.post {
                rebuild()
                handler.removeCallbacks(frame)
                if (visible) handler.post(frame) else drawFrame()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            listeners.add(refresh)
        }

        override fun onDestroy() {
            listeners.remove(refresh)
            handler.removeCallbacksAndMessages(null)
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            handler.removeCallbacks(frame)
            if (visible) {
                rebuild()
                handler.post(frame)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder?, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            sw = width
            sh = height
            rebuild()
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            visible = false
            handler.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        private fun rebuild() {
            if (sw <= 0 || sh <= 0) return
            val ctx = this@LiveCdWallpaperService
            val prefs = Prefs(ctx)
            val art = loadArt(ctx)
            bg = WallpaperApplier.cdBackground(art, sw, sh)
            val side = (sw * prefs.artSize.coerceIn(30, 100) / 100f).toInt().coerceIn(64, min(sw, sh))
            disc = WallpaperApplier.makeDisc(art, side)
            cx = (sw * prefs.artX.coerceIn(0, 100) / 100f).coerceIn(side / 2f, sw - side / 2f)
            cy = (sh * prefs.artY.coerceIn(0, 100) / 100f).coerceIn(side / 2f, sh - side / 2f)
        }

        private fun drawFrame() {
            val holder = surfaceHolder ?: return
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val b = bg
                    if (b != null) canvas.drawBitmap(b, 0f, 0f, null) else canvas.drawColor(Color.BLACK)
                    val d = disc
                    if (d != null) {
                        if (playing) angle = (angle + 1.5f) % 360f
                        canvas.save()
                        canvas.rotate(angle, cx, cy)
                        canvas.drawBitmap(d, cx - d.width / 2f, cy - d.height / 2f, paint)
                        canvas.restore()
                    }
                }
            } catch (e: Exception) {
                // si la superficie ya no existe, ignoramos el cuadro
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                        // ignorar
                    }
                }
            }
        }
    }

    companion object {
        private const val ART_FILE = "live_art.png"
        private val listeners = CopyOnWriteArraySet<Runnable>()

        @Volatile
        var playing = false

        fun setPlaying(p: Boolean) {
            if (playing == p) return
            playing = p
            notifyChanged()
        }

        fun notifyChanged() {
            listeners.forEach { it.run() }
        }

        fun saveArt(ctx: Context, art: Bitmap) {
            val longest = max(art.width, art.height)
            val scaled = if (longest > 1024) {
                val f = 1024f / longest
                Bitmap.createScaledBitmap(
                    art,
                    (art.width * f).toInt().coerceAtLeast(1),
                    (art.height * f).toInt().coerceAtLeast(1),
                    true
                )
            } else art
            val tmp = File(ctx.filesDir, "$ART_FILE.tmp")
            FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(File(ctx.filesDir, ART_FILE))
        }

        fun loadArt(ctx: Context): Bitmap? {
            val f = File(ctx.filesDir, ART_FILE)
            return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
        }

        /** ¿Está este papel tapiz animado puesto como fondo del sistema? */
        fun isActive(ctx: Context): Boolean {
            val info = WallpaperManager.getInstance(ctx).wallpaperInfo ?: return false
            return info.packageName == ctx.packageName &&
                info.serviceName == LiveCdWallpaperService::class.java.name
        }
    }
}
