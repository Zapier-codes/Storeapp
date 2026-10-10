package com.vythera.vyxelapps.crash

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vythera.vyxelapps.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Z-P17 (client half): the opt-in crash reporter. It is the "**S** library" the card names, written by hand
 * rather than pulling in ACRA — the whole job is one `Thread.setDefaultUncaughtExceptionHandler`, a small
 * spool file and one POST, which is what ACRA would do for us through a dependency and a JSON config we do
 * not need. The **Z** endpoint it talks to is Zealot's `POST /api/crash_reports`.
 *
 * Off by default, twice over:
 *   1. [install] does nothing unless the user turned crash reporting on in Settings (the same switch drives
 *      the mirror that lives in `SharedPreferences`, so a handler can read it synchronously), AND a vitals
 *      token is configured for the build;
 *   2. even then the handler chains to the previous one first, so the system's own crash dialog is unchanged.
 *
 * A crash must never make the app worse, so every step here is best-effort: the handler swallows its own
 * errors, the spool is capped, and the send runs on a short-lived thread that is not allowed to delay the
 * process. Nothing is collected until the opt-in is on, and then only the fields in [CrashProto.Event].
 */
object CrashReporter {

    /** Where the Settings toggle's current value is mirrored for the handler; see `SettingsStore`. */
    const val PREFS_NAME = "vyxel_crash"
    const val KEY_ENABLED = "crash_reporting_enabled"
    private const val KEY_SPOOL = "crash_spool"
    private const val SPOOL_MAX = 20

    private val gson = Gson()
    private val spoolType = object : TypeToken<List<CrashProto.Event>>() {}.type
    private val io = Executors.newSingleThreadExecutor { r ->
        // Daemon: a queued report must never keep the process alive after a crash.
        Thread(r, "vyxel-crash-send").apply { isDaemon = true }
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var installed = false

    /** `true` when the user has opted in (the Settings switch). */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Turns the opt-in on/off. Call from `SettingsStore` so the switch and the reporter never disagree. */
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) install(context) else uninstall()
    }

    /**
     * Makes this process report uncaught exceptions, if and only if the opt-in is on and a vitals token is
     * configured. Idempotent. Safe to call from `Application.onCreate`: with the switch off it just returns.
     */
    fun install(context: Context) {
        if (installed) return
        if (!isEnabled(context)) return
        if (BuildConfig.CRASH_REPORTING_TOKEN.isBlank()) return
        synchronized(this) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching { record(context, thread, throwable) }
                // Let the system do exactly what it always did; we only observed.
                previous?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }

    private fun uninstall() {
        if (!installed) return
        Thread.setDefaultUncaughtExceptionHandler(null)
        installed = false
    }

    /**
     * One thread's uncaught exception, reduced to a report. `kind` is [CrashProto.KIND_CRASH] for an error and
     * [CrashProto.KIND_ANR] for the ANR trace the framework hands the handler (an `Error` subclass named
     * `ANR`); a handled exception the app chooses to report uses [reportException] with [CrashProto.KIND_EXCEPTION].
     */
    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        val kind = if (throwable.javaClass.simpleName.contains("ANR", ignoreCase = true)) {
            CrashProto.KIND_ANR
        } else {
            CrashProto.KIND_CRASH
        }
        val message = throwable.message?.takeIf { it.isNotBlank() }
            ?: "${throwable.javaClass.name} in ${thread.name}"
        val event = CrashProto.Event(
            kind = kind,
            message = message,
            stackTrace = throwable.stackTraceToString(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toString(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            reportId = UUID.randomUUID().toString(),
            occurredAt = isoNow(),
        )
        enqueue(context, event)
        flush(context)
    }

    /** Reports a handled exception the app decided is worth sending, without crashing. */
    fun reportException(context: Context, throwable: Throwable, message: String? = null) {
        if (!isEnabled(context) || BuildConfig.CRASH_REPORTING_TOKEN.isBlank()) return
        val event = CrashProto.Event(
            kind = CrashProto.KIND_EXCEPTION,
            message = message?.takeIf { it.isNotBlank() } ?: throwable.message.orEmpty(),
            stackTrace = throwable.stackTraceToString(),
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE.toString(),
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            reportId = UUID.randomUUID().toString(),
            occurredAt = isoNow(),
        )
        enqueue(context, event)
        flush(context)
    }

    private fun enqueue(context: Context, event: CrashProto.Event) {
        val list = readSpool(context).toMutableList()
        list.add(event)
        while (list.size > SPOOL_MAX) list.removeAt(0)
        prefs(context).edit().putString(KEY_SPOOL, gson.toJson(list, spoolType)).commit()
    }

    /** Tries to send every spooled event, oldest first; successes are dropped, failures stay for next time. */
    private fun flush(context: Context) {
        val appContext = context.applicationContext
        io.execute {
            runCatching {
                val pending = readSpool(appContext)
                if (pending.isEmpty()) return@runCatching
                val remaining = ArrayList<CrashProto.Event>()
                var sent = 0
                for ((i, event) in pending.withIndex()) {
                    if (sendBlocking(event)) sent++ else remaining.add(event)
                    // Do not grind through a long queue on a phone that is clearly offline.
                    if (sent >= 5) {
                        remaining.addAll(pending.drop(i + 1))
                        break
                    }
                }
                prefs(appContext).edit().putString(KEY_SPOOL, gson.toJson(remaining, spoolType)).commit()
            }
        }
    }

    private fun sendBlocking(event: CrashProto.Event): Boolean {
        val body = CrashSender.jsonFor(event) ?: return true // not reportable: drop it
        val url = CrashSender.endpoint()
        val token = CrashSender.configuredToken
        if (url.isBlank() || token.isBlank()) return false
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun readSpool(context: Context): List<CrashProto.Event> = runCatching {
        val raw = prefs(context).getString(KEY_SPOOL, null) ?: return emptyList()
        gson.fromJson<List<CrashProto.Event>>(raw, spoolType) ?: emptyList()
    }.getOrDefault(emptyList())

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isoNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
}
