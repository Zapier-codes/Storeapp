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
 * Runtime `TenantConfig` object — leaf `1.c.ii.zi`. Fetched on launch, cached locally
 * with a TTL and a last-known-good fallback on failed fetch, reusing (not reinventing)
 * the two caching ideas this repo already has in two different places rather than
 * combining them nowhere until now:
 *   - `MetadataClient.ensureIndex()`'s TTL gate (`AppData.kt`'s sibling `api/MetadataClient.kt`)
 *     — a fresh-enough cache means "don't hit the network at all this launch."
 *   - `ZealotClient.resolveVerifiedIndex()`'s (`AppData.kt`) last-known-good fallback — ANY
 *     fetch failure serves whatever was last committed, with no TTL gate on the fallback
 *     read itself (a stale record beats no record).
 * This leaf does **not** verify anything — no signature check on the fetched payload.
 * That's `1.c.ii.zo`, explicitly held until this leaf lands, reusing `1.a.ii.zi`'s
 * pinned-key/rotation-window/anti-rollback posture (`ZealotTrust.kt`) rather than a
 * second trust mechanism. Until `1.c.ii.zo` wires a `Verifier`-equivalent in front of
 * `refresh()` below, an unsigned/untrusted `configUrl` response is accepted as-is —
 * acceptable for this leaf only because `configUrl` itself has no real value set
 * anywhere yet (see `configUrl` below), so nothing untrusted is actually fetched today.
 *
 * Shape mirrors `spec/tenant-config-schema.md` v1 — every field mapped here is a field
 * that document defines; nothing invented. `logo_sha256`/full-`branding` fields are
 * carried even though no caller reads them yet (`d.i.zi`/`d.i.zo`, both still open,
 * are the leaves that will), same "model the whole schema this converter's siblings
 * already commit to" posture `ZealotEntry.kt` uses for `ZealotVersion`'s unused fields.
 */
data class TenantBranding(
    @SerializedName("display_name")      val displayName: String = "Vyxel Apps",
    @SerializedName("primary_color_hex") val primaryColorHex: String = "#D0BCFF",
    @SerializedName("logo_url")          val logoUrl: String = "",
    @SerializedName("logo_sha256")       val logoSha256: String = ""
)

/**
 * The exact existing hardcoded string `MetadataManager` used before this leaf. Seeding
 * the default/seed tenant record with this precise value — not a placeholder, not a
 * different CDN — is what makes "default install behavior does not change, it just
 * moves from a compile-time constant to tenant-zero's config record" (`HANDOVER.md`,
 * `1.c.ii.zi`) concretely true rather than merely asserted.
 */
const val DEFAULT_CDN_BASE = "https://nikhilkain.github.io/appstore-metadata"

data class TenantConfigData(
    @SerializedName("schema_version")         val schemaVersion: Int = 1,
    @SerializedName("tenant_id")              val tenantId: String = "default",
    @SerializedName("generated_at")           val generatedAt: String = "",
    @SerializedName("sequence")               val sequence: Int = 0,
    @SerializedName("expires_at")             val expiresAt: String = "",
    val branding                              : TenantBranding = TenantBranding(),
    @SerializedName("cdn_base")               val cdnBase: String = DEFAULT_CDN_BASE,
    @SerializedName("catalog_index_base_url") val catalogIndexBaseUrl: String = "",
    val domains                               : List<String> = emptyList(),
    @SerializedName("is_default_tenant")      val isDefaultTenant: Boolean = true
)

object TenantConfig {

    /**
     * Per-tenant fetch endpoint. Mirrors `ZealotClient.baseUrl`'s own posture exactly:
     * blank/unset means "no live source configured yet," not an error — there is no
     * `TenantConfig` publisher anywhere in this program today (`spec/tenant-config-schema.md`
     * "Open decisions" #2 — Zealot Task 37b/37c, still open), so this stays blank in
     * practice until that lands. `current` must therefore always resolve to *something*
     * valid on its own — see below — never `null`, since `MetadataManager.init` (this
     * same leaf) needs an always-available `cdnBase` on the very first frame.
     */
    @Volatile var configUrl: String = ""

    @Volatile private var _current: TenantConfigData = TenantConfigData()
    val current: TenantConfigData get() = _current

    private const val PREFS      = "tenant_config_cache"
    private const val KEY_JSON   = "last_good_config"
    private const val KEY_TS     = "last_good_ts"

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

    /**
     * Unconditional fetch attempt. On success, `current` and the on-disk cache both
     * advance together. On ANY failure (unset `configUrl`, network error, non-2xx,
     * unparsable JSON, unsupported `schema_version`) `current` is left exactly as
     * `init()`'s synchronous load already set it — the last-known-good record, or the
     * compiled-in default tenant if nothing has ever been cached — same "every
     * rejection reason collapses to the same outcome" posture `ZealotClient.resolveVerifiedIndex`
     * already uses for its own fetch/verify step.
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val url = configUrl.trim()
        if (url.isEmpty()) return@withContext
        try {
            val resp = http.newCall(Request.Builder().url(url).build()).execute()
            resp.use { r ->
                if (!r.isSuccessful) return@withContext
                val json = r.body?.string() ?: return@withContext
                val parsed = try {
                    gson.fromJson(json, TenantConfigData::class.java)
                } catch (_: Exception) { null } ?: return@withContext
                if (parsed.schemaVersion != 1) return@withContext
                _current = parsed
                prefs(context).edit()
                    .putString(KEY_JSON, json)
                    .putLong(KEY_TS, System.currentTimeMillis())
                    .apply()
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
