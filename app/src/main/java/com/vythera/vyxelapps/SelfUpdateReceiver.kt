package com.vythera.vyxelapps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.vythera.vyxelapps.api.SelfInstallRules
import com.vythera.vyxelapps.api.SelfInstallState
import com.vythera.vyxelapps.api.StatusVerdict
import java.io.File

/**
 * Track h, leaf `h.iii.zi`: receives the result of the store's own install session. Written, NOT run.
 *
 *  - `STATUS_PENDING_USER_ACTION`: launches the confirmation screen Android attached to the broadcast (always the
 *    case on Android 8 to 11, and on a first update on any version).
 *  - success: clears the cached download. (When the update replaces this very app, Android stops the process, so
 *    this may never run; the cache is also cleared by the next download, which removes other versions' files.)
 *  - any failure: publishes the status code's plain meaning **and Android's own message text** to
 *    [SelfInstalls.state], which is the real reason the generic "problem parsing" screen hides.
 */
class SelfUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val versionCode = intent.getLongExtra(SelfInstaller.EXTRA_VERSION_CODE, 0L)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (val verdict = SelfInstallRules.describeStatus(status, message)) {
            StatusVerdict.NeedsConfirmation -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm == null) {
                    SelfInstalls.publish(SelfInstallState.Failed(versionCode, "Android asked for confirmation but did not say where. Try again."))
                    return
                }
                try {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                    SelfInstalls.publish(SelfInstallState.AwaitingConfirmation(versionCode))
                } catch (e: Exception) {
                    SelfInstalls.publish(SelfInstallState.Failed(versionCode, "Could not open Android's confirmation screen. Open the store and tap Install again."))
                }
            }
            StatusVerdict.Success -> {
                File(context.cacheDir, "self-update").listFiles()?.forEach { it.delete() }
                SelfInstalls.publish(SelfInstallState.Succeeded(versionCode))
            }
            is StatusVerdict.Failed -> SelfInstalls.publish(SelfInstallState.Failed(versionCode, verdict.reason))
        }
    }
}
