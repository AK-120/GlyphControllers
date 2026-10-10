package com.example.glyphcontroller

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/** What is playing right now. [playing] is false for paused / stopped sessions. */
data class MusicInfo(val title: String, val artist: String, val playing: Boolean)

/**
 * Watches the system's active media sessions and reports the track that is currently PLAYING (or null).
 *
 * Reading other apps' media sessions needs the app to be an enabled notification listener, so this relies on the
 * same "Notification access" grant as the notification flash. Without it [onProblem] is called once per start.
 */
class MusicMonitor(
    private val context: Context,
    private val onChange: (MusicInfo?) -> Unit,
    private val onProblem: (String) -> Unit
) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, GlyphNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var started = false
    private var controllers: List<MediaController> = emptyList()
    private var last: MusicInfo? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            started = true
            attach(manager.getActiveSessions(listener))
        } catch (se: SecurityException) {
            onProblem("Music on AOD needs Notification access (Setup tab > Grant notification access).")
        } catch (t: Throwable) {
            onProblem("Music detection failed: ${t.message}")
        }
    }

    fun stop() {
        if (started) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        started = false
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = emptyList()
        last = null
    }

    /** Retry, e.g. after the user just granted Notification access. */
    fun refresh() {
        stop()
        start()
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list
        controllers.forEach { runCatching { it.registerCallback(callback, handler) } }
        publish()
    }

    private fun publish() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val info = playing?.let { c ->
            val m = c.metadata
            val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
            MusicInfo(title.trim(), artist.trim(), true)
        }
        if (info != last) {
            last = info
            onChange(info)
        }
    }
}
