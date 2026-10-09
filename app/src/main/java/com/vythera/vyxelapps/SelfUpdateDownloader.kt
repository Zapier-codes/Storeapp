package com.vythera.vyxelapps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vythera.vyxelapps.api.FinalVerdict
import com.vythera.vyxelapps.api.HTTP_USER_AGENT
import com.vythera.vyxelapps.api.ResponsePlan
import com.vythera.vyxelapps.api.ResumePlan
import com.vythera.vyxelapps.api.SelfUpdateDownloadRules
import com.vythera.vyxelapps.api.SelfUpdateDownloadState
import com.vythera.vyxelapps.api.SelfUpdateOffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Track h, leaf `h.ii.zi`: downloads the store's own update. Written, NOT run (standing operator instruction).
 *
 * A foreground [CoroutineWorker] (dataSync service type, with a notification and a Cancel action) that fetches
 * the offer's `download_url` into `cacheDir/self-update/`, resumes after an interruption with an HTTP `Range`
 * request, and accepts the file **only when its byte count equals `size_bytes`**. A short or long file is
 * deleted and never handed on. One download runs at a time. The decisions (resume, accept the server's answer,
 * accept the final size) live in `api/SelfUpdateDownload.kt` and are unit-tested; this class does the I/O.
 *
 * It does NOT check the checksum or the signing certificate. "Downloaded" means "the right number of bytes
 * arrived"; the sha256 and the signer are checked by `h.iii.zi` before anything is installed.
 */
