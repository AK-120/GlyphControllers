package com.example.glyphcontroller

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional trigger for the NOTIFICATION state. Only works after the user grants
 * "Notification access" to this app (Settings > Notifications > Device & app notifications).
 * It never reads notification content; it only signals that something new arrived.
 */
class GlyphNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        GlyphRuntime.controller?.onNotification()
    }
}
