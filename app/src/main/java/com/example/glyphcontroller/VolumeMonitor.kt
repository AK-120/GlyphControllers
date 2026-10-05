package com.example.glyphcontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Detects media-volume changes without the app UI being open.
 *
 * Android has no public "volume changed" callback for streams, so two best-effort signals are combined:
 *  1. The broadcast "android.media.VOLUME_CHANGED_ACTION" sent by the system's AudioService.
 *     It is a long-standing but UNDOCUMENTED action string (not in the public SDK constants).
 *  2. ContentObservers on Settings.System "volume_music*" keys as a fallback (also AOSP-internal key names).
 * Both funnel into [check], which reads the real value from AudioManager:
 *     percent = volume / maxVolume * 100
 * The max is read from the system (it is not 100 - typically 15 on Nothing OS).
 */
class VolumeMonitor(
    private val context: Context,
    /** (newPercent, previousPercent) */
    private val onChange: (Int, Int) -> Unit
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var lastPercent = readPercent()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val stream = intent.getIntExtra(EXTRA_STREAM_TYPE, -1)
            if (stream == -1 || stream == AudioManager.STREAM_MUSIC) {
                // A key press at the min/max limit may not change the value; still give feedback.
                check(forceEmit = true)
            }
        }
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = check(forceEmit = false)
    }

    fun start() {
        if (started) return
        started = true
        lastPercent = readPercent()
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        for (key in SETTING_KEYS) {
            runCatching {
                context.contentResolver.registerContentObserver(Settings.System.getUriFor(key), false, observer)
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }

    /** Current media volume as 0..100. */
    fun readPercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (cur.toFloat() / max.toFloat() * 100f).roundToInt().coerceIn(0, 100)
    }

    private fun check(forceEmit: Boolean) {
        val p = readPercent()
        if (p != lastPercent || forceEmit) {
            val prev = lastPercent
            lastPercent = p
            onChange(p, prev)
        }
    }

    private companion object {
        const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        val SETTING_KEYS = listOf("volume_music", "volume_music_speaker", "volume_music_headset", "volume_music_bt_a2dp")
    }
}
