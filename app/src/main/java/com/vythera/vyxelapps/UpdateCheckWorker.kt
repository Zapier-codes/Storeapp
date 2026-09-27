package com.vythera.vyxelapps

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vythera.vyxelapps.R
import com.vythera.vyxelapps.api.ZEALOT_ID_OFFSET
import com.vythera.vyxelapps.api.parseZealotEntries
import com.vythera.vyxelapps.api.toUnifiedRepo

class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs   = PreferencesManager(applicationContext)
        val history = prefs.loadInstallHistory().distinctBy { it.repoId }
        val ignored = prefs.loadIgnoredVersions()
        val token   = prefs.loadSettings().githubToken
        if (token.isNotEmpty()) RetrofitClient.authToken = token

        // `InstallHistoryEntry` has no `source` field of its own (confirmed by reading this
        // file and its model this session, not assumed) -- every entry here, regardless of
        // original source, was being checked against GitHub's release API via ownerLogin/
        // repoName. That's a pre-existing gap for all six non-GitHub CDN sources too, not
        // just Zealot, and fixing it for those is out of this leaf's scope. This leaf only
        // adds Zealot's own path, routed the same way the rest of the app already tells a
        // Zealot-sourced `GitHubRepo.id` apart from the other sources' synthetic ids: the
        // `ZEALOT_ID_OFFSET` bucket (`ZealotEntry.toUnifiedRepo()`). Only fetches/verifies
        // Zealot's index at all if some installed entry is actually in that bucket, so a
        // device with no Zealot installs never pays for the extra network call.
        val zealotLatestVersions: Map<Long, String> =
            if (history.any { it.repoId >= ZEALOT_ID_OFFSET }) {
                ZealotClient.resolveVerifiedIndex(applicationContext)
                    ?.let { parseZealotEntries(it) }
                    ?.map { it.toUnifiedRepo() }
                    ?.associate { it.id to it.cdnVersion }
                    ?: emptyMap()
            } else {
                emptyMap()
            }

        val available = mutableListOf<Pair<String, String>>()
        for (entry in history) {
            try {
                val latestTag = if (entry.repoId >= ZEALOT_ID_OFFSET) {
                    // No matching (or unverifiable) Zealot entry this round -- skip rather
                    // than falling through to a GitHub lookup that could never be right for
                    // a Zealot-sourced repoId.
                    zealotLatestVersions[entry.repoId]?.takeIf { it.isNotBlank() } ?: continue
                } else {
                    RetrofitClient.service.getLatestRelease(entry.ownerLogin, entry.repoName).tag_name
                }
                val key = "${entry.repoId}:$latestTag"
                if (latestTag != entry.tagName && key !in ignored) {
                    available.add(entry.repoName to latestTag)
                }
            } catch (_: Exception) {}
        }

        if (available.isNotEmpty()) showNotification(available)
        return Result.success()
    }

    private fun showNotification(updates: List<Pair<String, String>>) {
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "${updates.size} app update${if (updates.size > 1) "s" else ""} available"
        val text  = updates.take(3).joinToString(", ") { "${it.first} → ${it.second}" }

        val notif = NotificationCompat.Builder(applicationContext, "vyxel_updates")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(2024, notif)
    }
}