class SelfUpdateDownloader(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private sealed class Outcome {
        object Complete : Outcome()
        data class Interrupted(val detail: String) : Outcome()
        data class Refused(val reason: String) : Outcome()
        object RestartFresh : Outcome()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(KEY_VERSION_NAME).orEmpty(), 0, indeterminate = true)

    override suspend fun doWork(): Result {
        val url      = inputData.getString(KEY_URL).orEmpty()
        val size     = inputData.getLong(KEY_SIZE, 0L)
        val code     = inputData.getLong(KEY_VERSION_CODE, 0L)
        val name     = inputData.getString(KEY_VERSION_NAME).orEmpty()

        if (!url.startsWith("https://", ignoreCase = true) || size <= 0L || code <= 0L) {
            return fail(code, "The update's download details are incomplete.")
        }

        try {
            val dir = File(applicationContext.cacheDir, DIR).apply { mkdirs() }
            val finalFile = File(dir, "appstore-$code.apk")
            val part      = File(dir, "appstore-$code.apk.part")
            // Anything left from another version (or a finished file of the wrong size) is cleared first.
            dir.listFiles()?.filter { it.name != finalFile.name && it.name != part.name }?.forEach { it.delete() }

            if (finalFile.exists()) {
                if (finalFile.length() == size) return succeed(code, finalFile, size)
                finalFile.delete()
            }

            try {
                setForeground(foregroundInfo(name, 0, indeterminate = true))
            } catch (_: Exception) {
                // Android 12+ refuses a foreground start from the background; the download still runs as plain work.
            }

            var restarted = false
            while (true) {
                when (SelfUpdateDownloadRules.planResume(part.length(), size)) {
                    ResumePlan.AlreadyComplete -> break
                    ResumePlan.DiscardAndRestart -> part.delete()
                    else -> Unit
                }
                if (part.length() < size) {
                    if (!SelfUpdateDownloadRules.enoughSpace(dir.usableSpace, size, part.length())) {
                        return fail(code, "Not enough free storage to download the update.")
                    }
                    when (val outcome = fetch(url, part, size, code, name)) {
                        Outcome.Complete -> break
                        is Outcome.Refused -> { part.delete(); return fail(code, outcome.reason) }
                        Outcome.RestartFresh -> {
                            if (restarted) { part.delete(); return fail(code, "The server would not continue the download.") }
                            restarted = true
                            continue
                        }
                        is Outcome.Interrupted -> {
                            // The part-file stays, so the next attempt resumes where this one stopped.
                            return if (SelfUpdateDownloadRules.mayRetry(runAttemptCount)) {
                                Result.retry()
                            } else {
                                part.delete()
                                fail(code, "The download kept stopping (${outcome.detail}). Check the connection and try again.")
                            }
                        }
                    }
                } else break
            }

            return when (val verdict = SelfUpdateDownloadRules.judgeFinalSize(part.length(), size)) {
                FinalVerdict.Accept -> {
                    if (part.renameTo(finalFile)) succeed(code, finalFile, size)
                    else { part.delete(); fail(code, "The downloaded update could not be saved.") }
                }
                is FinalVerdict.TooShort -> {
                    // The stream ended early without an error. Try to resume it; give up after the allowed attempts.
                    if (SelfUpdateDownloadRules.mayRetry(runAttemptCount)) Result.retry()
                    else { part.delete(); fail(code, "The download ended ${verdict.missingBytes} bytes short and was discarded.") }
                }
                is FinalVerdict.TooLong -> {
                    part.delete()
                    fail(code, "The server sent ${verdict.extraBytes} bytes more than the update lists; the file was discarded.")
                }
            }
        } catch (e: CancellationException) {
            SelfUpdateDownloads.publish(SelfUpdateDownloadState.Idle)
            throw e
        } catch (e: Exception) {
            return fail(code, "The download failed: ${e.message ?: e.javaClass.simpleName}.")
        }
    }

    /** One request. The part-file's current length is the offset; the caller has already planned the resume. */
    private suspend fun fetch(url: String, part: File, size: Long, code: Long, name: String): Outcome =
        withContext(Dispatchers.IO) {
            val offset = part.length()
            val builder = Request.Builder().url(url)
                .header("Accept-Encoding", "identity") // no gzip, so Content-Length is the file's real length
            if (offset > 0L) builder.header("Range", "bytes=$offset-")

            val response = try {
                http.newCall(builder.build()).execute()
            } catch (e: IOException) {
                return@withContext Outcome.Interrupted(e.message ?: "network error")
            }

            response.use { resp ->
                val body = resp.body
                val plan = SelfUpdateDownloadRules.planResponse(
                    code = resp.code,
                    requestedOffset = offset,
                    contentRangeHeader = resp.header("Content-Range"),
                    contentLength = body?.contentLength() ?: -1L,
                    expectedSize = size
                )
                when (plan) {
                    is ResponsePlan.Fail -> Outcome.Refused(plan.reason)
                    ResponsePlan.RestartFresh -> { part.delete(); Outcome.RestartFresh }
                    is ResponsePlan.Write -> {
                        if (body == null) return@use Outcome.Refused("The server sent no data.")
                        try {
                            var done = plan.startOffset
                            var lastReport = 0L
                            FileOutputStream(part, plan.startOffset > 0L).use { out ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        currentCoroutineContext().ensureActive()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        out.write(buffer, 0, n)
                                        done += n
                                        if (done > size) break // more than listed: stop filling the disk, the size check refuses it
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastReport >= REPORT_EVERY_MS) {
                                            lastReport = now
                                            report(code, name, done, size)
                                        }
                                    }
                                    out.flush()
                                }
                            }
                            report(code, name, done.coerceAtMost(size), size)
                            Outcome.Complete
                        } catch (e: IOException) {
                            Outcome.Interrupted(e.message ?: "connection lost")
                        }
                    }
                }
            }
        }

    private suspend fun report(code: Long, name: String, done: Long, total: Long) {
        val state = SelfUpdateDownloadState.Downloading(code, done, total)
        SelfUpdateDownloads.publish(state)
        setProgress(workDataOf(KEY_BYTES to done, KEY_TOTAL to total))
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(NOTIFICATION_ID, foregroundInfo(name, state.percent, indeterminate = false).notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted: the download goes on without a visible notification.
        }
    }

    private fun foregroundInfo(name: String, percent: Int, indeterminate: Boolean): ForegroundInfo {
        val channelId = ensureChannel(applicationContext)
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val text = if (indeterminate) "Starting" else "$percent%"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Downloading update ${name}".trim())
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Cancel", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun succeed(code: Long, file: File, size: Long): Result {
        SelfUpdateDownloads.publish(SelfUpdateDownloadState.Downloaded(code, file.absolutePath, size))
        return Result.success(workDataOf(KEY_FILE_PATH to file.absolutePath, KEY_SIZE to size, KEY_VERSION_CODE to code))
    }

    private fun fail(code: Long, reason: String): Result {
        SelfUpdateDownloads.publish(SelfUpdateDownloadState.Failed(code, reason))
        return Result.failure(workDataOf(KEY_REASON to reason, KEY_VERSION_CODE to code))
    }

    companion object {
        const val UNIQUE_NAME = "vyxel_self_update_download"
        const val KEY_URL = "url"
        const val KEY_SIZE = "size_bytes"
        const val KEY_VERSION_CODE = "version_code"
        const val KEY_VERSION_NAME = "version_name"
        const val KEY_BYTES = "bytes_done"
        const val KEY_TOTAL = "bytes_total"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_REASON = "reason"
        private const val DIR = "self-update"
        private const val CHANNEL_ID = "vyxel_self_update"
        private const val NOTIFICATION_ID = 7301
        private const val REPORT_EVERY_MS = 300L

        // followSslRedirects(false): an https download address must never be redirected to plain http.
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().addHeader("User-Agent", HTTP_USER_AGENT).build())
            }
            .build()

        private fun ensureChannel(context: Context): String {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Store update download", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "Progress of the store's own update download" }
                )
            }
            return CHANNEL_ID
        }

        internal fun inputFor(offer: SelfUpdateOffer): Data = workDataOf(
            KEY_URL to offer.downloadUrl,
            KEY_SIZE to offer.sizeBytes,
            KEY_VERSION_CODE to offer.versionCode,
            KEY_VERSION_NAME to offer.versionName
        )
    }
}

/**
 * The one place the rest of the app starts, cancels and watches the self-update download. One download runs at
 * a time: [enqueue] while one is running keeps the running one (`ExistingWorkPolicy.KEEP`).
 */
object SelfUpdateDownloads {
    private val _state = MutableStateFlow<SelfUpdateDownloadState>(SelfUpdateDownloadState.Idle)

    /** Progress as state, for the banner (leaf h.ii.zo). */
    val state: StateFlow<SelfUpdateDownloadState> = _state.asStateFlow()

    internal fun publish(state: SelfUpdateDownloadState) { _state.value = state }

    fun enqueue(context: Context, offer: SelfUpdateOffer) {
        val request = OneTimeWorkRequestBuilder<SelfUpdateDownloader>()
            .setInputData(SelfUpdateDownloader.inputFor(offer))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        _state.value = SelfUpdateDownloadState.Downloading(offer.versionCode, 0L, offer.sizeBytes)
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(SelfUpdateDownloader.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Cancels the running download and deletes its part-file: a cancelled download is not resumed later. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(SelfUpdateDownloader.UNIQUE_NAME)
        File(context.applicationContext.cacheDir, "self-update").listFiles()?.forEach { it.delete() }
        _state.value = SelfUpdateDownloadState.Idle
    }
}
