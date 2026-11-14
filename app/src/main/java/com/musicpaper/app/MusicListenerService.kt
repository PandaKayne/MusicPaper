package com.musicpaper.app

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Escucha las sesiones de medios activas (Spotify, etc.). Cuando una app de música
 * empieza a reproducir, toma la carátula y la pone como fondo. Al pausar, restaura.
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
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudo restaurar el fondo", e)
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val cn = ComponentName(this, MusicListenerService::class.java)
            msm.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            sync(msm.getActiveSessions(cn))
        } catch (e: SecurityException) {
            Log.e(TAG, "Sin acceso a notificaciones", e)
        }
    }

    override fun onListenerDisconnected() {
        instance = null
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

    private fun anyPlaying(): Boolean {
        val allowed = Prefs(this).apps
        return tracked.values.any {
            isAllowed(it.controller, allowed) &&
                it.controller.playbackState?.state == PlaybackState.STATE_PLAYING
        }
    }

    private fun evaluate(c: MediaController) {
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        if (!isAllowed(c, prefs.apps)) return

        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            main.removeCallbacks(restoreTask)
            val md = c.metadata ?: return
            val key = listOf(
                c.packageName,
                md.getString(MediaMetadata.METADATA_KEY_TITLE),
                md.getString(MediaMetadata.METADATA_KEY_ARTIST),
                md.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ).joinToString("|")
            if (key == lastKey) return

            worker.execute {
                if (key == lastKey) return@execute
                try {
                    val art = extractArt(md) ?: return@execute
                    WallpaperApplier.applyArt(applicationContext, art)
                    lastKey = key
                    applied = true
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudo aplicar el fondo", e)
                }
            }
        } else if (applied && !anyPlaying()) {
            main.removeCallbacks(restoreTask)
            main.postDelayed(restoreTask, prefs.restoreDelay * 1000L)
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

    companion object {
        private const val TAG = "MusicPaper"

        @Volatile
        var instance: MusicListenerService? = null
    }
}
