package com.vythera.vyxelapps.crash

import com.google.gson.Gson
import com.vythera.vyxelapps.BuildConfig
import com.vythera.vyxelapps.expressive.core.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Z-P17 (client half): the wire between [CrashProto] and Zealot's `POST /api/crash_reports`. This is the only
 * place the reporter touches the network, and it never throws to a caller that is already handling a crash:
 * [send] answers `true`/`false`.
 *
 * The request carries a **vitals-scoped** per-app token (`Authorization: Bearer zpa_…`), which Zealot accepts
 * only on this intake and which can never upload a release — so the secret compiled into a shipped build is the
 * least-privileged one that does the job. The token and the base URL come from Gradle properties (see
 * `app/build.gradle.kts`); a blank either way means "not configured yet", not an error, and [send] answers
 * `false` without a request. The console also refuses a report for an app the owner has not opted in (403), so a
 * build that still carries the reporter sends nothing once the switch is off.
 */
object CrashSender {

    private val gson = Gson()

    /** The intake URL, derived from the same host the catalog index comes from. */
    fun endpoint(base: String = BuildConfig.ZEALOT_CATALOG_URL): String = CrashProto.url(base)

    /** The configured vitals token, or blank. A public build with no token configured reports nothing. */
    val configuredToken: String = BuildConfig.CRASH_REPORTING_TOKEN

    /** The JSON body for an event, or null for one that is not reportable. Public so a test asserts the wire. */
    fun jsonFor(event: CrashProto.Event): String? = CrashProto.body(event)?.let { gson.toJson(it) }

    /**
     * POST one event. Returns `true` only for a 2xx. A blank token or base, a non-reportable event, a refusal,
     * or any network error is `false` and nothing throws — the queue keeps the event for the next attempt. A
     * duplicate `report_id` is answered 200 by the console and counts as success, so a retry is harmless.
     */
    suspend fun send(
        event: CrashProto.Event,
        token: String = configuredToken,
        base: String = BuildConfig.ZEALOT_CATALOG_URL,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = jsonFor(event) ?: return@withContext false
        val url = endpoint(base)
        if (url.isBlank() || token.isBlank()) return@withContext false
        return@withContext try {
            Net.postJson(url, body, headers = mapOf("Authorization" to "Bearer $token"))
            true
        } catch (_: Exception) {
            false
        }
    }
}
