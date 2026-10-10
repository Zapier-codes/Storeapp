package com.vythera.vyxelapps.silent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vythera.vyxelapps.expressive.install.InstallOutcome
import com.vythera.vyxelapps.expressive.install.InstallResultReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Z-P26: runs a silent install (or uninstall) to completion in the foreground.
 *
 * A silent install through Shizuku, Dhizuku or `su` is a child process that can take a minute or more,
 * and Android kills an app's background work the moment it leaves the foreground. Putting the work in a
 * foreground service keeps the store's process alive for exactly that long, and — per the operator
 * directive that setup/progress be visible — shows the person a notification saying an install is
 * running and then how it ended. The service stops itself as soon as the work is done; it is never a
 * long-lived daemon.
 *
 * The outcome is delivered through the same channel the PackageInstaller path uses
 * ([InstallResultReceiver.emit]), so the UI needs no second code path to watch a silent install finish.
 */
class SilentInstallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty().ifBlank { "app" }
        val appId = intent.getStringExtra(EXTRA_APP_ID).orEmpty()
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val uninstall = intent.getBooleanExtra(EXTRA_UNINSTALL, false)

        startForegroundCompat(
            if (uninstall) "Removing $appName…" else "Installing $appName…",
        )

        scope.launch {
            val succeeded = runCatching { perform(intent) }.getOrElse { false }
            if (uninstall) {
                if (packageName != null) {
                    InstallResultReceiver.emit(
                        if (succeeded) InstallOutcome.Success(appId)
                        else InstallOutcome.Failure(appId, "The app could not be removed.")
                    )
                }
            } else {
                InstallResultReceiver.emit(
                    if (succeeded) InstallOutcome.Success(appId)
                    else InstallOutcome.Failure(appId, "The app could not be installed.")
                )
            }
            notifyFinished(appName, uninstall, succeeded)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun perform(intent: Intent): Boolean {
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        val pinned = intent.getStringExtra(EXTRA_PINNED)?.let { name ->
            runCatching { SilentInstallBackend.valueOf(name) }.getOrNull()
        }
        return if (intent.getBooleanExtra(EXTRA_UNINSTALL, false)) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: return false
            SilentInstaller.uninstall(this, packageName, enabled, pinned)
        } else {
            val path = intent.getStringExtra(EXTRA_APK_PATH) ?: return false
            when (SilentInstaller.install(this, File(path), enabled, pinned)) {
                is SilentInstaller.Result.Success -> true
                is SilentInstaller.Result.Failed -> false
                SilentInstaller.Result.NoBackend -> false
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Notifications ──────────────────────────────────────────────────────────

    private fun startForegroundCompat(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_PROGRESS,
                "Silent installs",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress while an app is installed without a confirmation screen." }
            manager.createNotificationChannel(channel)
        }
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    private fun notifyFinished(appName: String, uninstall: Boolean, succeeded: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val verb = if (uninstall) "removed" else "installed"
        val text = if (succeeded) "$appName was $verb." else "$appName could not be $verb."
        runCatching { manager.notify(NOTIFICATION_ID_DONE, buildNotification(text)) }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_PROGRESS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Appstore")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VyxelSilentSvc"
        private const val CHANNEL_PROGRESS = "vyxel_silent_install"
        private const val NOTIFICATION_ID_PROGRESS = 4101
        private const val NOTIFICATION_ID_DONE = 4102

        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_APP_ID = "app_id"
        const val EXTRA_UNINSTALL = "uninstall"
        const val EXTRA_ENABLED = "silent_enabled"
        const val EXTRA_PINNED = "silent_pinned"

        /** Starts a silent install of [apk] in the foreground. */
        fun startInstall(
            context: Context,
            apk: File,
            appId: String,
            appName: String,
            enabled: Boolean,
            pinned: SilentInstallBackend?,
        ) {
            start(context, Intent(context, SilentInstallService::class.java).apply {
                putExtra(EXTRA_APK_PATH, apk.absolutePath)
                putExtra(EXTRA_APP_ID, appId)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_ENABLED, enabled)
                putExtra(EXTRA_PINNED, pinned?.name)
            })
        }

        /** Starts a silent uninstall of [packageName] in the foreground. */
        fun startUninstall(
            context: Context,
            packageName: String,
            appId: String,
            appName: String,
            enabled: Boolean,
            pinned: SilentInstallBackend?,
        ) {
            start(context, Intent(context, SilentInstallService::class.java).apply {
                putExtra(EXTRA_UNINSTALL, true)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APP_ID, appId)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_ENABLED, enabled)
                putExtra(EXTRA_PINNED, pinned?.name)
            })
        }

        private fun start(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.d(TAG, "could not start silent install service: ${e.message}")
            }
        }
    }
}
