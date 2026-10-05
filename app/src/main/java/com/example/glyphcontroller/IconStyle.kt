package com.example.glyphcontroller

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes

/**
 * Selectable logo. Each style has:
 *  - a launcher icon, switched by enabling exactly one <activity-alias> (see AndroidManifest.xml)
 *  - a status-bar icon, used by the foreground-service notification and the picker
 */
enum class IconStyle(val label: String, @DrawableRes val notif: Int, private val aliasClass: String) {
    RING("Ring", R.drawable.ic_notif_ring, "LauncherRing"),
    GLYPH("G", R.drawable.ic_notif_g, "LauncherG"),
    BOLT("Bolt", R.drawable.ic_notif_bolt, "LauncherBolt");

    companion object {
        private const val PKG = "com.example.glyphcontroller"

        /**
         * Enables the chosen launcher alias and disables the others. DONT_KILL_APP keeps the app running,
         * but the launcher may take a few seconds to refresh and some launchers need a restart to do it.
         */
        fun applyLauncher(context: Context, style: IconStyle) {
            val pm = context.packageManager
            // Enable the new one first so the app never has zero launcher entries.
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(context.packageName, "$PKG.${style.aliasClass}"),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
                )
            }
            for (other in entries) {
                if (other == style) continue
                runCatching {
                    pm.setComponentEnabledSetting(
                        ComponentName(context.packageName, "$PKG.${other.aliasClass}"),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
                    )
                }
            }
        }
    }
}
