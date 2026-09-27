package com.vythera.vyxelapps.api

import android.content.Context
import com.google.gson.Gson
import com.vythera.vyxelapps.ZealotClient
import okhttp3.OkHttpClient

/**
 * Federated-catalog-index client — leaf `d.iv.zi`. **Architecture decision made this session,
 * recorded here and in `HANDOVER.md`'s "Decisions on record" (Section 2): aggregation layer, not
 * a `TenantConfig` schema-v2 fan-out.** The alternative this leaf's own text raised — each
 * `TenantConfig` record listing every sibling tenant's `catalog_index_base_url` so the client
 * fetches and Ed25519-verifies N indexes itself — was rejected because it makes every device pay
 * a fetch-plus-signature-check for every tenant in the program, a cost that grows unbounded with
 * tenant count and duplicates `1.a.ii.zi`'s verification machinery N times over for no benefit.
 * Zealot (the console) already knows the full tenant set server-side, so it publishes ONE extra
 * signed index — every tenant's apps merged, same `apps: [...]` shape `ZealotIndex` already
 * models, each entry additionally carrying `tenant_id` (`ZealotEntry.kt`) so a federated entry can
 * self-identify its origin tenant — and this object fetches that ONE index, reusing every piece of
 * `1.a.ii.zi`'s trust machinery verbatim (`verifySignature`/`isRollback`/`isExpired`,
 * `PINNED_KEYS`, `SUPPORTED_SCHEMA_VERSION`) rather than a second, parallel verification path.
 *
 * Deliberately its own object, not a second `baseUrl` on `ZealotClient`: a federated index is a
 * genuinely different published artifact from a tenant's own catalog index (different content,
 * different publish cadence on Zealot's side), and giving it its own last-good-cache
 * (`federated_trust_state`, below) means a federation-index outage can never silently roll back or
 * clobber a device's already-verified *own-tenant* Zealot state, and vice versa — two independent
 * failure domains, matching this repo's existing "no shared state between independently-trusted
 * things" posture (e.g. `f.x`'s own database-per-service decision).
 *
 * `baseUrl` mirrors `ZealotClient.baseUrl`'s own "blank means not configured yet" posture — same
 * honest empty-result fallback, not an error, and same reason: no real federation endpoint is
 * published anywhere in this program yet (Zealot-side work, out of this repo). **Deliberately NOT
 * a `TenantConfig` field:** a federation index is, by definition, identical across every tenant —
 * it is not this tenant's own data, so it doesn't belong in a per-tenant record; it's a
 * program-wide endpoint, the same "settable object, not a schema field" tier `ZealotClient.baseUrl`
 * itself already occupies.
 */
