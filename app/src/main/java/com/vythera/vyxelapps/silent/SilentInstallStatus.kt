package com.vythera.vyxelapps.silent

/**
 * Z-P26: what a backend answered when asked "are you usable right now?".
 *
 * Kept as four plain cases so the Settings screen can say exactly what is missing — a person who set
 * Shizuku up but never granted this app will see [Running] and a Grant button, not a bare "unavailable"
 * that sends them hunting.
 */
enum class SilentInstallStatus {
    /** Not present on this device at all (no service running, no `su`). */
    NotInstalled,

    /** Present and running, but this app has not been granted its permission yet. */
    Running,

    /** Running and granted: the store may use it. */
    Ready,

    /** The probe itself failed (a timeout, a crash in the other process). Treated as not usable. */
    Unknown;

    /** Only [Ready] means the backend may be used. */
    val ready: Boolean get() = this == Ready
}
