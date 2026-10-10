package com.vythera.vyxelapps.expressive.data.source

import com.vythera.vyxelapps.api.DStoreCatalogClient
import com.vythera.vyxelapps.api.DStoreOrder
import com.vythera.vyxelapps.expressive.data.model.AppItem
import com.vythera.vyxelapps.expressive.data.model.SourceId
import com.vythera.vyxelapps.expressive.data.toAppItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * D-Store's public catalog as an Expressive source — leaf `j.vii.c`.
 *
 * Classic already browses this catalogue (`DStoreCatalogClient`, its `7.b.iv.zi` view);
 * this gives the Expressive shell the same second first-party store.
 *
 * **Browse-only, never installable.** D-Store's `GET /api/catalog` is unsigned — no
 * signature, no checksum, no fingerprint — so nothing a row says can be treated as a
 * checked claim (the operator's decision 5a/5b). The mapping ([toAppItem]) therefore
 * leaves `downloadUrl` null; the install button reads Unavailable and the card opens to
 * be read, not installed. That is a deliberate limit, not a missing field.
 */
class DStoreSource : AppSource {

    override val id: SourceId = SourceId.DStore

    /** The `top` order, the same listing Classic's browse view leads with. */
    override suspend fun featured(): List<AppItem> {
        val page = page(order = DStoreOrder.TOP)
        return page?.apps?.map { it.toAppItem() }.orEmpty()
    }

    override suspend fun search(query: String): List<AppItem> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val page = page(order = DStoreOrder.TOP, query = q)
        return page?.apps?.map { it.toAppItem() }.orEmpty()
    }

    private suspend fun page(order: DStoreOrder, query: String? = null) = withContext(Dispatchers.IO) {
        runCatching {
            DStoreCatalogClient.page(
                order = order,
                query = query,
                limit = DStoreCatalogClient.PAGE_MAX,
            )
        }.getOrNull()
    }
}