object FederatedCatalogClient {
    @Volatile var baseUrl: String = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("User-Agent", "VyxelApps/1.0").build())
        }.build()

    /** Same shape as `ZealotClient.RawIndexFetch` — kept as its own type rather than reusing that
     *  one directly, since the two clients' fetch results should never be interchangeable by
     *  accident (a federated fetch result passed to `ZealotClient`'s verify path, or vice versa,
     *  would be a real bug this type distinction catches at compile time). */
    data class RawFederatedFetch(val indexText: String, val signatureText: String)

    /** Fetches `index.json` + `index.json.sig` from `baseUrl`, same two-file convention every
     *  other Zealot-shaped index in this program uses. Returns null on a blank/unset `baseUrl`,
     *  any non-2xx response, or a network error — identical failure collapsing to `ZealotClient
     *  .fetchIndex()`'s own posture, so the caller has one shape to handle regardless of which
     *  index it asked for. */
    suspend fun fetchIndex(): RawFederatedFetch? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext null
        try {
            val indexReq = okhttp3.Request.Builder().url("$base/index.json").build()
            val sigReq   = okhttp3.Request.Builder().url("$base/index.json.sig").build()
            val indexDeferred = kotlinx.coroutines.async { http.newCall(indexReq).execute() }
            val sigDeferred   = kotlinx.coroutines.async { http.newCall(sigReq).execute() }
            val indexResp = indexDeferred.await()
            val sigResp   = sigDeferred.await()
            indexResp.use { iResp ->
                sigResp.use { sResp ->
                    if (!iResp.isSuccessful || !sResp.isSuccessful) return@withContext null
                    val indexText = iResp.body?.string() ?: return@withContext null
                    val sigText   = sResp.body?.string()?.trim() ?: return@withContext null
                    RawFederatedFetch(indexText, sigText)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Verify + last-good-index cache ──────────────────────────────────────────
    // Same fetch → verify → persist/fallback orchestration as ZealotClient, against its own
    // SharedPreferences store so a federation-index failure can never touch the per-tenant
    // Zealot state, or be touched by it.

    private const val TRUST_PREFS      = "federated_trust_state"
    private const val KEY_SEQUENCE     = "last_sequence"
    private const val KEY_GENERATED_AT = "last_generated_at"
    private const val KEY_CACHED_INDEX = "last_good_index"
    private const val KEY_CACHED_SIG   = "last_good_sig"

    private fun trustPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(TRUST_PREFS, Context.MODE_PRIVATE)

    private fun lastState(context: Context): IndexState? {
        val prefs = trustPrefs(context)
        val seq = prefs.getInt(KEY_SEQUENCE, -1)
        val gen = prefs.getString(KEY_GENERATED_AT, null)
        return if (seq >= 0 && gen != null) IndexState(seq, gen) else null
    }

    private fun commitState(context: Context, envelope: ZealotClient.IndexEnvelope, indexText: String, signatureText: String) {
        trustPrefs(context).edit()
            .putInt(KEY_SEQUENCE, envelope.sequence)
            .putString(KEY_GENERATED_AT, envelope.generated_at)
            .putString(KEY_CACHED_INDEX, indexText)
            .putString(KEY_CACHED_SIG, signatureText)
            .apply()
    }

    private fun lastGoodIndexText(context: Context): String? =
        trustPrefs(context).getString(KEY_CACHED_INDEX, null)

    /** Identical check sequence to `ZealotClient.validate` — same pinned keys, same schema
     *  constant, same rollback/expiry rules — reused as function calls, not re-implemented,
     *  against this client's own `lastState`. */
    private fun validate(context: Context, indexText: String, signatureText: String): ZealotClient.IndexEnvelope? {
        if (verifySignature(indexText, signatureText) == null) return null
        val envelope = try {
            Gson().fromJson(indexText, ZealotClient.IndexEnvelope::class.java)
        } catch (_: Exception) { return null }
        if (envelope.schema_version != SUPPORTED_SCHEMA_VERSION) return null
        if (isExpired(envelope.expires_at)) return null
        val candidate = IndexState(envelope.sequence, envelope.generated_at)
        if (isRollback(candidate, lastState(context))) return null
        return envelope
    }

    /**
     * Public entry point, same contract as `ZealotClient.resolveVerifiedIndex`: fetch, verify,
     * and on ANY failure fall back to the last federated index this device itself already
     * verified. Returns the raw, now-trusted `index.json` text, or `null` if nothing has ever
     * verified successfully on this device — parsing it into `ZealotEntry`/`GitHubRepo` is the
     * caller's job (`AppViewModel.fetchZealotApps()`), reusing `parseZealotEntries()` verbatim
     * since a federated index is the same `apps: [...]` shape, just with `tenant_id` populated
     * per entry.
     */
    suspend fun resolveVerifiedIndex(context: Context): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val fetched = fetchIndex()
            if (fetched != null) {
                val envelope = validate(context, fetched.indexText, fetched.signatureText)
                if (envelope != null) {
                    commitState(context, envelope, fetched.indexText, fetched.signatureText)
                    return@withContext fetched.indexText
                }
            }
            lastGoodIndexText(context)
        }
}
