package com.musicpaper.app

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Escucha las sesiones de medios activas (Spotify, etc.). Cuando una app de música
 * empieza a reproducir, toma la carátula y la pone como fondo. Al pausar, restaura.
 * Escribe un pequeño diagnóstico (pantalla "Estado") para saber qué pasa en cada teléfono.
 */
class MusicListenerService : NotificationListenerService() {

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val tracked = HashMap<MediaSession.Token, Tracked>()

    @Volatile private var lastKey: String? = null
    @Volatile private var applied = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        sync(list ?: emptyList())
    }

    private val restoreTask = Runnable {
        if (!anyPlaying()) {
            worker.execute {
                try {
                    WallpaperApplier.restore(applicationContext)
                    lastKey = null
                    applied = false
                } catch (e: Throwable) {
                    Log.e(TAG, "No se pudo restaurar el fondo", e)
                }
            }
        }
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Prefs(this).putDiag("listener", "conectado ${now()}")
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val cn = ComponentName(this, MusicListenerService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            sync(msm.getActiveSessions(cn))
        } catch (e: SecurityException) {
            Log.e(TAG, "Sin acceso a notificaciones", e)
            Prefs(this).putDiag("listener", "sin permiso ${now()}")
        }
    }

    override fun onListenerDisconnected() {
        instance = null
        Prefs(this).putDiag("listener", "desconectado ${now()}")
        try {
            getSystemService(MediaSessionManager::class.java)
                .removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
            // ignorar
        }
        tracked.values.forEach { it.controller.unregisterCallback(it.callback) }
        tracked.clear()
        main.removeCallbacks(restoreTask)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** Se llama desde la UI ("Aplicar ahora"). */
    fun reapply() {
        main.post {
            lastKey = null
            tracked.values.forEach { evaluate(it.controller) }
        }
    }

    private fun sync(list: List<MediaController>) {
        val live = list.map { it.sessionToken }.toSet()

        val iter = tracked.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.key !in live) {
                e.value.controller.unregisterCallback(e.value.callback)
                iter.remove()
            }
        }

        for (c in list) {
            val token = c.sessionToken
            if (tracked.containsKey(token)) continue
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = evaluate(c)
                override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate(c)
            }
            c.registerCallback(cb, main)
            tracked[token] = Tracked(c, cb)
        }

        list.forEach { evaluate(it) }
    }

    private fun isAllowed(c: MediaController, allowed: Set<String>) =
        allowed.isEmpty() || c.packageName in allowed

    private fun isPlaying(c: MediaController): Boolean {
        val st = c.playbackState?.state
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
    }

    private fun anyPlaying(): Boolean {
        val allowed = Prefs(this).apps
        return tracked.values.any { isAllowed(it.controller, allowed) && isPlaying(it.controller) }
    }

    private fun evaluate(c: MediaController) {
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        if (!isAllowed(c, prefs.apps)) return

        if (isPlaying(c)) {
            main.removeCallbacks(restoreTask)
            LiveCdWallpaperService.setPlaying(true)
            val md = c.metadata ?: return
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val key = listOf(
                c.packageName,
                title,
                artist,
                md.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ).joinToString("|")
            prefs.putDiag("track", "${c.packageName} · ${title ?: "?"}")
            if (key == lastKey) return

            val pkg = c.packageName
            worker.execute {
                if (key == lastKey) return@execute
                try {
                    var origin = "metadatos"
                    var art = extractArt(md)
                    if (art == null) {
                        origin = "notificación"
                        art = artFromNotification(pkg)
                    }
                    if (art == null) {
                        prefs.putDiag("art", "esta app no entrega carátula")
                        prefs.putDiag("result", "sin carátula ${now()}")
                        return@execute
                    }
                    prefs.putDiag("art", "${art.width}x${art.height} vía $origin")
                    WallpaperApplier.applyArt(applicationContext, art, title, artist)
                    lastKey = key
                    applied = true
                    prefs.putDiag("result", "OK ${now()}")
                } catch (e: Throwable) {
                    Log.e(TAG, "No se pudo aplicar el fondo", e)
                    prefs.putDiag("result", "Error: ${e.javaClass.simpleName} ${(e.message ?: "").take(80)} ${now()}")
                }
            }
        } else if (!anyPlaying()) {
            LiveCdWallpaperService.setPlaying(false)
            if (applied) {
                main.removeCallbacks(restoreTask)
                main.postDelayed(restoreTask, prefs.restoreDelay * 1000L)
            }
        }
    }

    /** Reúne todas las imágenes disponibles y se queda con la de mayor resolución. */
    private fun extractArt(md: MediaMetadata): Bitmap? {
        val candidates = mutableListOf<Bitmap>()
        listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        ).forEach { k -> md.getBitmap(k)?.let { candidates.add(it) } }
        listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI
        ).forEach { k ->
            val s = md.getString(k)
            if (s != null) loadUri(s)?.let { candidates.add(it) }
        }
        return candidates.maxByOrNull { it.width.toLong() * it.height }
    }

    private fun loadUri(s: String): Bitmap? {
        if (!s.startsWith("content://") && !s.startsWith("file://")) return null
        return try {
            contentResolver.openInputStream(Uri.parse(s))?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Plan B: muchas apps no ponen la carátula en los metadatos pero sí en su
     * notificación de reproducción. Como somos un servicio de notificaciones, la leemos de ahí.
     */
    private fun artFromNotification(pkg: String): Bitmap? {
        try {
            val list: Array<StatusBarNotification> = activeNotifications ?: return null
            for (sbn in list) {
                if (sbn.packageName != pkg) continue
                val n = sbn.notification
                val icon = n.getLargeIcon()
                if (icon != null) {
                    val d = icon.loadDrawable(this)
                    if (d != null) return drawableToBitmap(d)
                }
                @Suppress("DEPRECATION")
                val big = n.extras?.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
                if (big != null) return big
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo leer la notificación", e)
        }
        return null
    }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable) {
            val b = d.bitmap
            if (b != null) return b
        }
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return out
    }

    companion object {
        private const val TAG = "MusicPaper"

        @Volatile
        var instance: MusicListenerService? = null
    }
}
