package com.vythera.vyxelapps.review

import com.vythera.vyxelapps.api.ReviewProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Z-P9 (principle 2), client half: the proof-of-work solver and the wire contract. The solver is checked
 * against real digests (the same loop Zealot's `ReviewChallenge#solution_valid?` runs server-side), and the
 * signed payload against a digest Zealot's `AnonymousReviewService.signed_payload` produces, so the two cannot
 * drift. Pure Kotlin, no Android — runs on any JVM.
 */
class ReviewClientTest {

    // A fixed challenge, so the solved numbers are constants. Produced by the Ruby verifier:
    //   cost=1 -> 16, cost=2 -> 124, cost=4 -> 42965, over SHA-256(salt+number+nonce).
    private val salt = "c2FsdHZhbHVlMTIz"
    private val nonce = "bm9uY2V2YWx1ZTEyMzQ1Ng"

    private fun challenge(cost: Int) = ReviewProto.Challenge(nonce = nonce, salt = salt, cost = cost)

    @Test
    fun `solves cost 1 at the known number`() {
        val n = ReviewProto.solve(challenge(1))
        assertEquals(16L, n)
        assertTrue(hasLeadingZeros(n!!, 1))
    }

    @Test
    fun `solves cost 2 and 4 at the known numbers`() {
        assertEquals(124L, ReviewProto.solve(challenge(2)))
        assertEquals(42965L, ReviewProto.solve(challenge(4)))
    }

    @Test
    fun `cost 0 accepts the first number without hashing anything away`() {
        assertEquals(0L, ReviewProto.solve(challenge(0)))
    }

    @Test
    fun `refuses a cost this client will not grind`() {
        assertNull(ReviewProto.solve(challenge(ReviewProto.MAX_SOLVABLE_COST + 1)))
        assertNull(ReviewProto.solve(challenge(-1)))
    }

    @Test
    fun `refuses a blank nonce or salt rather than hanging`() {
        assertNull(ReviewProto.solve(ReviewProto.Challenge(nonce = "", salt = salt, cost = 1)))
        assertNull(ReviewProto.solve(ReviewProto.Challenge(nonce = nonce, salt = "", cost = 1)))
    }

    @Test
    fun `derives the review base from the catalog URL`() {
        assertEquals("https://host.example.com", ReviewProto.deriveBase("https://host.example.com/catalog"))
        assertEquals("https://host.example.com", ReviewProto.deriveBase("https://host.example.com/catalog/"))
        assertEquals("https://host.example.com", ReviewProto.deriveBase("https://host.example.com"))
        assertEquals("", ReviewProto.deriveBase(""))
    }

    @Test
    fun `builds the three route URLs`() {
        val base = "https://host.example.com"
        assertEquals("$base/reviews/com.example.app/challenge", ReviewProto.challengeUrl("com.example.app", base))
        assertEquals("$base/reviews/com.example.app", ReviewProto.submitUrl("com.example.app", base))
        assertEquals("$base/reviews/com.example.app/keys", ReviewProto.registerKeyUrl("com.example.app", base))
    }

    @Test
    fun `the signed payload matches Zealot's byte contract`() {
        // Zealot: ['ZP9v1', package, rating, sha256(body), version_code, challenge].join("\n")
        val payload = ReviewProto.signedPayload("com.example.app", 5, "Works well", "42", nonce)
        val expectedBody = "8d2e731fe60fa1b264920a5af32591c4ddf657eebdc2619dcf31a764e8f9fe7d"
        assertEquals(
            listOf("ZP9v1", "com.example.app", "5", expectedBody, "42", nonce).joinToString("\n"),
            payload
        )
        // And the body digest really is the SHA-256 the console computes.
        assertEquals(expectedBody, sha256Hex("Works well"))
    }

    private fun hasLeadingZeros(n: Long, cost: Int): Boolean =
        sha256Hex("$salt$n$nonce").startsWith("0".repeat(cost))

    private fun sha256Hex(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
