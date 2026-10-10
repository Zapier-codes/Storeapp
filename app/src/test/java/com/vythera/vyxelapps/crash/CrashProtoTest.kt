package com.vythera.vyxelapps.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Z-P17 (client half): the wire contract of the opt-in crash reporter, checked against Zealot's own rules on a
 * plain JVM (no Android). The fingerprints below are constants produced by the console's
 * `CrashReport.fingerprint_for`, so the client's rule cannot drift from the one that groups the inbox.
 */
class CrashProtoTest {

    @Test
    fun `derives the intake url from the catalog url`() {
        assertEquals(
            "https://host.example.com/api/crash_reports",
            CrashProto.url("https://host.example.com/catalog"),
        )
        assertEquals(
            "https://host.example.com/api/crash_reports",
            CrashProto.url("https://host.example.com/catalog/"),
        )
        assertEquals(
            "https://host.example.com/api/crash_reports",
            CrashProto.url("https://host.example.com"),
        )
        assertEquals("", CrashProto.url(""))
        assertEquals("", CrashProto.url("   "))
    }

    @Test
    fun `unknown or missing kinds normalize to crash`() {
        assertEquals(CrashProto.KIND_CRASH, CrashProto.normalizedKind(null))
        assertEquals(CrashProto.KIND_CRASH, CrashProto.normalizedKind(""))
        assertEquals(CrashProto.KIND_CRASH, CrashProto.normalizedKind("weird"))
        assertEquals(CrashProto.KIND_ANR, CrashProto.normalizedKind("ANR"))
        assertEquals(CrashProto.KIND_EXCEPTION, CrashProto.normalizedKind("Exception"))
    }

    @Test
    fun `fingerprint matches the console rule`() {
        val trace = "com.a.Foo.bar(Foo.kt:10)\ncom.a.Foo.baz(Foo.kt:20)\ncom.a.Main.run(Main.kt:5)\n" +
            "line4\nline5\nline6\nline7"
        assertEquals(
            "23b0a1aa9cfdd826331240b9d23c516bd6a306da8f740f73b804929c6880bc7b",
            CrashProto.fingerprintFor("crash", "boom: NullPointerException", trace),
        )
        assertEquals(
            "80f0909e0757ea82f51745997bc05591d5dc93aa5eafc374d596271257a6ea8c",
            CrashProto.fingerprintFor("anr", "main thread not responding", "at android.os.Looper.loop"),
        )
        // First message line is trimmed; a different kind changes the hash.
        assertEquals(
            "01bdd1fe664446dabc04a88d7d7e579fb703c4a26665611ed914b2c80cb0990c",
            CrashProto.fingerprintFor("crash", "  spaced first line  \nsecond", ""),
        )
        assertTrue(
            CrashProto.fingerprintFor("crash", "m", "") !=
                CrashProto.fingerprintFor("anr", "m", ""),
        )
    }

    @Test
    fun `a report needs a message or a trace`() {
        assertTrue(CrashProto.isReportable(CrashProto.Event("crash", "boom", "")))
        assertTrue(CrashProto.isReportable(CrashProto.Event("crash", "", "at Foo")))
        assertTrue(!CrashProto.isReportable(CrashProto.Event("crash", "  ", "\n")))
    }

    @Test
    fun `body drops blank fields and normalizes the kind`() {
        val body = CrashProto.body(
            CrashProto.Event(
                kind = "Crash",
                message = "boom",
                stackTrace = "at Foo",
                appVersionName = "1.1.0",
                appVersionCode = "",
                androidVersion = "Android 14 (API 34)",
                deviceModel = null,
                reportId = "r-1",
                occurredAt = "2026-10-10T00:00:00Z",
            ),
        )!!
        assertEquals("crash", body["kind"])
        assertEquals("boom", body["message"])
        assertEquals("at Foo", body["stack_trace"])
        assertEquals("1.1.0", body["app_version_name"])
        assertEquals("Android 14 (API 34)", body["android_version"])
        assertEquals("r-1", body["report_id"])
        assertEquals("2026-10-10T00:00:00Z", body["occurred_at"])
        assertTrue(!body.containsKey("app_version_code"))
        assertTrue(!body.containsKey("device_model"))
    }

    @Test
    fun `body is null for an empty event and caps the message`() {
        assertNull(CrashProto.body(CrashProto.Event("crash", "", "")))
        val huge = "x".repeat(CrashProto.MAX_MESSAGE_CHARS + 500)
        val body = CrashProto.body(CrashProto.Event("exception", huge, ""))!!
        assertEquals(CrashProto.MAX_MESSAGE_CHARS, body["message"]!!.length)
    }
}
