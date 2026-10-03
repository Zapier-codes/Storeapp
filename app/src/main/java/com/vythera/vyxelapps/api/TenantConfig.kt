package com.vythera.vyxelapps.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Runtime `TenantConfig` object — leaves `1.c.ii.zi`/`1.c.ii.zo`. Fetched on launch,
 * cached locally with a TTL and a last-known-good fallback on failed fetch, reusing
 * (not reinventing) the caching ideas this repo already has in two different places:
 *   - `MetadataClient.ensureIndex()`'s TTL gate (`AppData.kt`'s sibling `api/MetadataClient.kt`)
 *     — a fresh-enough cache means "don't hit the network at all this launch."
 *   - `ZealotClient.resolveVerifiedIndex()`'s (`AppData.kt`) last-known-good fallback — ANY
 *     fetch or verification failure serves whatever was last committed, with no TTL gate
 *     on the fallback read itself (a stale-but-verified record beats no record).
 *
 * `1.c.ii.zo`'s Ed25519 signature/trust verification (this session) reuses `1.a.ii.zi`'s
 * whole posture rather than a second trust mechanism, per this leaf's own stated scope
 * ("tenant config is at least as security-sensitive as the catalog index"): the same
 * pinned key list (`ZealotTrust.PINNED_KEYS`), the same free functions
 * (`verifySignature`/`isRollback`/`isExpired`, `ZealotTrust.kt`), and the same
 * fetch→verify→commit-or-fallback shape `ZealotClient.resolveVerifiedIndex` already
 * uses. **Deliberately the identical key, not a second tenant-config-specific one:**
 * this program has one signer (Zealot) publishing both the catalog index and (once
 * `1.c.i.zi`'s open decision #2 is resolved) tenant-config records, so a single trust
 * root is the correct model, not an accident of reuse — a compromised or rotated key
 * needs one rotation window (`PINNED_KEYS`), not two to keep in sync.
 * `TenantConfigData`'s own `schema_version` (fixed at `1`, `spec/tenant-config-schema.md`)
 * is a fully separate version number from `ZealotTrust.SUPPORTED_SCHEMA_VERSION`
 * (fixed at `2`, the *catalog index's* schema) — two different documents signed by the
 * same key, each independently versioned; this file checks its own constant, never that one.
 *
 * **Sidecar signature convention (a real decision this leaf makes, not assumed
 * elsewhere):** neither `spec/tenant-config-schema.md` nor `tenant-config.schema.json`
 * defines how a signature travels with a TenantConfig payload — there is no publisher
 * yet to have already fixed a convention (`spec/tenant-config-schema.md` "Open
 * decisions" #2, Zealot Task 37b/37c, still open). This leaf mirrors the catalog
 * index's own `index.json`/`index.json.sig` pairing exactly: `configUrl` names the
 * JSON payload, the signature is fetched from `configUrl + ".sig"`. Flagged here so
 * whichever leaf eventually builds the Zealot-side publisher matches this, not a
 * convention invented independently on that side.
 *
 * Shape mirrors `spec/tenant-config-schema.md` v1 — every field mapped here is a field
 * that document defines; nothing invented. `logo_sha256`/full-`branding` fields are
 * carried even though no caller reads them yet (`d.i.zi`/`d.i.zo`, both still open,
 * are the leaves that will), same "model the whole schema this converter's siblings
 * already commit to" posture `ZealotEntry.kt` uses for `ZealotVersion`'s unused fields.
 */
/**
 * Display name of the seed (first-party) tenant, used only until a tenant record is fetched.
 * Every on-screen mention of the store's name reads `TenantConfig.current.branding.displayName`,
 * never this constant, so a white-label tenant's own name replaces it everywhere.
 */
const val DEFAULT_DISPLAY_NAME = "Appstore"

/**
 * `User-Agent` token sent on catalog and metadata requests. A fixed ASCII token on purpose: it is
 * an HTTP header (a tenant name with spaces or non-ASCII characters can make OkHttp reject it),
 * and servers key on it, so it names the platform, not the tenant.
 */
const val HTTP_USER_AGENT = "Appstore/1.0"

data class TenantBranding(
    @SerializedName("display_name")      val displayName: String = DEFAULT_DISPLAY_NAME,
    @SerializedName("primary_color_hex") val primaryColorHex: String = "#D0BCFF",
    @SerializedName("logo_url")          val logoUrl: String = "",
    @SerializedName("logo_sha256")       val logoSha256: String = ""
)

/**
 * The exact existing hardcoded string `MetadataManager` used before this leaf. Seeding
 * the default/seed tenant record with this precise value — not a placeholder, not a
 * different CDN — is what makes "default install behavior does not change, it just
 * moves from a compile-time constant to tenant-zero's config record" (`HANDOVER.md`,
 * `1.c.ii.zi`) concretely true rather than merely asserted. The compiled-in default
 * record is trusted implicitly, same as before this leaf — it's never fetched over the
 * network, so `1.c.ii.zo`'s signature check has nothing to verify it against and never
 * runs against it; verification only gates a *fetched* record replacing this one.
 */
const val DEFAULT_CDN_BASE = "https://nikhilkain.github.io/appstore-metadata"

/**
 * `d.iv.zo`: the `tenant_id` of the first-party/default tenant, pinned client-side rather than read
 * from any fetched record's `is_default_tenant` flag -- that flag is informational only, never a
 * trust signal (`spec/tenant-config-schema.md`), so ranking must not key off it. Same value as
 * [TenantConfigData.tenantId]'s compiled-in default (the seed tenant).
 */
const val FIRST_PARTY_TENANT_ID = "default"

data class TenantConfigData(
    @SerializedName("schema_version")         val schemaVersion: Int = 1,
    @SerializedName("tenant_id")              val tenantId: String = FIRST_PARTY_TENANT_ID,
    @SerializedName("generated_at")           val generatedAt: String = "",
    @SerializedName("sequence")               val sequence: Int = 0,
    @SerializedName("expires_at")             val expiresAt: String = "",
    val branding                              : TenantBranding = TenantBranding(),
    @SerializedName("cdn_base")               val cdnBase: String = DEFAULT_CDN_BASE,
    @SerializedName("catalog_index_base_url") val catalogIndexBaseUrl: String = "",
    val domains                               : List<String> = emptyList(),
    @SerializedName("is_default_tenant")      val isDefaultTenant: Boolean = true
)

/** Matches `tenant-config.schema.json`'s `schema_version.const`. A separate constant from `ZealotTrust.SUPPORTED_SCHEMA_VERSION` on purpose — different document, independently versioned, same signer. */
const val TENANT_CONFIG_SCHEMA_VERSION: Int = 1

object TenantConfig {

    /**
     * Per-tenant fetch endpoint (the JSON payload; its signature is fetched from
     * `configUrl + ".sig"`, see this file's header comment). Mirrors `ZealotClient.baseUrl`'s
     * own posture exactly: blank/unset means "no live source configured yet," not an
     * error — there is no `TenantConfig` publisher anywhere in this program today
     * (`spec/tenant-config-schema.md` "Open decisions" #2 — Zealot Task 37b/37c, still
     * open), so this stays blank in practice until that lands. `current` must
     * therefore always resolve to *something* valid on its own — see below — never
     * `null`, since `MetadataManager.init` needs an always-available `cdnBase` on the
     * very first frame.
     */
    @Volatile var configUrl: String = ""

    @Volatile private var _current: TenantConfigData = TenantConfigData()
    val current: TenantConfigData get() = _current

    private const val PREFS            = "tenant_config_cache"
    private const val KEY_JSON         = "last_good_config"
    private const val KEY_TS           = "last_good_ts"
    private const val KEY_SEQUENCE     = "last_sequence"
    private const val KEY_GENERATED_AT = "last_generated_at"

    /** Same TTL `MetadataClient` already uses for its own index cache — not a fresh number invented for this leaf. */
    private const val CACHE_TTL = 60 * 60 * 1000L

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Call once on launch, before `MetadataManager.init(ctx)` reads `current.cdnBase`
     * (`AppViewModel.init`, `AppData.kt`). Synchronously loads whatever was last
     * committed to disk into memory — no TTL gate on this read, matching
     * `ZealotClient`'s "a stale-but-verified record beats no record" posture, since
     * this is the fallback path, not the freshness-optimization path — then fires a
     * background refresh attempt. Never suspends: `MetadataManager.init`'s cdnBase
     * read must not block the first frame on network.
     */
    fun init(context: Context) {
        loadCachedIntoMemory(context)
        CoroutineScope(Dispatchers.IO).launch {
            try { refreshIfStale(context) } catch (_: Exception) {}
        }
    }

    /** TTL gate only — same role `MetadataClient.ensureIndex()`'s own freshness check plays before it decides a real fetch is worth doing at all. */
    private suspend fun refreshIfStale(context: Context) {
        val lastFetchAt = prefs(context).getLong(KEY_TS, 0L)
        if (System.currentTimeMillis() - lastFetchAt < CACHE_TTL) return
        refresh(context)
    }

    /** This device's last-committed anti-rollback state, for `isRollback`'s `last` argument — same shape/role as `ZealotClient.lastState`, kept in this object's own prefs store since a device resolves one tenant's sequence track at a time, not a shared one with the catalog index. */
    private fun lastState(context: Context): IndexState? {
        val p   = prefs(context)
        val seq = p.getInt(KEY_SEQUENCE, -1)
        val gen = p.getString(KEY_GENERATED_AT, null)
        return if (seq >= 0 && gen != null) IndexState(seq, gen) else null
    }

    /**
     * Unconditional fetch-and-verify attempt: fetches `configUrl` + its `.sig` sidecar
     * (in parallel, same shape `ZealotClient.fetchIndex` already uses), then runs the
     * exact same signature → schema-version → expiry → anti-rollback gauntlet
     * `ZealotClient.validate` runs for the catalog index, reusing `ZealotTrust.kt`'s
     * free functions rather than re-implementing any of the four checks. `current` and
     * the on-disk cache only advance together, and only once every check passes.
     *
     * On ANY failure — unset `configUrl`, network error on either fetch, non-2xx on
     * either, unparsable JSON, wrong `schema_version`, bad/missing signature, expired,
     * or rolled back — `current` is left exactly as `init()`'s synchronous load already
     * set it: the last-known-good *verified* record, or the compiled-in default tenant
     * if nothing has ever verified on this device. Every rejection reason collapses to
     * the same outcome, same posture `ZealotClient.resolveVerifiedIndex` already uses.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val url = configUrl.trim()
        if (url.isEmpty()) return@withContext
        try {
            val configDeferred = kotlinx.coroutines.async {
                http.newCall(Request.Builder().url(url).build()).execute()
            }
            val sigDeferred = kotlinx.coroutines.async {
                http.newCall(Request.Builder().url("$url.sig").build()).execute()
            }
            val configResp = configDeferred.await()
            val sigResp     = sigDeferred.await()
            configResp.use { cResp ->
                sigResp.use { sResp ->
                    if (!cResp.isSuccessful || !sResp.isSuccessful) return@withContext
                    val json    = cResp.body?.string() ?: return@withContext
                    val sigText = sResp.body?.string()?.trim() ?: return@withContext

                    val parsed = try {
                        gson.fromJson(json, TenantConfigData::class.java)
                    } catch (_: Exception) { null } ?: return@withContext
                    if (parsed.schemaVersion != TENANT_CONFIG_SCHEMA_VERSION) return@withContext

                    // 1.c.ii.zo: signature, expiry, and anti-rollback -- same checks,
                    // same free functions, `ZealotClient.validate` already runs for the
                    // catalog index (`ZealotTrust.kt`), never re-implemented here.
                    if (verifySignature(json, sigText) == null) return@withContext
                    if (isExpired(parsed.expiresAt)) return@withContext
                    val candidate = IndexState(parsed.sequence, parsed.generatedAt)
                    if (isRollback(candidate, lastState(context))) return@withContext

                    _current = parsed
                    prefs(context).edit()
                        .putString(KEY_JSON, json)
                        .putLong(KEY_TS, System.currentTimeMillis())
                        .putInt(KEY_SEQUENCE, candidate.sequence)
                        .putString(KEY_GENERATED_AT, candidate.generatedAt)
                        .apply()
                }
            }
        } catch (_: Exception) {
            Log.w("TenantConfig", "refresh failed, keeping last-known-good/default")
        }
    }

    private fun loadCachedIntoMemory(context: Context) {
        val json = prefs(context).getString(KEY_JSON, null) ?: return
        try {
            gson.fromJson(json, TenantConfigData::class.java)?.let { _current = it }
        } catch (_: Exception) {
            // corrupt cache -- leave `current` at the compiled-in default rather than crash launch
        }
    }
}
