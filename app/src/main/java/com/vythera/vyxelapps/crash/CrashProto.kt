package com.vythera.vyxelapps.crash

import java.security.MessageDigest

/**
 * Z-P17 (principle 4, client half): the pure half of the opt-in crash reporter — the endpoint shape, the
 * accepted field set, the fingerprint rule and the body builder. Deliberately free of Android and JSON: the
 * console's contract is a byte contract, and keeping it here means it is exercised on a plain JVM
 * (`CrashProtoTest`), exactly as Zealot's `Api::CrashReportsController` and `CrashReport.fingerprint_for`
 * compute them.
 *
 * Privacy first, and this file is where that is enforced: the accepted fields are the minimum that makes a
 * crash actionable (kind, message, stack trace, app version, Android version, device model, a report id and a
 * time). There is no user id, no advertising id, no free-form blob — the reporter cannot smuggle personal data
 * in because there is no field for it. The console refuses a report for an app whose owner has not opted in
 * (403) and stores nothing, so a build that still carries the reporter cannot send after the switch is off.
 */
object CrashProto {

    /** The three kinds Zealot accepts; anything else is normalized to [KIND_CRASH]. */
    const val KIND_CRASH = "crash"
    const val KIND_ANR = "anr"
    const val KIND_EXCEPTION = "exception"

    val KINDS = setOf(KIND_CRASH, KIND_ANR, KIND_EXCEPTION)

    /** How many leading stack frames the fingerprint folds in; the same constant the console uses. */
    const val FINGERPRINT_FRAMES = 5

    /** A message and a trace are both capped so a pathological throwable cannot make an unbounded body. */
    const val MAX_MESSAGE_CHARS = 8_000
    const val MAX_TRACE_CHARS = 64_000

    /**
     * One crash event, already reduced to the fields the console accepts. [occurredAt] is an ISO-8601 instant;
     * the reporter fills it at crash time, so a report queued offline keeps the time it happened.
     */
    data class Event(
        val kind: String,
        val message: String,
        val stackTrace: String,
        val appVersionName: String? = null,
        val appVersionCode: String? = null,
        val androidVersion: String? = null,
        val deviceModel: String? = null,
        val reportId: String? = null,
        val occurredAt: String? = null,
    )

    /** `https://host/catalog` -> `https://host/api/crash_reports`. Leaves anything else's `/catalog` suffix alone. */
    fun url(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        val host = if (base.endsWith("/catalog")) base.removeSuffix("/catalog") else base
        return "$host/api/crash_reports"
    }

    /** Lower-case a reporter-supplied kind and keep it only if Zealot names it; else treat it as a crash. */
    fun normalizedKind(kind: String?): String {
        val value = kind?.trim()?.lowercase().orEmpty()
        return if (value in KINDS) value else KIND_CRASH
    }

    /** A report needs at least one of a message or a stack trace, or the console answers 422. */
    fun isReportable(event: Event): Boolean =
        event.message.isNotBlank() || event.stackTrace.isNotBlank()

    /**
     * The grouping key: the normalized first message line plus the first few stack frames, joined by newlines
     * and hashed. Byte-for-byte the same rule as Zealot's `CrashReport.fingerprint_for`, so a report this
     * client believes is new really is new to the console. Informational on the client; the console recomputes
     * and stores its own.
     */
    fun fingerprintFor(kind: String?, message: String?, stackTrace: String?): String {
        val firstMessage = message.orEmpty().lineSequence().firstOrNull().orEmpty().trim()
        val frames = stackTrace.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(FINGERPRINT_FRAMES)
            .toList()
        val input = listOf(normalizedKind(kind), firstMessage, frames.joinToString("\n")).joinToString("\n")
        return sha256Hex(input)
    }

    /**
     * The JSON body for `POST /api/crash_reports`, as field name to string. Null and blank values are dropped
     * rather than sent empty, so the console stores `nil` for "the reporter did not know" instead of `""`.
     * Returns null for an event that is not [isReportable], so the sender never makes a doomed request.
     */
    fun body(event: Event): Map<String, String>? {
        if (!isReportable(event)) return null
        val out = LinkedHashMap<String, String>()
        out["kind"] = normalizedKind(event.kind)
        val message = event.message.trim().take(MAX_MESSAGE_CHARS)
        if (message.isNotEmpty()) out["message"] = message
        val trace = event.stackTrace.trim().take(MAX_TRACE_CHARS)
        if (trace.isNotEmpty()) out["stack_trace"] = trace
        event.appVersionName?.takeIf { it.isNotBlank() }?.let { out["app_version_name"] = it }
        event.appVersionCode?.takeIf { it.isNotBlank() }?.let { out["app_version_code"] = it }
        event.androidVersion?.takeIf { it.isNotBlank() }?.let { out["android_version"] = it }
        event.deviceModel?.takeIf { it.isNotBlank() }?.let { out["device_model"] = it }
        event.reportId?.takeIf { it.isNotBlank() }?.let { out["report_id"] = it }
        event.occurredAt?.takeIf { it.isNotBlank() }?.let { out["occurred_at"] = it }
        return out
    }

    private fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) out.append(String.format("%02x", b))
        return out.toString()
    }
}
