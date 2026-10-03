package com.vythera.vyxelapps.api

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Client for D-Store's `GET /api/catalog` — leaf `7.b.iii.zo` (second of the four leaves D-Store's
 * `7.b.i.zo` splits into; `7.b.iii.zi` is [DStoreApp] and its converter, `7.b.iv.zi`/`7.b.iv.zo`
 * the browse and search wiring). **Not wired into `AppViewModel` or any screen yet.**
 *
 * **Unsigned, and this file says so on purpose.** Unlike `ZealotClient`/`FederatedCatalogClient`,
 * there is no signature, no pinned key, no rollback state and no last-good cache here: the API has
 * none, and none is faked. A page is whatever the server answered over HTTPS, nothing more. Nothing
 * in it may be treated as a checked claim; [DStoreApp.toUnifiedRepo] already refuses to set a
 * checksum or fingerprint for that reason.
 *
 * Contract (version 1; D-Store `lib/catalog-api.ts`): query `order` (`top`|`new`), `type`
 * (`app`|`game`), `category` (needs `type`), `q` (search; `top` order only, no `category`),
 * `limit` (1 to 100) and `cursor` (the previous page's `next_cursor`, opaque); answer
 * `{ "apps": [...], "next_cursor": string | null }`. The client refuses the combinations the
 * server would answer `400` to instead of sending them.
 *
 * Failure posture, same as the other clients: **[page] returns `null` for every failure** (blank
 * or non-`https` [baseUrl], an argument the server would reject, a network error, a timeout, any
 * status but `200`, a body over [MAX_BODY_BYTES], a body that is not the documented envelope). The
 * caller has one shape to handle. The only thing it lets escape is coroutine cancellation, which
 * must propagate. It never logs a query, a cursor or a body.
 *
 * Strictness: the envelope is all-or-nothing (a non-object body, a missing or non-array `apps`, more
 * than [PAGE_MAX] apps, or a `next_cursor` that is not `null` or a cursor-shaped string fails the
 * page). An individual app is skipped and counted in [DStoreCatalogPage.skipped] when its `slug` is
 * missing or blank, a field present has the wrong type, or its `slug` repeats an earlier one on the
 * page. A skipped app never reaches the converter, so a blank slug cannot give several apps the same id.
 *
 * Requests carry the same `User-Agent` as the other clients, no cookies (OkHttp's default jar is
 * none, and none is set), and do not follow an `https` to `http` redirect.
 */

/** One parsed page. [skipped] counts apps dropped for being malformed or repeated. [nextCursor] is `null` on the last page. */
data class DStoreCatalogPage(
    val apps: List<DStoreApp>,
    val nextCursor: String?,
    val skipped: Int
)

enum class DStoreOrder(val wire: String) { TOP("top"), NEW("new") }

enum class DStoreType(val wire: String) { APP("app"), GAME("game") }

object DStoreCatalogClient {
    /** Blank means "not configured yet", not an error: [page] answers `null` without a request. Same posture as `ZealotClient.baseUrl`. */
    @Volatile var baseUrl: String = ""

    const val PAGE_MAX = 100
    const val DEFAULT_LIMIT = 50
    /** Most bytes of a response body read; a body over it fails the page. 100 apps are about 100 KB, so this is generous, not tight. */
    const val MAX_BODY_BYTES = 2L * 1024 * 1024
    /** The server cuts a search needle at 100 characters (`CATALOG_SEARCH_MAX_CHARS`); cutting here first keeps the URL honest. */
    const val SEARCH_MAX_CHARS = 100
    /** The server rejects a longer cursor (`MAX_CURSOR_LENGTH`). */
    const val CURSOR_MAX_CHARS = 600

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("User-Agent", "VyxelApps/1.0").build())
        }.build()

    /**
     * One page of the catalog, or `null` on any failure (see the file header). `query` makes it a
     * search: `order` must then be [DStoreOrder.TOP] and `category` null, as the server requires.
     * A blank `query` answers an empty page with no request, which is what the server would answer.
     * `limit` is clamped to 1..[PAGE_MAX].
     */
    suspend fun page(
        order: DStoreOrder = DStoreOrder.TOP,
        type: DStoreType? = null,
        category: String? = null,
        query: String? = null,
        cursor: String? = null,
        limit: Int = DEFAULT_LIMIT
    ): DStoreCatalogPage? = withContext(Dispatchers.IO) {
        try {
            if (query != null && query.isBlank()) {
                return@withContext DStoreCatalogPage(emptyList(), null, 0)
            }
            val url = buildCatalogUrl(baseUrl, order, type, category, query, cursor, limit)
                ?: return@withContext null
            val body = fetchBody(url) ?: return@withContext null
            parseCatalogPage(body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Blocking; call off the main thread. `null` unless the answer is `200` and no larger than [MAX_BODY_BYTES]. */
    private fun fetchBody(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (response.code != 200) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BODY_BYTES) return null
            val source = body.source()
            source.request(MAX_BODY_BYTES + 1)
            if (source.buffer.size > MAX_BODY_BYTES) return null
            return source.buffer.readUtf8()
        }
    }

    private val CURSOR_SHAPE = Regex("^[A-Za-z0-9_-]+$")

    /**
     * The request URL, or `null` when [base] is blank or not `https`, or an argument is one the
     * server would answer `400` to. Pure; no network. `internal` so the unit test can reach it.
     */
    internal fun buildCatalogUrl(
        base: String,
        order: DStoreOrder,
        type: DStoreType?,
        category: String?,
        query: String?,
        cursor: String?,
        limit: Int
    ): String? {
        val trimmedBase = base.trim().trimEnd('/')
        if (trimmedBase.isEmpty()) return null
        val parsed = "$trimmedBase/api/catalog".toHttpUrlOrNull() ?: return null
        if (!parsed.isHttps) return null

        if (category != null && (type == null || category.isBlank())) return null
        val needle = query?.let { cutSearchNeedle(it) }
        if (needle != null && (needle.isEmpty() || order != DStoreOrder.TOP || category != null)) return null
        if (cursor != null && (cursor.length > CURSOR_MAX_CHARS || !CURSOR_SHAPE.matches(cursor))) return null

        val builder = parsed.newBuilder()
            .addQueryParameter("order", order.wire)
            .addQueryParameter("limit", limit.coerceIn(1, PAGE_MAX).toString())
        if (type != null) builder.addQueryParameter("type", type.wire)
        if (category != null) builder.addQueryParameter("category", category)
        if (needle != null) builder.addQueryParameter("q", needle)
        if (cursor != null) builder.addQueryParameter("cursor", cursor)
        return builder.build().toString()
    }

    /** Trims, then cuts at [SEARCH_MAX_CHARS] code points (not UTF-16 units), then trims again. */
    internal fun cutSearchNeedle(query: String): String {
        val s = query.trim()
        val end = if (s.codePointCount(0, s.length) > SEARCH_MAX_CHARS) s.offsetByCodePoints(0, SEARCH_MAX_CHARS) else s.length
        return s.substring(0, end).trim()
    }

    /** The envelope and its apps, or `null` when the body is not the documented envelope. Pure. `internal` for the unit test. */
    internal fun parseCatalogPage(body: String): DStoreCatalogPage? {
        val root = try { JsonParser.parseString(body) } catch (_: Exception) { return null }
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject

        val appsElement = obj.get("apps")
        if (appsElement == null || !appsElement.isJsonArray) return null
        val array = appsElement.asJsonArray
        if (array.size() > PAGE_MAX) return null

        val cursorElement = obj.get("next_cursor")
        val nextCursor: String? = when {
            cursorElement == null || cursorElement.isJsonNull -> null
            cursorElement.isJsonPrimitive && cursorElement.asJsonPrimitive.isString -> {
                val c = cursorElement.asString
                if (c.isEmpty() || c.length > CURSOR_MAX_CHARS || !CURSOR_SHAPE.matches(c)) return null
                c
            }
            else -> return null
        }

        val apps = ArrayList<DStoreApp>(array.size())
        val seen = HashSet<String>()
        var skipped = 0
        for (element in array) {
            val app = parseApp(element)
            if (app == null || !seen.add(app.slug)) skipped++ else apps.add(app)
        }
        return DStoreCatalogPage(apps, nextCursor, skipped)
    }

    // ── One app ─────────────────────────────────────────────────────────────────

    /** A field that is absent or `null` is `Ok(null)`; one of the wrong type is [Bad]. */
    private object Bad

    private fun str(o: JsonObject, name: String): Any? {
        val e = o.get(name)
        if (e == null || e.isJsonNull) return null
        return if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else Bad
    }

    private fun parseApp(element: JsonElement): DStoreApp? {
        if (!element.isJsonObject) return null
        val o = element.asJsonObject

        val slug = str(o, "slug")
        if (slug !is String || slug.isBlank() || slug.length > 200) return null

        val packageName = str(o, "package_name")
        val origin      = str(o, "origin")
        val name        = str(o, "name")
        val summary     = str(o, "summary")
        val icon        = str(o, "icon")
        val version     = str(o, "version")
        val appType     = str(o, "app_type")
        val category    = str(o, "category")
        val license     = str(o, "license")
        val downloadUrl = str(o, "download_url")
        val updatedAt   = str(o, "updated_at")
        if (listOf(packageName, origin, name, summary, icon, version, appType, category, license, downloadUrl, updatedAt).any { it === Bad }) return null

        var developerName = ""
        val developer = o.get("developer")
        if (developer != null && !developer.isJsonNull) {
            if (!developer.isJsonObject) return null
            val n = str(developer.asJsonObject, "name")
            if (n === Bad) return null
            developerName = (n as String?) ?: ""
        }

        var sizeMb = 0.0
        val size = o.get("size_mb")
        if (size != null && !size.isJsonNull) {
            if (!size.isJsonPrimitive || !size.asJsonPrimitive.isNumber) return null
            sizeMb = size.asDouble
            if (sizeMb.isNaN() || sizeMb.isInfinite() || sizeMb < 0) return null
        }

        var reported: Long? = null
        val downloads = o.get("reported_downloads")
        if (downloads != null && !downloads.isJsonNull) {
            if (!downloads.isJsonPrimitive || !downloads.asJsonPrimitive.isNumber) return null
            val d = downloads.asDouble
            if (d.isNaN() || d.isInfinite() || d < 0 || d != Math.floor(d) || d > MAX_EXACT_COUNT) return null
            reported = d.toLong()
        }

        return DStoreApp(
            slug               = slug,
            package_name       = (packageName as String?) ?: "",
            origin             = (origin as String?) ?: "",
            name               = (name as String?) ?: "",
            summary            = (summary as String?) ?: "",
            icon               = icon as String?,
            version            = (version as String?) ?: "",
            app_type           = (appType as String?) ?: "",
            category           = (category as String?) ?: "",
            developer_name     = developerName,
            license            = (license as String?) ?: "",
            size_mb            = sizeMb,
            download_url       = downloadUrl as String?,
            reported_downloads = reported,
            updated_at         = (updatedAt as String?) ?: ""
        )
    }

    /** 2^53, the largest integer a JSON double holds exactly; a bigger "count" is not one. */
    private const val MAX_EXACT_COUNT = 9_007_199_254_740_992.0
}
