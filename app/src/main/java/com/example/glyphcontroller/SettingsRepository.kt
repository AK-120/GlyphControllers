package com.example.glyphcontroller

import android.content.Context
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class GlyphSettings(
    val enabled: Boolean = false,
    /** 0..100, applied on top of every state's own brightness. */
    val globalBrightness: Int = 70,
    val priority: PriorityPreset = PriorityPreset.VOLUME_FIRST,

    val aodAnimation: String = "aod_breathing",
    val aodSpeed: Float = 1f,
    val aodBrightness: Int = 60,
    val aodOnlyWhenScreenOff: Boolean = false,

    /** AOD shows the song that is playing (scrolling name + rotating disc) instead of the chosen AOD animation. */
    val musicAodEnabled: Boolean = false,
    /** false = rotating disc only, no scrolling name. */
    val musicShowName: Boolean = true,
    /** Picture shown between name scrolls. */
    val musicStyle: MusicStyle = MusicStyle.DISC,
    /** Seconds the disc is shown between two scrolls of the name. */
    val musicNameEverySec: Int = 15,

    val volumeAnimation: String = "volume_pulse",
    val volumeSpeed: Float = 1f,
    val volumeDurationMs: Int = 2000,
    val volumeBrightness: Int = 100,

    val chargingAnimation: String = "charging_flow",
    val chargingSpeed: Float = 1f,
    val chargingBrightness: Int = 80,

    val notificationEnabled: Boolean = false,
    val notificationAnimation: String = "notification_ping",
    val notificationDurationMs: Int = 3000,
    val notificationBrightness: Int = 100,

    /** Re-send the last frame this often while the picture is static (0 = never). */
    val keepAliveSec: Int = 20,
    /** Hold a partial wake lock so animations keep running with the screen off (costs battery). */
    val animateWhenScreenOff: Boolean = false,
    val matrixTimeoutCall: MatrixTimeoutCall = MatrixTimeoutCall.OFF,

    /** Logo: launcher icon + status-bar icon. */
    val iconStyle: IconStyle = IconStyle.RING,

    /** App colour theme. */
    val theme: AppTheme = AppTheme.NOTHING
)

private val Context.glyphDataStore by preferencesDataStore(name = "glyph_settings")

private object K {
    val ENABLED = booleanPreferencesKey("enabled")
    val GLOBAL_BRIGHTNESS = intPreferencesKey("global_brightness")
    val PRIORITY = stringPreferencesKey("priority")
    val AOD_ANIM = stringPreferencesKey("aod_anim")
    val AOD_SPEED = floatPreferencesKey("aod_speed")
    val AOD_BRIGHTNESS = intPreferencesKey("aod_brightness")
    val AOD_ONLY_SCREEN_OFF = booleanPreferencesKey("aod_only_screen_off")
    val MUSIC_ENABLED = booleanPreferencesKey("music_aod_enabled")
    val MUSIC_SHOW_NAME = booleanPreferencesKey("music_show_name")
    val MUSIC_EVERY = intPreferencesKey("music_name_every_sec")
    val MUSIC_STYLE = stringPreferencesKey("music_style")
    val VOL_ANIM = stringPreferencesKey("vol_anim")
    val VOL_SPEED = floatPreferencesKey("vol_speed")
    val VOL_DURATION = intPreferencesKey("vol_duration_ms")
    val VOL_BRIGHTNESS = intPreferencesKey("vol_brightness")
    val CHG_ANIM = stringPreferencesKey("chg_anim")
    val CHG_SPEED = floatPreferencesKey("chg_speed")
    val CHG_BRIGHTNESS = intPreferencesKey("chg_brightness")
    val NOTIF_ENABLED = booleanPreferencesKey("notif_enabled")
    val NOTIF_ANIM = stringPreferencesKey("notif_anim")
    val NOTIF_DURATION = intPreferencesKey("notif_duration_ms")
    val NOTIF_BRIGHTNESS = intPreferencesKey("notif_brightness")
    val KEEP_ALIVE = intPreferencesKey("keep_alive_sec")
    val ANIMATE_SCREEN_OFF = booleanPreferencesKey("animate_screen_off")
    val TIMEOUT_CALL = stringPreferencesKey("timeout_call")
    val ICON_STYLE = stringPreferencesKey("icon_style")
    val THEME = stringPreferencesKey("theme")
}

class SettingsRepository(private val context: Context) {

