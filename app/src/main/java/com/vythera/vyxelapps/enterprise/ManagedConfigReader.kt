package com.vythera.vyxelapps.enterprise

import android.content.Context
import android.content.RestrictionsManager

/**
 * S-P3: reads the app restrictions a device policy controller set, through Android's own
 * `RestrictionsManager`. This is the only Android-aware part of the managed-config support —
 * all the parsing and folding lives in [ManagedConfigRules] and [withManaged].
 *
 * A device with no DPC (almost every device) gets [ManagedConfig.NONE] and the client behaves
 * exactly as before. Any failure reading the restrictions is treated the same way: an install
 * is never blocked or locked by a broken config.
 */
class ManagedConfigReader(private val context: Context) {

    fun read(): ManagedConfig {
        val manager = context.getSystemService(Context.RESTRICTIONS_SERVICE) as? RestrictionsManager
            ?: return ManagedConfig.NONE

        val bundle = runCatching { manager.applicationRestrictions }.getOrNull()
            ?: return ManagedConfig.NONE

        val entries = bundle.keySet().associateWith { bundle.get(it) }
        return ManagedConfigRules.fromBundle(entries)
    }
}
