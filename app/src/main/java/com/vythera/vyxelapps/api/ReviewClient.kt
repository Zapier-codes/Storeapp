package com.vythera.vyxelapps.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.vythera.vyxelapps.BuildConfig
import com.vythera.vyxelapps.expressive.core.net.Net

/**
 * Z-P9 (principle 2), client half: write an anonymous review to Zealot. "Anonymous" is literal — no account,
 * no sign-in, no email. The price of posting without an account is a small proof of work (ALTCHA shape) plus
 * a device-bound key, and this object is where the client pays both.
 *
 * Three calls, matching the console's own routes:
 *   GET  {base}/reviews/{package}/challenge   -> nonce + salt + cost
 *   POST {base}/reviews/{package}/keys        -> register a device key (attestation checked server-side)
 *   POST {base}/reviews/{package}             -> the solved, signed review
 *
 * The pure contract (URL shapes, the solver, the canonical signed payload) lives in [ReviewProto] and is
 * unit-tested on a plain JVM; this object only adds JSON and the HTTP calls. The Android-only pieces
 * (generating a Keystore key, reading the installed version code) live in `review/DeviceReviewKey.kt` and
 * `review/ReviewWriter.kt`.
 *
 * The base URL is derived from the same host the catalog index comes from (`BuildConfig.ZEALOT_CATALOG_URL` is
 * `https://host/catalog`, so the review base is `https://host`), so a deployment configures one URL, not two.
 * Blank means "not configured yet", not an error — the same posture every other client here takes.
 */
object ReviewClient {

    /** `https://host` — `ZEALOT_CATALOG_URL` with its `/catalog` suffix removed. */
    @Volatile var baseUrl: String = ReviewProto.deriveBase(BuildConfig.ZEALOT_CATALOG_URL)

    private val gson = Gson()

    private data class ChallengeWire(
        val nonce: String? = null,
        val salt: String? = null,
        val cost: Int? = null,
    )

    private data class ReviewResponseWire(val review: ReviewWire? = null, val decision: String? = null)
    private data class ReviewWire(
        val id: Long? = null,
        val rating: Int? = null,
        val body: String? = null,
        val status: String? = null,
        @SerializedName("verified_install") val verifiedInstall: Boolean? = null,
    )

    private data class SubmitBody(
        val rating: Int,
        val body: String,
        val challenge: String,
        val solution: Long,
        @SerializedName("reviewer_key_fingerprint") val reviewerKeyFingerprint: String? = null,
        val signature: String? = null,
        @SerializedName("version_code") val versionCode: String? = null,
    )

    fun challengeUrl(packageName: String, base: String = baseUrl): String = ReviewProto.challengeUrl(packageName, base)
    fun submitUrl(packageName: String, base: String = baseUrl): String = ReviewProto.submitUrl(packageName, base)
    fun registerKeyUrl(packageName: String, base: String = baseUrl): String = ReviewProto.registerKeyUrl(packageName, base)

    fun deriveBase(catalogUrl: String): String = ReviewProto.deriveBase(catalogUrl)

    /** Solve a proof-of-work challenge. See [ReviewProto.solve]. */
    fun solve(challenge: ReviewProto.Challenge): Long? = ReviewProto.solve(challenge)

    fun parseChallenge(json: String): ReviewProto.Challenge? = runCatching {
        val wire = gson.fromJson(json, ChallengeWire::class.java) ?: return null
        val nonce = wire.nonce?.takeIf { it.isNotBlank() } ?: return null
        val salt = wire.salt?.takeIf { it.isNotBlank() } ?: return null
        ReviewProto.Challenge(nonce = nonce, salt = salt, cost = wire.cost ?: 0)
    }.getOrNull()

    fun parseSubmitResult(json: String): ReviewProto.SubmitResult? = runCatching {
        val wire = gson.fromJson(json, ReviewResponseWire::class.java) ?: return null
        val review = wire.review
        ReviewProto.SubmitResult(
            id = review?.id,
            rating = review?.rating,
            body = review?.body,
            status = review?.status,
            verifiedInstall = review?.verifiedInstall ?: false,
            decision = wire.decision,
        )
    }.getOrNull()

    /** The JSON body for POST /reviews/{package}. Public so a test can assert the exact wire shape. */
    fun buildSubmitJson(
        rating: Int,
        body: String,
        challengeNonce: String,
        solution: Long,
        reviewerKeyFingerprint: String? = null,
        signature: String? = null,
        versionCode: String? = null,
    ): String = gson.toJson(
        SubmitBody(
            rating = rating,
            body = body,
            challenge = challengeNonce,
            solution = solution,
            reviewerKeyFingerprint = reviewerKeyFingerprint,
            signature = signature,
            versionCode = versionCode,
        )
    )

    private data class RegisterKeyBody(
        @SerializedName("public_key") val publicKey: String,
        @SerializedName("attestation_chain") val attestationChain: List<String>,
        val challenge: String,
    )

    private data class RegisterKeyResponse(val fingerprint: String? = null)

    /** The JSON body for POST /reviews/{package}/keys. */
    fun buildRegisterKeyJson(publicKeyPem: String, attestationChain: List<String>, challenge: String): String =
        gson.toJson(RegisterKeyBody(publicKeyPem, attestationChain, challenge))

    /**
     * Register a device key with the server so a review signed by it can be resolved. Returns the server's
     * fingerprint, or null on a blank base URL, a refused registration, or a network error. The chain must be
     * leaf-first; the server records whatever the attestation check says (it never silently upgrades an
     * unverified key), so a device without a real chain still gets a usable but unverified key.
     */
    suspend fun registerKey(
        packageName: String,
        publicKeyPem: String,
        attestationChain: List<String>,
        challenge: String,
    ): String? {
        if (baseUrl.isBlank()) return null
        val json = buildRegisterKeyJson(publicKeyPem, attestationChain, challenge)
        return try {
            gson.fromJson(Net.postJson(registerKeyUrl(packageName), json), RegisterKeyResponse::class.java)?.fingerprint
        } catch (_: Exception) {
            null
        }
    }

    /** Fetch a challenge. Returns null when [baseUrl] is blank, on any non-2xx, or on a network error. */
    suspend fun fetchChallenge(packageName: String): ReviewProto.Challenge? {
        if (baseUrl.isBlank()) return null
        return try {
            parseChallenge(Net.getString(challengeUrl(packageName), retries = 1))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Solve a challenge and post the review. Returns the server's answer, or null for "could not post" —
     * which includes a blank base URL, a challenge this client cannot solve, and any network error. A call
     * that reaches the server but is refused (bad solution, rate limit) also returns null: the console's
     * non-2xx is not retried here, and the UI shows nothing rather than a wrong success.
     */
    suspend fun submit(
        packageName: String,
        rating: Int,
        body: String,
        reviewerKeyFingerprint: String? = null,
        signature: String? = null,
        versionCode: String? = null,
    ): ReviewProto.SubmitResult? {
        if (baseUrl.isBlank()) return null
        val challenge = fetchChallenge(packageName) ?: return null
        val solution = solve(challenge) ?: return null
        val payload = buildSubmitJson(
            rating = rating,
            body = body,
            challengeNonce = challenge.nonce,
            solution = solution,
            reviewerKeyFingerprint = reviewerKeyFingerprint,
            signature = signature,
            versionCode = versionCode,
        )
        return try {
            parseSubmitResult(Net.postJson(submitUrl(packageName), payload))
        } catch (_: Exception) {
            null
        }
    }
}
