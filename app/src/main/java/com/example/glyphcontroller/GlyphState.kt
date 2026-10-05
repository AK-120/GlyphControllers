package com.example.glyphcontroller

/** What the Glyph Matrix is currently showing. IDLE is the custom AOD. */
enum class GlyphState { IDLE, VOLUME, CHARGING, NOTIFICATION }

enum class PlugType { NONE, USB, AC, WIRELESS, DOCK }

enum class ChargeKind(val variantKey: String) {
    NORMAL("normal"), FAST("fast"), FULL("full")
}

data class ChargeInfo(
    val plug: PlugType,
    val levelPercent: Int,
    val full: Boolean,
    val fast: Boolean
) {
    val pluggedIn: Boolean get() = plug != PlugType.NONE
    val kind: ChargeKind
        get() = when {
            full -> ChargeKind.FULL
            fast -> ChargeKind.FAST
            else -> ChargeKind.NORMAL
        }
}

/**
 * Which transient state wins when several are active. IDLE (AOD) is always the lowest.
 * "Volume interrupts charging" is simply the preset where VOLUME is ranked above CHARGING.
 */
enum class PriorityPreset(val label: String, val order: List<GlyphState>) {
    VOLUME_FIRST(
        "Volume › Charging › Notification",
        listOf(GlyphState.VOLUME, GlyphState.CHARGING, GlyphState.NOTIFICATION)
    ),
    CHARGING_FIRST(
        "Charging › Volume › Notification",
        listOf(GlyphState.CHARGING, GlyphState.VOLUME, GlyphState.NOTIFICATION)
    ),
    NOTIFICATION_FIRST(
        "Notification › Volume › Charging",
        listOf(GlyphState.NOTIFICATION, GlyphState.VOLUME, GlyphState.CHARGING)
    )
}

/**
 * GlyphMatrixManager.setGlyphMatrixTimeout(boolean) exists in the SDK binary but is NOT documented,
 * so its exact meaning is unverified. Default is OFF (never called).
 */
enum class MatrixTimeoutCall(val label: String) {
    OFF("Don't call (default)"), TRUE("setGlyphMatrixTimeout(true)"), FALSE("setGlyphMatrixTimeout(false)")
}
