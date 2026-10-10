package com.vythera.vyxelapps.silent

/**
 * Z-P26: the three ways this store can install an APK without Android's confirmation screen.
 *
 * Every one of them ends in the same place — a `pm install` running as a privileged user — but they
 * differ in whose privilege it is, and every device offers a different subset. The store never
 * bundles or ships any of them; it detects the ones the person already runs (or sets up) and drives
 * whichever answers.
 *
 * - [Shizuku] borrows the ADB shell through the Shizuku app (or the Sui Magisk module). Works on an
 *   unrooted phone once the person starts the service; the widest reach.
 * - [Dhizuku] borrows a device owner's authority through the Dhizuku app. Needs the phone managed
 *   (device owner set up), which is exactly the enterprise case.
 * - [Root] runs `su` directly. Only on a rooted phone, and only when the user grants the request.
 */
enum class SilentInstallBackend(
    val label: String,
    /** The authority the backend draws on, said in the app so the person knows what they are enabling. */
    val authority: String,
) {
    Shizuku("Shizuku", "ADB shell"),
    Dhizuku("Dhizuku", "Device owner"),
    Root("Root", "superuser"),
}
