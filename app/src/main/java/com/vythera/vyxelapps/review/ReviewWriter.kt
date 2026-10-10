package com.vythera.vyxelapps.review

import android.content.Context
import com.vythera.vyxelapps.api.ReviewClient
import com.vythera.vyxelapps.api.ReviewProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Z-P9 (principle 2): post an anonymous review from the phone. This is the small orchestration the two halves
 * need — `DeviceReviewKey` (the Keystore key) and `ReviewClient` (the wire). The canonical signed payload lives
 * in `ReviewProto` (one copy, shared with the console's `AnonymousReviewService.signed_payload`), so the two
 * cannot drift.
 *
 * The key is optional. A review with no key still posts (the console accepts it as a fresh anonymous row), it
 * just cannot be edited later and can never carry the "verified install" mark. The key is worth making when the
 * app you are reviewing is the one this process is; `versionCode` is read from the installed package for exactly
 * that case.
 */
object ReviewWriter {

    /** The canonical bytes signed by the device key — see `ReviewProto.signedPayload`, the one implementation. */
    fun signedPayload(packageName: String, rating: Int, body: String, versionCode: String?, challenge: String): String =
        ReviewProto.signedPayload(packageName, rating, body, versionCode, challenge)

    /**
     * Post a review for [packageName]. Makes/uses the device key, asks the server for a challenge, binds the key
     * to that challenge, signs the payload, and submits. Returns the server's answer, or null for "could not
     * post" — a blank base URL, an unsolvable challenge, or any refusal. Nothing here throws to the caller.
     */
    suspend fun post(
        context: Context,
        packageName: String,
        rating: Int,
        body: String,
        reviewOwnPackage: Boolean = false,
    ): ReviewProto.SubmitResult? = withContext(Dispatchers.IO) {
        val challenge = ReviewClient.fetchChallenge(packageName) ?: return@withContext null

        var fingerprint: String? = null
        var signature: String? = null
        var versionCode: String? = null

        if (reviewOwnPackage) {
            val key = DeviceReviewKey(context)
            // Make the key under this very challenge, so the attestation certificate can only describe a key
            // created for this registration. A key already present is reused unchanged.
            if (key.ensureKey(challenge.nonce)) {
                val pem = key.publicKeyPem()
                if (pem != null) {
                    // Register the key before the review: the console resolves a submitted fingerprint only
                    // against a key it has already checked, so an unregistered key would be refused. Registration
                    // records whatever the attestation result is (verified or not); the review only earns the
                    // install mark when it is verified.
                    fingerprint = ReviewClient.registerKey(packageName, pem, key.attestationChainPem(), challenge.nonce)
                        ?: key.fingerprint()
                }
                versionCode = installedVersionCode(context, packageName)
                if (fingerprint != null) {
                    val payload = ReviewProto.signedPayload(packageName, rating, body, versionCode, challenge.nonce)
                    signature = key.sign(payload)
                }
            }
        }

        val solution = ReviewClient.solve(challenge) ?: return@withContext null
        val json = ReviewClient.buildSubmitJson(
            rating = rating,
            body = body,
            challengeNonce = challenge.nonce,
            solution = solution,
            reviewerKeyFingerprint = fingerprint,
            signature = signature,
            versionCode = versionCode,
        )
        try {
            ReviewClient.parseSubmitResult(
                com.vythera.vyxelapps.expressive.core.net.Net.postJson(ReviewClient.submitUrl(packageName), json)
            )
        } catch (_: Exception) {
            null
        }
    }

    /** The installed version code of [packageName], as a string, or null if it is not installed. */
    fun installedVersionCode(context: Context, packageName: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION")
        info.versionCode.toString()
    }.getOrNull()
}
