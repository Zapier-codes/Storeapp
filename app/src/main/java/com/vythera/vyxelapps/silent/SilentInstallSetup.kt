package com.vythera.vyxelapps.silent

/**
 * Z-P26: how this store sets a silent-install backend up, by hand, at runtime.
 *
 * The decision on record (Zealot `docs/UNOFFICIAL-ROUTES.md`) is that the client drives each backend
 * itself — Shizuku's own service, Dhizuku's owner process, the device's `su` — and does NOT install or
 * bundle a second app to do it. What the store cannot do is hold a privilege it was never given, so
 * "setup" here means: help the person put the backend in the one state where it works, then probe
 * again. Nothing is downloaded and installed behind their back.
 *
 * [startIntent] is the package a backend exposes for that one step, when it has one. It is opened with
 * an ordinary `ACTION_VIEW`, which the backend's own app handles to walk the person through its setup.
 * A backend with no such package ([Root]) returns null and the caller says "run `su`" in prose.
 */
enum class SilentInstallSetup(val packageName: String?, val startAction: String) {
    /** Shizuku's launcher activity; its app guides "start via ADB / wireless debugging". */
    Shizuku("moe.shizuku.privileged.api", "moe.shizuku.manager.intent.action.MAIN"),

    /** Dhizuku's launcher activity; its app guides granting this app Device-Owner delegation. */
    Dhizuku("com.rosan.dhizuku", "com.rosan.dhizuku.intent.action.MAIN"),

    /** No app to open: the person runs `su` in a terminal, or flashes a root manager. */
    Root(null, "");

    companion object {
        fun of(backend: SilentInstallBackend): SilentInstallSetup = when (backend) {
            SilentInstallBackend.Shizuku -> Shizuku
            SilentInstallBackend.Dhizuku -> Dhizuku
            SilentInstallBackend.Root -> Root
        }
    }
}
