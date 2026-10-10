package com.vythera.vyxelapps.enterprise

import com.vythera.vyxelapps.expressive.data.Settings
import com.vythera.vyxelapps.expressive.data.model.SourceId

/**
 * S-P3 (Play parity, enterprise): the client's managed configuration.
 *
 * A device policy controller (Android Enterprise, Headwind MDM, any DPC) can hand the
 * client a set of **app restrictions** through `RestrictionsManager`. The client reads
 * them here and folds them into its own settings. The intent is the Play managed-config
 * shape: an organisation sets policy centrally, the person still owns everything the
 * organisation did not set.
 *
 * Two rules are the whole point:
 *  1. A key the organisation did not set leaves the person's own choice untouched.
 *  2. A package the organisation **hides** cannot be un-hidden from the app — but nothing
 *     here blocks a download; it only removes a listing from what the store shows.
 *
 * This file is deliberately pure (no `android.*`) so the parsing and the folding can be
 * unit-tested off-device; [ManagedConfigReader] is the thin Android reader.
 */
data class ManagedConfig(
    /** `null` means "not set" — the person chooses; a set (possibly empty) restricts. */
    val enabledSources: Set<SourceId>? = null,
    /** `null` means "not set". */
    val showDesktopSources: Boolean? = null,
    /** Enforced: always hidden, and cannot be restored in-app. */
    val hiddenPackages: Set<String> = emptySet(),
    /** The keys the organisation actually set, so the UI can say "managed" and name them. */
    val managedKeys: Set<String> = emptySet(),
) {
    /** True when a DPC is steering this install at all. */
    val isManaged: Boolean get() = managedKeys.isNotEmpty()

    /** Keys the person may still change freely: every known key the org did not set. */
    val freelyEditableKeys: Set<String>
        get() = ManagedConfigRules.KNOWN_KEYS - managedKeys

    companion object {
        val NONE = ManagedConfig()
    }
}

object ManagedConfigRules {
    const val KEY_ENABLED_SOURCES = "enabled_sources"
    const val KEY_SHOW_DESKTOP_SOURCES = "show_desktop_sources"
    const val KEY_HIDDEN_PACKAGES = "hidden_packages"

    val KNOWN_KEYS = setOf(KEY_ENABLED_SOURCES, KEY_SHOW_DESKTOP_SOURCES, KEY_HIDDEN_PACKAGES)

    /** Shown in the UI when the org hides packages — the one thing the person cannot undo. */
    const val HIDDEN_NOTE = "Hidden by your organisation"

    /**
     * Build a config from the raw restriction map a DPC supplied. Unset keys stay unset;
     * a key whose value cannot be understood is treated as unset rather than guessed, so a
     * typo in a DPC's config never silently locks a person out.
     */
    fun fromBundle(entries: Map<String, Any?>): ManagedConfig {
        val managed = mutableSetOf<String>()

        val sources = entries[KEY_ENABLED_SOURCES]
            ?.let { parseSources(it.toString()) }
            ?.also { managed.add(KEY_ENABLED_SOURCES) }

        val desktop = entries[KEY_SHOW_DESKTOP_SOURCES]
            ?.let { parseBoolean(it) }
            ?.also { managed.add(KEY_SHOW_DESKTOP_SOURCES) }

        val hidden = entries[KEY_HIDDEN_PACKAGES]
            ?.let { parsePackages(it.toString()) }
            ?.also { if (it.isNotEmpty()) managed.add(KEY_HIDDEN_PACKAGES) }

        return ManagedConfig(
            enabledSources = sources,
            showDesktopSources = desktop,
            hiddenPackages = hidden ?: emptySet(),
            managedKeys = managed,
        )
    }

    /**
     * Parse the source list. Blank means "not set" (`null`); the literal `none` (or `off`)
     * means "no sources" (empty set). Otherwise the recognised names win and unknown names
     * are ignored; if *nothing* is recognised the key is treated as unset, so a misspelt
     * list does not disable the whole store.
     */
    fun parseSources(raw: String?): Set<SourceId>? {
        val tokens = tokenize(raw)
        if (tokens.isEmpty()) return null
        if (tokens.any { it == "none" || it == "off" }) return emptySet()

        val known = tokens.mapNotNull { token ->
            SourceId.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
        }.toSet()

        return known.ifEmpty { null }
    }

    /** Comma/space/semicolon separated package names, trimmed and de-blanked. */
    fun parsePackages(raw: String?): Set<String> =
        raw.orEmpty()
            .split(',', ' ', ';', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    /** `true`/`1`/`yes`/`on` and their opposites; anything else is `null` (unset). */
    fun parseBoolean(raw: Any?): Boolean? = when (val value = raw?.toString()?.trim()?.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    private fun tokenize(raw: String?): List<String> =
        raw.orEmpty()
            .split(',', ' ', ';', '\n', '\t')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
}

/**
 * Fold a [ManagedConfig] onto the person's own [Settings]. A key the org set wins; a key it
 * did not set leaves the person's value alone. Organisation-hidden packages are added to the
 * person's own hidden set (union) — the app offers no path that removes them.
 */
fun Settings.withManaged(config: ManagedConfig): Settings = copy(
    enabledSources = config.enabledSources ?: enabledSources,
    showDesktopSources = config.showDesktopSources ?: showDesktopSources,
    hiddenPackages = hiddenPackages + config.hiddenPackages,
)
