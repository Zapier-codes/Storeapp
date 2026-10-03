package com.vythera.vyxelapps

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vythera.vyxelapps.api.PushRegistrar
import com.vythera.vyxelapps.api.TenantConfig

/**
 * Leaf `e.i` -- receives FCM. Token rotation goes to [PushRegistrar]; messages are rendered as a
 * tenant-branded notification on the existing `vyxel_updates` channel.
 *
 * **Server contract (flagged for Zealot 37d-iv): send DATA-ONLY messages** (`title`/`body`
 * keys). A message carrying a `notification` block is displayed by the FCM SDK itself when the
 * app is backgrounded, bypassing this class, so it could not be tenant-branded. If `title` is
 * absent the tenant's `branding.displayName` is used.
 *
 * Shares notification slot ([UPDATE_NOTIFICATION_TAG], 2024) with `UpdateCheckWorker`, so a push
 * and the worker's local check for the same updates replace each other rather than stacking.
 */
class AppstoreMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushRegistrar.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val body = data["body"] ?: message.notification?.body ?: return
        val title = data["title"] ?: message.notification?.title
            ?: TenantConfig.current.branding.displayName

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, "vyxel_updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(UPDATE_NOTIFICATION_TAG, UPDATE_NOTIFICATION_ID, notif)
    }
}

const val UPDATE_NOTIFICATION_TAG = "vyxel_update_summary"
const val UPDATE_NOTIFICATION_ID = 2024
