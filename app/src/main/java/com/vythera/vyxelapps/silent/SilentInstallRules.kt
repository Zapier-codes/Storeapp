package com.vythera.vyxelapps.silent

/**
 * Z-P26: which silent-install backend the store may use, and in what order.
 *
 * This is the whole decision, kept as a pure function so it runs as a plain JVM test — nothing here
 * touches Android. The caller probes the device (is Shizuku running and granted? is Dhizuku running
 * and granted? does `su` answer?) and hands the answers in.
 *
 * Rules, in order:
 *  1. If the person has turned silent installs off, nothing is used — the store falls back to
 *     Android's own installer, which asks every time. This is the default: silence is opt-in.
 *  2. If the person pinned one backend, only that one is used, and only if it is ready. A pin that
 *     is not ready does not silently fall through to a different privilege the person did not pick.
 *  3. Otherwise the ready backends are tried most-authority-last: Shizuku, then Dhizuku, then Root.
 *     Shizuku first because it needs no device-owner setup and no root; Dhizuku before Root because a
 *     managed phone is a deliberate state whereas root is the bluntest tool of the three.
 *
 * "Ready" is [SilentInstallStatus.ready]: running and permitted. Anything else (not installed, not
 * running, permission refused) means the backend is not used, and the caller falls back to Android's
 * installer rather than failing the install.
 */
object SilentInstallRules {

    /** The one switch. Off by default: the person turns silent installs on in Settings. */
    const val ENABLED_BY_DEFAULT = false

    /** The order the ready backends are tried in when the person has not pinned one. */
    val DEFAULT_ORDER: List<SilentInstallBackend> =
        listOf(SilentInstallBackend.Shizuku, SilentInstallBackend.Dhizuku, SilentInstallBackend.Root)

    /**
     * The backends to try, best first, or an empty list to mean "use Android's installer".
     *
     * @param enabled the person's switch (defaults to [ENABLED_BY_DEFAULT]).
     * @param pinned when set, the only backend the person allowed; null means any ready one.
     * @param status how each backend answered a probe this session.
     */
    fun plan(
        enabled: Boolean,
        pinned: SilentInstallBackend?,
        status: (SilentInstallBackend) -> SilentInstallStatus,
    ): List<SilentInstallBackend> {
        if (!enabled) return emptyList()
        if (pinned != null) {
            return if (status(pinned).ready) listOf(pinned) else emptyList()
        }
        return DEFAULT_ORDER.filter { status(it).ready }
    }
}
