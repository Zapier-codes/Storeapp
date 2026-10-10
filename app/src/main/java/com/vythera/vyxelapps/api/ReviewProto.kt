package com.vythera.vyxelapps.api

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Z-P9 (principle 2), the pure half of the anonymous-review client: the URL shapes, the proof-of-work solver
 * and the canonical signed payload. Deliberately free of Android and JSON imports — the console's contract is
 * a byte contract, and keeping it here means it is exercised on a plain JVM (`ReviewClientTest`) against real
 * digests, exactly as Zealot's `ReviewChallenge#solution_valid?` and `AnonymousReviewService.signed_payload`
 * compute them.
 *
 * `ReviewClient` (gson + `Net`) and `device review/ReviewWriter` (Android) both delegate here, so there is one
 * copy of each rule, not three.
 */
object ReviewProto {

    /** A challenge the server issues. `cost` is the number of leading zero hex nibbles the solution must have. */
    data class Challenge(val nonce: String, val salt: String, val cost: Int)

    /** The server's answer to a solved review. */
    data class SubmitResult(
        val id: Long? = null,
        val rating: Int? = null,
        val body: String? = null,
        /** `published`, `pending` (held by the moderator) or `rejected`. */
        val status: String? = null,
        val verifiedInstall: Boolean = false,
        val decision: String? = null,
    )

    /** A cost the phone should never be asked to grind: 6 nibbles is ~1/16M, far past a review. */
    const val MAX_SOLVABLE_COST = 6

    /** A hard stop so a pathological challenge cannot spin forever; well above any real cost's expected tries. */
    const val MAX_TRIES = 1L shl 24

    /** `https://host/catalog` -> `https://host`. Leaves anything without the suffix untouched. */
    fun deriveBase(catalogUrl: String): String {
        val trimmed = catalogUrl.trim().trimEnd('/')
        return when {
            trimmed.isEmpty() -> ""
            trimmed.endsWith("/catalog") -> trimmed.removeSuffix("/catalog")
            else -> trimmed
        }
    }

    fun challengeUrl(packageName: String, base: String): String =
        "${base.trim().trimEnd('/')}/reviews/${encode(packageName)}/challenge"

    fun submitUrl(packageName: String, base: String): String =
        "${base.trim().trimEnd('/')}/reviews/${encode(packageName)}"

    fun registerKeyUrl(packageName: String, base: String): String =
        "${base.trim().trimEnd('/')}/reviews/${encode(packageName)}/keys"

    /**
     * Solve a proof-of-work challenge: the smallest `number >= 0` whose `SHA-256(salt + number + nonce)` has at
     * least `cost` leading zero hex nibbles. Returns null for a cost this client refuses to grind (negative, or
     * past [MAX_SOLVABLE_COST]) and for a blank nonce/salt — a null is "cannot post", never a hang.
     *
     * The same loop the server verifies (`ReviewChallenge#solution_valid?`); the digest input order is fixed by
     * the console, so the two must not drift.
     */
    fun solve(challenge: Challenge): Long? {
        if (challenge.nonce.isBlank() || challenge.salt.isBlank()) return null
        val cost = challenge.cost
        if (cost < 0 || cost > MAX_SOLVABLE_COST) return null
        val prefix = "0".repeat(cost)
        val digest = MessageDigest.getInstance("SHA-256")
        var n = 0L
        while (true) {
            val hex = hex(digest.digest("${challenge.salt}$n${challenge.nonce}".toByteArray()))
            if (cost == 0 || hex.startsWith(prefix)) return n
            n += 1
            if (n > MAX_TRIES) return null
        }
    }

    /**
     * The canonical bytes signed by the device key. Field order and the SHA-256 of the body are fixed by the
     * console's `AnonymousReviewService.signed_payload`:
     * `["ZP9v1", package, rating, sha256(body), version_code, challenge].join("\n")`.
     */
    fun signedPayload(packageName: String, rating: Int, body: String, versionCode: String?, challenge: String): String {
        val bodyDigest = hex(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
        return listOf("ZP9v1", packageName, rating.toString(), bodyDigest, versionCode ?: "", challenge).joinToString("\n")
    }

    /** Percent-encode a package name's dot-separated segments; each segment is already `[A-Za-z0-9_]`. */
    private fun encode(packageName: String): String =
        packageName.trim().split('.').joinToString(".") { seg -> URLEncoder.encode(seg, "UTF-8") }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) out.append(String.format("%02x", b))
        return out.toString()
    }
}
