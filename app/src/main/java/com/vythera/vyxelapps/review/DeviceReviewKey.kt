package com.vythera.vyxelapps.review

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Z-P9 (principle 2), Android side: the device-bound pseudonymous review key. The key is made in the Android
 * Keystore with an attestation challenge, so the private half never leaves secure hardware and the certificate
 * the Keystore hands back is a statement, signed by the device, about *that* key — which the console checks
 * (`AndroidKeyAttestation`) to earn the "verified install" mark.
 *
 * This key is deliberately not an account: there is no email, no profile, no sign-in. It exists only so a
 * person has one editable review per app (Zealot keys reviews on the key's fingerprint) and so a review that
 * arrived with proof the build was installed can be marked.
 *
 * The alias is stable per app install (`vyxel_review_key`); a reinstall makes a new key and therefore a new
 * pseudonym, which is honest — the old one is gone. The fingerprint is the SHA-256 of the public key's SPKI
 * bytes, the same bytes Zealot hashes (`ReviewerKey.fingerprint_for`), so the two cannot disagree.
 */
class DeviceReviewKey(context: Context) {

    private val appContext = context.applicationContext
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** The key's fingerprint, or null if the key cannot be read/made (no Keystore, trimmed device). */
    fun fingerprint(): String? = publicKeyDer()?.let { hex(MessageDigest.getInstance("SHA-256").digest(it)) }

    /** The PEM of the key's public half, or null. */
    fun publicKeyPem(): String? = certificate()?.publicKey?.encoded?.let { der ->
        buildString {
            append("-----BEGIN PUBLIC KEY-----\n")
            append(Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n"))
            append("\n-----END PUBLIC KEY-----\n")
        }
    }

    /** The attestation certificate chain, leaf first, each PEM — what the console verifies. Empty if none. */
    fun attestationChainPem(): List<String> =
        runCatching {
            keyStore.getCertificateChain(ALIAS)?.map { cert -> pem("CERTIFICATE", cert.encoded) } ?: emptyList()
        }.getOrDefault(emptyList())

    /**
     * Make the key if it is not there yet, binding `challenge` (a server nonce) into the attestation so the
     * certificate can only ever describe a key made for this registration. A key that already exists is reused:
     * a new review does not make a new identity.
     */
    fun ensureKey(challenge: String): Boolean = runCatching {
        if (keyStore.containsAlias(ALIAS)) return true
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge.toByteArray())
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            .apply { initialize(spec) }
            .generateKeyPair()
        true
    }.getOrDefault(false)

    /**
     * Sign `payload` with the Keystore key (ECDSA/SHA-256), returning a base64 signature, or null. The payload
     * is the exact string Zealot's `AnonymousReviewService.signed_payload` builds; a single byte of drift makes
     * the console refuse the signature, which is the intended failure — never a silently wrong review.
     */
    fun sign(payload: String): String? = runCatching {
        val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry ?: return null
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(entry.privateKey)
        sig.update(payload.toByteArray())
        Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }.getOrNull()

    private fun certificate(): java.security.cert.Certificate? =
        runCatching { keyStore.getCertificate(ALIAS) }.getOrNull()

    private fun publicKeyDer(): ByteArray? =
        runCatching { certificate()?.publicKey?.encoded }.getOrNull()

    private fun pem(label: String, der: ByteArray): String = buildString {
        append("-----BEGIN $label-----\n")
        append(Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n"))
        append("\n-----END $label-----\n")
    }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) out.append(String.format("%02x", b))
        return out.toString()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "vyxel_review_key"
    }
}
