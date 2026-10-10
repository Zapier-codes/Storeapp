package com.vythera.vyxelapps.api

/**
 * Task 47i (Zealot's handover; Storeapp Track i). Android 14 lets the first installer of an app claim its UPDATE
 * OWNERSHIP: from then on every other installer needs the person's confirmation to update that app, even one that
 * holds `INSTALL_PACKAGES`. This store asks for it on the installs it performs, so an app installed here is
 * updated here (the Play-like path, Track h) and not by a browser, a file manager or another store.
 *
 * What Android's documentation says (read 2026-10-10): `PackageInstaller.SessionParams.setRequestUpdateOwnership`
 * is API 34; the installer must hold `ENFORCE_UPDATE_OWNERSHIP`; it only takes effect on the INITIAL install and is
 * a no-op on an update, so it is safe to ask on every session. Nothing here touches Android, so the rule runs as a
 * plain unit test; `expressive/install/ApkInstaller.kt` asks it before it commits a session.
 *
 * Consequences, said plainly: apps already installed here before this change keep no owner (it cannot be set on an
 * update). Zealot's injected updater stays silent for any app another package owns (`UpdaterRules.shouldStayQuiet`).
 * The store updating ITSELF (`SelfInstaller`) is an update, so it does not ask. Whether a normal app is granted
 * `ENFORCE_UPDATE_OWNERSHIP` was NOT confirmed (the protection level is not in the pages read); if Android refuses,
 * the session proceeds without ownership and nothing else changes (see `ApkInstaller`).
 */
object UpdateOwnershipRules {
    /** The first Android that has `setRequestUpdateOwnership` (UPSIDE_DOWN_CAKE). */
    const val MIN_SDK = 34

    /** The one switch. Off: no session asks, nothing else changes. */
    const val REQUEST_OWNERSHIP = true

    /** Ask for ownership on this session? Only on Android 14 or newer, and only when the switch is on. */
    fun shouldRequest(sdkInt: Int, enabled: Boolean = REQUEST_OWNERSHIP): Boolean = enabled && sdkInt >= MIN_SDK
}
