package com.vythera.vyxelapps

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle

/**
 * Handles links from D-Store's web pages: `vyxelapps://uninstall/<package>` (Chrome sends it as
 * `intent://uninstall/<package>#Intent;scheme=vyxelapps;package=...;S.browser_fallback_url=...;end`).
 *
 * It has no screen. For a well-formed package that is installed on this device it opens Android's own
 * uninstall confirmation, so the person still has to tap OK: an app can never remove another app silently
 * (needs REQUEST_DELETE_PACKAGES, already declared, and the system dialog). Anything else is ignored.
 */
class LinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent?.data)
        finish()
    }

    private fun handle(data: Uri?) {
        if (data == null || data.scheme != "vyxelapps" || data.host != "uninstall") return
        val pkg = data.pathSegments.firstOrNull() ?: return
        if (!PACKAGE_NAME.matches(pkg) || pkg.length > 255) return
        try {
            packageManager.getPackageInfo(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return // not installed: nothing to remove
        }
        try {
            startActivity(
                Intent(Intent.ACTION_DELETE).apply {
                    this.data = Uri.parse("package:$pkg")
                    putExtra(Intent.EXTRA_RETURN_RESULT, false)
                }
            )
        } catch (_: Exception) {
            // no uninstaller available: the person can still remove it in Settings
        }
    }

    private companion object {
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
