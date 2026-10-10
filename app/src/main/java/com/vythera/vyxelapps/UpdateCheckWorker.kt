package com.vythera.vyxelapps

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vythera.vyxelapps.R
import com.vythera.vyxelapps.api.SelfUpdatePlanner
import com.vythera.vyxelapps.api.StoreUpdateChecker
import com.vythera.vyxelapps.api.isStoreRepoId
import com.vythera.vyxelapps.api.offerOrNull

class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs   = PreferencesManager(applicationContext)
        val history = prefs.loadInstallHistory().distinctBy { it.repoId }
        val ignored = prefs.loadIgnoredVersions()
        // Play-parity auto-update opt-out: apps the user switched off are skipped by
        // the background check entirely, so they don't raise an updates notification.
        val autoUpdateOptOut = prefs.loadAutoUpdateOptOut()
        val token   = prefs.loadSettings().githubToken
        if (token.isNotEmpty()) RetrofitClient.authToken = token

        // h.iv: store-first routing, shared with checkForUpdatesNow()/updateAll() (`api/StoreUpdateCheck.kt`).
        // `InstallHistoryEntry` has no `source` field, so a store-sourced entry is told apart by its
        // `GitHubRepo.id` bucket: Zealot's (`isZealotRepoId`) or D-Store's (`isDStoreRepoId`). An app on
        // either store is checked against that store's own index, never GitHub; an app on no store keeps
        // the GitHub path. The Zealot index is fetched only when some installed entry is in that bucket.
        val storeLatest: Map<Long, String> =
            if (history.any { isStoreRepoId(it.repoId) }) {
                StoreUpdateChecker(applicationContext).check(history).associate { it.repoId to it.latestTag }
            } else {
                emptyMap()
            }

        // ── Updates for apps the user installed through Vyxel ────────────────
        val available = mutableListOf<UpdateInfo>()
        // Every entry this run actually reached a source for, whatever the verdict. Anything in here
        // has a fresh answer, so a stale saved entry for it is wrong and must go — see the merge below.
        val verified = mutableSetOf<Long>()
        for (entry in history) {
            // Auto-update off means the background check never reports this app.
            val optOutKey = entry.packageName.ifBlank { "repo:${entry.repoId}" }
            if (optOutKey in autoUpdateOptOut) continue
            try {
                if (isStoreRepoId(entry.repoId)) {
                    // No matching (or unverifiable) store entry this round -- skip rather than falling
                    // through to a GitHub lookup that could never be right for a store-sourced repoId.
                    val latestTag = storeLatest[entry.repoId]?.takeIf { it.isNotBlank() } ?: continue
                    verified += entry.repoId
                    val key = "${entry.repoId}:$latestTag"
                    // Compare versions, not strings (see VersionCompare.kt): "v1.0.1" vs "1.0.1" is not
                    // an update, and a downgrade is not one either.
                    if (isVersionNewerThan(latestTag, entry.tagName) && key !in ignored) {
                        available.add(UpdateInfo(entry.repoId, entry.repoName, entry.tagName, latestTag, ""))
                    }
                } else {
                    val release = RetrofitClient.service.getLatestRelease(entry.ownerLogin, entry.repoName)
                    verified += entry.repoId
                    val key = "${entry.repoId}:${release.tag_name}"
                    // Compare versions, not strings — a bare string difference flagged "v1.0.1" against
                    // a stored "1.0.1" as an update forever, and reported downgrades as updates.
                    if (isVersionNewerThan(release.tag_name, entry.tagName) && key !in ignored) {
                        available.add(UpdateInfo(
                            repoId     = entry.repoId,
                            repoName   = entry.repoName,
                            currentTag = entry.tagName,
                            latestTag  = release.tag_name,
                            changelog  = release.body ?: ""
                        ))
                    }
                }
            } catch (_: Exception) {}
        }
        // Persist so the Updates UI and the home-screen widget show these immediately, without waiting
        // for the app's own foreground check. Entries this run didn't check are kept (the worker only
        // covers GitHub-source installs, so updates found from other sources must survive); an entry it
        // *did* check and found current is dropped, which clears the phantom updates the old string
        // comparison left behind.
        val stale = prefs.loadUpdates().filter { old -> old.repoId !in verified }
        val merged = available + stale
        if (merged != prefs.loadUpdates()) prefs.saveUpdates(merged)
        if (available.isNotEmpty()) {
            showAppsUpdateNotification(available.map { it.repoName to it.latestTag })
        }
        TodayWidgetProvider.refreshAll(applicationContext)

        // ── Optional background self-update check (h.vi) ─────────────────────
        checkStoreSelfUpdate()

        return Result.success()
    }

    /**
     * h.vi: off by default (`AppSettings.backgroundSelfUpdate`). Reads the store's own signed index —
     * never a GitHub owner/repo, which is what upstream's own worker did and what Track h removed — and
     * only notifies; no download starts without the person tapping. At most one notification per day.
     */
    private suspend fun checkStoreSelfUpdate() {
        if (!PreferencesManager(applicationContext).loadSettings().backgroundSelfUpdate) return
        val rawPrefs  = applicationContext.getSharedPreferences("vyxel_prefs", Context.MODE_PRIVATE)
        val lastCheck = rawPrefs.getLong("last_self_update_notif", 0L)
        if (System.currentTimeMillis() - lastCheck < 24 * 60 * 60 * 1000L) return

        try {
            val verified = ZealotClient.resolveVerifiedIndex(applicationContext)
            val decision = SelfUpdatePlanner.planFromVerifiedText(
                verifiedIndexJsonText = verified,
                installedPackage      = applicationContext.packageName,
                installedVersionCode  = BuildConfig.VERSION_CODE.toLong(),
                deviceSdk             = android.os.Build.VERSION.SDK_INT
            )
            val offer = decision.offerOrNull()
            if (offer != null) showVyxelUpdateNotification(offer.versionName)
            // Recorded only when an index was actually read, so an offline run retries next time.
            if (verified != null) {
                rawPrefs.edit().putLong("last_self_update_notif", System.currentTimeMillis()).apply()
            }
        } catch (_: Exception) {}
    }

    private fun showAppsUpdateNotification(updates: List<Pair<String, String>>) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "${updates.size} app update${if (updates.size > 1) "s" else ""} available"
        val text  = updates.take(3).joinToString(", ") { "${it.first} → ${it.second}" }
        // Expanded view lists every app, one per line — a digest worth tapping
        val bigText = updates.joinToString("\n") { "• ${it.first}  ${it.second}" } +
            "\n\nOpen Vyxel and tap Update All."

        val notif = NotificationCompat.Builder(applicationContext, "vyxel_updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(UPDATE_NOTIFICATION_TAG, UPDATE_NOTIFICATION_ID, notif)
    }

    private fun showVyxelUpdateNotification(latestVersion: String) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            applicationContext, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, "vyxel_updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Appstore update available")
            .setContentText("Version $latestVersion is ready — tap to open and update")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("A new version of the store ($latestVersion) is available. Open the app to download and install it."))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(UPDATE_NOTIFICATION_TAG, UPDATE_NOTIFICATION_ID + 1, notif)
    }
}
