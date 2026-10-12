package com.example.glyphcontroller

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the controller alive while the app UI is closed.
 *
 * Why a foreground service: volume/charging/screen monitors are dynamically registered receivers that
 * only live as long as the process, and Android freely kills the process of a background app.
 * Type = specialUse (declared in the manifest) because none of the predefined types fits.
 */
class GlyphService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var attached = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } catch (t: Throwable) {
            // e.g. ForegroundServiceStartNotAllowedException when Android restarts us from the background.
            GlyphRuntime.report("Foreground service could not start: ${t.message}. Open the app once to restart it.")
            stopSelf()
            return
        }
        GlyphRuntime.attach(this, GlyphRuntime.Owner.SERVICE)
        attached = true

        // Stop ourselves when the master switch is turned off elsewhere (UI, notification action).
        scope.launch {
            combine(GlyphRuntime.settings, GlyphRuntime.settingsLoaded) { s, loaded -> loaded && !s.enabled }
                .distinctUntilChanged()
                .collect { shouldStop -> if (shouldStop) stopSelf() }
        }
        // Keep the status-bar icon in sync with the chosen logo.
        scope.launch {
            GlyphRuntime.settings.map { it.iconStyle }.distinctUntilChanged().drop(1).collect {
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
            }
        }
        // Optional wake lock so animations keep ticking with the screen off (CPU would otherwise suspend).
        scope.launch {
            combine(GlyphRuntime.settings, GlyphRuntime.screenOn, GlyphRuntime.animating) { s, screenOn, animating ->
                s.enabled && s.animateWhenScreenOff && !screenOn && animating
            }.distinctUntilChanged().collect { want -> if (want) acquireWakeLock() else releaseWakeLock() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GlyphRuntime.updateSettings { it.copy(enabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        if (attached) {
            GlyphRuntime.detach(GlyphRuntime.Owner.SERVICE)
            attached = false
        }
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glyphcontroller:animation").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_MIN)
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GlyphService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(GlyphRuntime.settings.value.iconStyle.notif)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "glyph_status"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "dotwave.action.STOP"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, GlyphService::class.java))
            }.onFailure { GlyphRuntime.report("Could not start the background service: ${it.message}") }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GlyphService::class.java))
        }
    }
}
