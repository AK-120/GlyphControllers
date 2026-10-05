package com.example.glyphcontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Restarts the background service after a reboot if the master switch was on.
 * specialUse foreground services are allowed to start from BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        GlyphRuntime.init(app)
        GlyphRuntime.appScope.launch {
            try {
                if (SettingsRepository(app).load().enabled) GlyphService.start(app)
            } finally {
                pending.finish()
            }
        }
    }
}
