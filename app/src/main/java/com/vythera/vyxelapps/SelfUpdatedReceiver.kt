package com.vythera.vyxelapps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Track h, leaf `h.iii.zo`: runs when this app has just been replaced by a newer version of itself. Written, NOT run.
 *
 * Only acts when the replacement was this store's own doing (`SelfInstaller` sets `self_update_pending` just
 * before it commits the session), so an update from any other source is left alone. It then tries to open the
 * new version, and also posts a "tap to open" notification, because Android 10 and newer often refuse an
 * activity launch from the background and a notification is the reliable way back.
 */
class SelfUpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = context.getSharedPreferences("vyxel_prefs", Context.MODE_PRIVATE)
        if (prefs.getLong(SelfInstaller.PENDING_KEY, 0L) <= 0L) return
        prefs.edit().remove(SelfInstaller.PENDING_KEY).apply()

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            context.startActivity(open)
        } catch (_: Exception) {
            // Refused from the background: the notification below is the way back.
        }

        val tap = PendingIntent.getActivity(
            context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, "vyxel_updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Store updated")
            .setContentText("Now on version ${BuildConfig.VERSION_NAME}. Tap to open.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted: the launch attempt above was the only try.
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 7302
    }
}
