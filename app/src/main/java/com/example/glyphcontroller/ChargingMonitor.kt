package com.example.glyphcontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Event-driven charging detection (no polling): listens to the sticky ACTION_BATTERY_CHANGED broadcast,
 * which the system sends on plug/unplug, status and level changes.
 *
 * Plug type, full-battery and level are straightforward public APIs.
 * "Fast charging" has NO public API. It is a heuristic: the instantaneous charge current
 * (BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) read whenever the broadcast arrives, with hysteresis.
 * Treat it as best effort; tune [FAST_ON_MA] / [FAST_OFF_MA] for your charger.
 */
class ChargingMonitor(
    private val context: Context,
    /** null = not plugged in */
    private val onChange: (ChargeInfo?) -> Unit
) {
    private val battery = context.getSystemService(BatteryManager::class.java)
    private var last: ChargeInfo? = null
    private var hasEmitted = false
    private var fast = false
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = handle(intent)
    }

    fun start() {
        if (started) return
        started = true
        val sticky = ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (sticky != null) handle(sticky)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        hasEmitted = false
    }

    private fun handle(i: Intent) {
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val plug = when {
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> PlugType.WIRELESS
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> PlugType.AC
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> PlugType.USB
            plugged and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> PlugType.DOCK
            else -> PlugType.NONE
        }
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0

        val info: ChargeInfo? = if (plug == PlugType.NONE) {
            fast = false
            null
        } else {
            val full = status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100
            val ma = currentMa()
            if (ma != null) fast = if (fast) ma >= FAST_OFF_MA else ma >= FAST_ON_MA
            ChargeInfo(plug, pct, full, fast && !full)
        }

        if (!hasEmitted || info != last) {
            hasEmitted = true
            last = info
            onChange(info)
        }
    }

    /** Magnitude of the charge current in mA, or null if the device doesn't report it. Sign conventions vary, so abs(). */
    private fun currentMa(): Int? {
        val ua = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (ua == Int.MIN_VALUE || ua == 0) return null
        return abs(ua) / 1000
    }

    private companion object {
        const val FAST_ON_MA = 2500
        const val FAST_OFF_MA = 1800
    }
}
