package com.example.glyphcontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** Screen on/off (ACTION_SCREEN_ON/OFF must be registered dynamically, which the foreground service does). */
class ScreenMonitor(
    private val context: Context,
    private val onChange: (Boolean) -> Unit
) {
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onChange(true)
                Intent.ACTION_SCREEN_OFF -> onChange(false)
            }
        }
    }

    fun isScreenOn(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onChange(isScreenOn())
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
    }
}
