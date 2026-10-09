package com.vythera.vyxelapps

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.vythera.vyxelapps.api.SelfInstallRules
import com.vythera.vyxelapps.api.SelfInstallState
import com.vythera.vyxelapps.api.SelfUpdateBanner
import com.vythera.vyxelapps.api.SelfUpdateCheckPhase
import com.vythera.vyxelapps.api.SignerComparison
import com.vythera.vyxelapps.api.Verifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Track h, leaf `h.iii.zi`: the store installs its own update through a `PackageInstaller.Session`. Written,
 * NOT run (standing operator instruction). This is the one place `PackageInstaller.Session` is used (the
 * decision on record in Track h); `InstallGateway` and the two `ACTION_VIEW` call sites for other apps stay.
 *
 * Why a session: only a session can report the real result code and Android's own status message (the actual
 * `INSTALL_FAILED_*` or `INSTALL_PARSE_FAILED_*` reason the generic "problem parsing the package" screen hides),
 * and on Android 12 and newer only a session can ask for an update with no confirmation screen.
 *
 * What it does, in order, and stops at the first refusal:
 *  1. runs `Verifier` on the file with the offer's `sha256` and `signing_fingerprint` (both must pass);
 *  2. compares the file's signing certificate with the installed store's, and refuses with a clear message when
 *     they are unrelated (Android would refuse anyway; this says why before a session is opened);
 *  3. opens a session for this package, writes the APK, and commits it with a result sent to [SelfUpdateReceiver].
 *
 * It does not decide what the banner shows or fall back to anything: [Start.CannotStart] tells the caller (leaf
 * h.iii.zo) to try `InstallGateway`, and [Start.Refused] tells it the file must not be installed at all.
 */
object SelfInstaller {

    sealed class Start {
        /** The session is committed; progress and the result come through [SelfInstalls.state]. */
        object Committed : Start()
        /** The file failed a check or is signed by another key: do not install it, show [reason], delete the file. */
        data class Refused(val reason: String) : Start()
        /** A session could not be opened or written (not a problem with the file): the caller may fall back. */
        data class CannotStart(val reason: String) : Start()
    }

    const val ACTION_RESULT = "com.vythera.vyxelapps.SELF_UPDATE_RESULT"
    const val EXTRA_VERSION_CODE = "self_update_version_code"
    /** Preference key (in `vyxel_prefs`) holding the versionCode of an update this store has committed and not yet seen replace it. */
    const val PENDING_KEY = "self_update_pending"

    suspend fun install(
        ctx: Context,
        apk: File,
        versionCode: Long,
        claimedSha256: String,
        claimedSigningFingerprint: String
    ): Start = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext

        // 1. The checksum and the signer the index published. Only a result that checked both passes.
        val verified = Verifier.verify(app, apk, claimedSha256, claimedSigningFingerprint)
        val phase = SelfUpdateBanner.phaseFor(verified, versionCode, apk.absolutePath)
        if (phase is SelfUpdateCheckPhase.Failed) return@withContext Start.Refused(phase.reason)

        // 2. The signer against the installed store's.
        when (val same = SelfInstallRules.compareSigners(
            Verifier.installedSigningFingerprints(app),
            Verifier.apkSigningFingerprints(app, apk)
        )) {
            SignerComparison.Same -> Unit
            is SignerComparison.Different -> return@withContext Start.Refused(
                "The update is signed with a different key than the store installed now, so Android would refuse it. " +
                    "Install the new version once by hand, and updates after that will work in the app."
            )
            is SignerComparison.Unknown -> return@withContext Start.CannotStart("Could not compare signing certificates: ${same.reason}.")
        }

        // 3. The session.
        val installer = app.packageManager.packageInstaller
        var sessionId = -1
        try {
            // A session left over from an earlier attempt would hold the package open: abandon our own first.
            installer.mySessions.filter { it.appPackageName == app.packageName }.forEach {
                try { installer.abandonSession(it.sessionId) } catch (_: Exception) {}
            }

            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(apk.length())
                if (SelfInstallRules.requireNoUserAction(Build.VERSION.SDK_INT)) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                // Not set: setRequestUpdateOwnership (API 34). It needs a separate permission and only matters for
                // claiming ownership of OTHER apps' updates; the store updating itself gains nothing from it.
            }
            sessionId = installer.createSession(params)

            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(app, SelfUpdateReceiver::class.java)
                    .setAction(ACTION_RESULT)
                    .setPackage(app.packageName)
                    .putExtra(EXTRA_VERSION_CODE, versionCode)
                // Mutable on purpose: PackageInstaller adds the status extras to this intent. It is explicit (a
                // component of this app), which Android 14 requires of a mutable PendingIntent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(app, sessionId, intent, flags)
                // Marks that the replacement of this app is OUR doing, so SelfUpdatedReceiver reopens the store only then.
                app.getSharedPreferences("vyxel_prefs", Context.MODE_PRIVATE).edit().putLong(PENDING_KEY, versionCode).apply()
                SelfInstalls.publish(SelfInstallState.Running(versionCode))
                session.commit(pending.intentSender)
            }
            Start.Committed
        } catch (e: Exception) {
            if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) {}
            app.getSharedPreferences("vyxel_prefs", Context.MODE_PRIVATE).edit().remove(PENDING_KEY).apply()
            SelfInstalls.publish(SelfInstallState.Idle)
            Start.CannotStart("Could not start the install session: ${e.message ?: e.javaClass.simpleName}.")
        }
    }
}

/** The one place the rest of the app watches the self-install's progress and result (leaf h.iii.zo reads it). */
object SelfInstalls {
    private val _state = MutableStateFlow<SelfInstallState>(SelfInstallState.Idle)
    val state: StateFlow<SelfInstallState> = _state.asStateFlow()
    internal fun publish(state: SelfInstallState) { _state.value = state }
}
