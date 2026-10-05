package com.example.glyphcontroller

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.nothing.ketchum.GlyphToy

/**
 * The Always-on Glyph Toy entry point. On Phone (4a) Pro, Nothing only supports AOD toys: the user
 * picks this toy under Settings > Glyph Interface > Flip to Glyph > Always-on Glyph Toy, the system binds
 * this service while it is selected, and sends EVENT_AOD roughly once a minute.
 *
 * While bound we share the same controller/engine as the foreground service but draw through
 * setMatrixFrame() (the toy path). The system may still blank the matrix on its own schedule.
 */
class GlyphToyService : Service() {

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                GlyphToy.MSG_GLYPH_TOY -> {
                    val event = msg.data?.getString(GlyphToy.MSG_GLYPH_TOY_DATA)
                    if (event == GlyphToy.EVENT_AOD) {
                        // Periodic tick: re-send the current picture so the system keeps showing it.
                        GlyphRuntime.controller?.refresh(forceResend = true)
                    }
                }
                else -> super.handleMessage(msg)
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder {
        GlyphRuntime.attach(this, GlyphRuntime.Owner.TOY)
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        GlyphRuntime.detach(GlyphRuntime.Owner.TOY)
        return false
    }
}
