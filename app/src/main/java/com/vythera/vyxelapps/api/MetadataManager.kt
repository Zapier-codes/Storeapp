package com.vythera.vyxelapps.api

import android.content.Context

// 1.c.ii.zi: `cdnBase` now reads from `TenantConfig.current` instead of a compile-time
// literal. `TenantConfig.init(ctx)` must run before this (see `AppViewModel.init`,
// `AppData.kt`) so `current` already reflects this device's cached tenant record --
// its own synchronous disk load, not a network wait -- by the time this constructs.
// The default/seed tenant's `cdnBase` is `DEFAULT_CDN_BASE`, the exact string this
// object used to hardcode, so a device with no tenant config ever fetched (the only
// case that exists today -- see `TenantConfig.configUrl`'s own doc comment) gets
// byte-identical behavior to before this leaf.
object MetadataManager {

    private var client: MetadataClient? = null

    fun init(context: Context): MetadataClient {
        if (client == null) {
            client = MetadataClient(
                context = context.applicationContext,
                cdnBase = TenantConfig.current.cdnBase
            )
        }
        return client!!
    }

    fun get(): MetadataClient = client
        ?: throw IllegalStateException("MetadataManager.init() must be called before get()")
}
