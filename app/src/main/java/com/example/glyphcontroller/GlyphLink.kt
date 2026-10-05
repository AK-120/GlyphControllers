package com.example.glyphcontroller

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one shared connection to Nothing's Glyph service.
 *
 * GlyphMatrixManager.getInstance() is a process-wide singleton, so no component may call unInit()
 * on its own. Everything acquires/releases this link; the SDK is bound while at least one owner
 * (foreground service or Glyph Toy) holds it.
 */
object GlyphLink {
    private const val TAG = "GlyphLink"

    enum class Status { IDLE, CONNECTING, CONNECTED, DISCONNECTED, UNSUPPORTED, ERROR }

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status
    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    private val lock = Any()
    private var manager: GlyphMatrixManager? = null
    private var refs = 0
    private var appContext: Context? = null

    @Volatile private var ready = false
    val isReady: Boolean get() = ready

    /** Matrix side length reported by the SDK (13 on Phone (4a) Pro, 25 on Phone (3)); 13 if unknown. */
    fun matrixLength(): Int = try {
        Common.getDeviceMatrixLength().takeIf { it in 5..64 } ?: 13
    } catch (t: Throwable) {
        13
    }

    /** Device identifier for register(), or null if this is not a device the SDK supports. */
    private fun deviceId(): String? = try {
        when {
            Common.is25111p() -> Glyph.DEVICE_25111p
            Common.is23112() -> Glyph.DEVICE_23112
            else -> null
        }
    } catch (t: Throwable) {
        null
    }

    fun acquire(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            refs++
            if (refs == 1) connectLocked()
        }
    }

    fun release() {
        synchronized(lock) {
            if (refs == 0) return
            refs--
            if (refs == 0) disconnectLocked()
        }
    }

    /** Drop and re-establish the binding (used after repeated send failures / service death). */
    fun reconnect() {
        synchronized(lock) {
            if (refs > 0) {
                disconnectLocked()
                connectLocked()
            }
        }
    }

    /** Runs [block] with the manager if connected. Returns false when not connected. Exceptions propagate. */
    fun <T> use(block: (GlyphMatrixManager) -> T): Result<T>? {
        val gm = manager
        if (!ready || gm == null) return null
        return runCatching { block(gm) }
    }

    private fun connectLocked() {
        val ctx = appContext ?: return
        val id = deviceId()
        if (id == null) {
            _status.value = Status.UNSUPPORTED
            _detail.value = "Glyph Matrix SDK reports this is not a Phone (4a) Pro / Phone (3). " +
                "Nothing will be shown on hardware."
            return
        }
        _status.value = Status.CONNECTING
        _detail.value = ""
        try {
            val gm = GlyphMatrixManager.getInstance(ctx)
            manager = gm
            gm.init(object : GlyphMatrixManager.Callback {
                override fun onServiceConnected(name: ComponentName) {
                    val ok = try {
                        gm.register(id)
                    } catch (t: Throwable) {
                        Log.e(TAG, "register failed", t)
                        false
                    }
                    ready = ok
                    if (ok) {
                        _status.value = Status.CONNECTED
                        _detail.value = "Registered as $id"
                    } else {
                        _status.value = Status.ERROR
                        _detail.value = "register($id) was refused by the Glyph service"
                    }
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    ready = false
                    _status.value = Status.DISCONNECTED
                    _detail.value = "Glyph service disconnected"
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "init failed", t)
            ready = false
            manager = null
            _status.value = Status.ERROR
            _detail.value = "Could not start the Glyph SDK: ${t.message}"
        }
    }

    private fun disconnectLocked() {
        ready = false
        try {
            manager?.unInit()
        } catch (t: Throwable) {
            Log.w(TAG, "unInit failed", t)
        }
        manager = null
        _status.value = Status.IDLE
        _detail.value = ""
    }
}