    val flow: Flow<GlyphSettings> = context.glyphDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }

    suspend fun load(): GlyphSettings = flow.first()

    suspend fun save(settings: GlyphSettings) {
        context.glyphDataStore.edit { it.write(settings) }
    }

    private fun Preferences.toSettings(): GlyphSettings {
        val d = GlyphSettings()
        return GlyphSettings(
            enabled = this[K.ENABLED] ?: d.enabled,
            globalBrightness = (this[K.GLOBAL_BRIGHTNESS] ?: d.globalBrightness).coerceIn(0, 100),
            priority = enumOrDefault(this[K.PRIORITY], d.priority),
            aodAnimation = this[K.AOD_ANIM] ?: d.aodAnimation,
            aodSpeed = (this[K.AOD_SPEED] ?: d.aodSpeed).coerceIn(0.1f, 4f),
            aodBrightness = (this[K.AOD_BRIGHTNESS] ?: d.aodBrightness).coerceIn(0, 100),
            aodOnlyWhenScreenOff = this[K.AOD_ONLY_SCREEN_OFF] ?: d.aodOnlyWhenScreenOff,
            musicAodEnabled = this[K.MUSIC_ENABLED] ?: d.musicAodEnabled,
            musicShowName = this[K.MUSIC_SHOW_NAME] ?: d.musicShowName,
            musicStyle = enumOrDefault(this[K.MUSIC_STYLE], d.musicStyle),
            musicNameEverySec = (this[K.MUSIC_EVERY] ?: d.musicNameEverySec).coerceIn(5, 60),
            volumeAnimation = this[K.VOL_ANIM] ?: d.volumeAnimation,
            volumeSpeed = (this[K.VOL_SPEED] ?: d.volumeSpeed).coerceIn(0.1f, 4f),
            volumeDurationMs = (this[K.VOL_DURATION] ?: d.volumeDurationMs).coerceIn(500, 10_000),
            volumeBrightness = (this[K.VOL_BRIGHTNESS] ?: d.volumeBrightness).coerceIn(0, 100),
            chargingAnimation = this[K.CHG_ANIM] ?: d.chargingAnimation,
            chargingSpeed = (this[K.CHG_SPEED] ?: d.chargingSpeed).coerceIn(0.1f, 4f),
            chargingBrightness = (this[K.CHG_BRIGHTNESS] ?: d.chargingBrightness).coerceIn(0, 100),
            notificationEnabled = this[K.NOTIF_ENABLED] ?: d.notificationEnabled,
            notificationAnimation = this[K.NOTIF_ANIM] ?: d.notificationAnimation,
            notificationDurationMs = (this[K.NOTIF_DURATION] ?: d.notificationDurationMs).coerceIn(500, 10_000),
            notificationBrightness = (this[K.NOTIF_BRIGHTNESS] ?: d.notificationBrightness).coerceIn(0, 100),
            keepAliveSec = (this[K.KEEP_ALIVE] ?: d.keepAliveSec).coerceIn(0, 120),
            animateWhenScreenOff = this[K.ANIMATE_SCREEN_OFF] ?: d.animateWhenScreenOff,
            matrixTimeoutCall = enumOrDefault(this[K.TIMEOUT_CALL], d.matrixTimeoutCall),
            iconStyle = enumOrDefault(this[K.ICON_STYLE], d.iconStyle),
            theme = enumOrDefault(this[K.THEME], d.theme)
        )
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.write(s: GlyphSettings) {
        this[K.ENABLED] = s.enabled
        this[K.GLOBAL_BRIGHTNESS] = s.globalBrightness
        this[K.PRIORITY] = s.priority.name
        this[K.AOD_ANIM] = s.aodAnimation
        this[K.AOD_SPEED] = s.aodSpeed
        this[K.AOD_BRIGHTNESS] = s.aodBrightness
        this[K.AOD_ONLY_SCREEN_OFF] = s.aodOnlyWhenScreenOff
        this[K.MUSIC_ENABLED] = s.musicAodEnabled
        this[K.MUSIC_SHOW_NAME] = s.musicShowName
        this[K.MUSIC_EVERY] = s.musicNameEverySec
        this[K.MUSIC_STYLE] = s.musicStyle.name
        this[K.VOL_ANIM] = s.volumeAnimation
        this[K.VOL_SPEED] = s.volumeSpeed
        this[K.VOL_DURATION] = s.volumeDurationMs
        this[K.VOL_BRIGHTNESS] = s.volumeBrightness
        this[K.CHG_ANIM] = s.chargingAnimation
        this[K.CHG_SPEED] = s.chargingSpeed
        this[K.CHG_BRIGHTNESS] = s.chargingBrightness
        this[K.NOTIF_ENABLED] = s.notificationEnabled
        this[K.NOTIF_ANIM] = s.notificationAnimation
        this[K.NOTIF_DURATION] = s.notificationDurationMs
        this[K.NOTIF_BRIGHTNESS] = s.notificationBrightness
        this[K.KEEP_ALIVE] = s.keepAliveSec
        this[K.ANIMATE_SCREEN_OFF] = s.animateWhenScreenOff
        this[K.TIMEOUT_CALL] = s.matrixTimeoutCall.name
        this[K.ICON_STYLE] = s.iconStyle.name
        this[K.THEME] = s.theme.name
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        if (name == null) default else runCatching { enumValueOf<E>(name) }.getOrDefault(default)
}
