package com.vythera.vyxelapps.api

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.time.Instant

/**
 * Trust config + verification primitives for Zealot's signed catalog index — leaf `1.a.ii.zi`.
 * Deliberately its own file, separate from `ZealotClient` (the reader, `1.a.i.zo`, in `AppData.kt`):
 * this is the part that must NOT trust anything the index itself claims about its own signer.
 * Native port of D-Store's `lib/sources/zealot-trust.ts` — same pinned key, same rotation-window
 * posture, same anti-rollback/expiry logic. Per that file's own framing — "the index is never its
 * own trust anchor" — the key below is pinned in this repo's own source, never fetched from
 * whatever Zealot's publish step puts alongside `index.json`/`index.json.sig` on the CDN.
 *
 * Ed25519 here is **not** `java.security`/platform crypto: Android's own `java.security.interfaces.EdECKey`
 * (and `Signature.getInstance("Ed25519")` support behind it) was only added at API 33, confirmed
 * against Android's own reference docs this session. This repo's `minSdk` is 26, so relying on the
 * platform provider would silently have no working Ed25519 on every device below Android 13 --
 * unacceptable for a trust-anchor check, not a marginal gap. BouncyCastle's raw `Ed25519Signer` /
 * `Ed25519PublicKeyParameters` (algorithm classes used directly, not registered as a JCA
 * `Provider`) work identically on every API level this app supports, so that's what's used below.
 * See `1.a.ii.zi`'s done-note in `HANDOVER.md` and the "Decisions on record" entry for this choice.
 */

data class PinnedKey(
    val keyId: String,
    /**
     * Standard (non-URL-safe) base64 of the raw 32-byte Ed25519 public key — same encoding
     * Zealot's own `CatalogIndexSigningKey#public_key` column / `signing_key.pub` file use.
     */
    val publicKeyBase64: String
)

/**
 * Rotation window: this reader accepts a signature from ANY key in this list, so a planned
 * rotation is "add the new key here, leave the old one in place until the rotation is confirmed
 * done, then remove the old one in a follow-up change" — never replace this list's only entry in
 * one step, which would make an in-flight rotation look like a rejected signature instead.
 *
 * `dbfa9202d7894165` / its base64 value below is the exact same pinned key D-Store's
 * `lib/sources/zealot-trust.ts` already carries — read directly from that file this session, not
 * retyped from memory, since both repos must trust the identical key for the identical index.
 */
val PINNED_KEYS: List<PinnedKey> = listOf(
    PinnedKey(keyId = "dbfa9202d7894165", publicKeyBase64 = "k0DusCjl424tYHMJ1XZi3jGQI/Ntl//qM+hAVweNIFY=")
)

/** Matches Zealot's `CatalogIndex::Serializer::SCHEMA_VERSION` (same value D-Store's reader pins). An index at any other version is refused outright, not best-effort parsed. */
const val SUPPORTED_SCHEMA_VERSION: Int = 2

/**
 * Verifies `signatureBase64` (detached Ed25519, RFC 8032, no pre-hash — Zealot's own documented
 * format) over the *exact* bytes of `indexJsonText` — never a re-serialized/re-parsed copy, since
 * re-encoding JSON is not guaranteed to reproduce the exact bytes that were signed. Tries every
 * pinned key, returns the first that matches, `null` if none do — every rejection reason (bad
 * base64, malformed pinned entry, wrong signature) collapses to the same "don't trust this"
 * outcome, same posture the TS original uses.
 */
fun verifySignature(indexJsonText: String, signatureBase64: String): PinnedKey? {
    val message = indexJsonText.toByteArray(Charsets.UTF_8)
    val signature = try {
        Base64.decode(signatureBase64.trim(), Base64.DEFAULT)
    } catch (_: Exception) {
        return null
    }

    for (key in PINNED_KEYS) {
        try {
            val raw = Base64.decode(key.publicKeyBase64, Base64.DEFAULT)
            if (raw.size != 32) continue // malformed pinned entry -- never a reason to throw, just never matches
            val publicKey = Ed25519PublicKeyParameters(raw, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(message, 0, message.size)
            if (verifier.verifySignature(signature)) return key
        } catch (_: Exception) {
            continue // this pinned key didn't parse or didn't match -- try the next one, never throw
        }
    }
    return null
}

/**
 * Anti-rollback state — the part of this leaf that has to survive across fetches.
 * `ZealotClient` (`AppData.kt`) persists this to its own `SharedPreferences` store, the on-device
 * equivalent of the TS reader's `storage/downloads/zealot-index-state.json`.
 *
 * ❓ open, carried over unchanged from D-Store's own flagged note (not re-decided here): every
 * index Zealot publishes today carries `sequence: 0` (a gap on Zealot's own side, not this
 * reader's), so `sequence` alone can't catch a replay yet — `generated_at` is the field that's
 * actually strictly increasing in production today. `isRollback` below keys off whichever field
 * is informative, same as the TS original: reject a lower `sequence` outright; reject when
 * `sequence` ties (true for every index today) and `generated_at` hasn't strictly advanced.
 */
data class IndexState(val sequence: Int, val generatedAt: String)

fun isRollback(candidate: IndexState, last: IndexState?): Boolean {
    if (last == null) return false
    if (candidate.sequence < last.sequence) return true
    if (candidate.sequence == last.sequence) {
        val candidateMillis = try {
            Instant.parse(candidate.generatedAt).toEpochMilli()
        } catch (_: Exception) {
            return true // unparsable "new" timestamp -- never treat as a genuine advance
        }
        val lastMillis = try {
            Instant.parse(last.generatedAt).toEpochMilli()
        } catch (_: Exception) {
            return false // unparsable "last" timestamp -- don't let a corrupt local state block every future update
        }
        return candidateMillis <= lastMillis
    }
    return false // candidate.sequence > last.sequence -- always a genuine advance
}

fun isExpired(expiresAtIso: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
    val expiresMillis = try {
        Instant.parse(expiresAtIso).toEpochMilli()
    } catch (_: Exception) {
        return true // unparsable expiry -- never trust an index whose freshness bound can't even be read
    }
    return expiresMillis <= nowMillis
}
