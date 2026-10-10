package com.vythera.vyxelapps.silent

import android.content.Context
import android.util.Log
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import java.io.File

/**
 * Z-P26: the one place this app touches the Dhizuku client API.
 *
 * Isolated on purpose: Dhizuku's classes throw `NoClassDefFoundError` / `RuntimeException` on a device
 * where the Dhizuku service is absent, and `LinkageError` is an `Error`, not an `Exception` — a plain
 * try/catch around `Exception` would let it through and crash the store. Every method here catches
 * `Throwable` and turns "Dhizuku is not here" into a clean null/false, so the rest of the store can ask
 * unconditionally and get an honest answer.
 *
 * The store does not ship Dhizuku and does not install it. When Dhizuku is present, `newProcess` runs a
 * command as the device owner it is sharing; the person grants this app that delegation through
 * Dhizuku's own UI ([requestPermission]).
 */
object DhizukuBridge {

    private const val TAG = "VyxelDhizuku"

    /** Binds to the Dhizuku service. False means it is not installed or not running. */
    fun init(context: Context): Boolean = try {
        Dhizuku.init(context) && Dhizuku.getVersionCode() > 0
    } catch (t: Throwable) {
        Log.d(TAG, "Dhizuku.init failed: ${t.message}")
        false
    }

    fun isPermissionGranted(): Boolean = try {
        Dhizuku.isPermissionGranted()
    } catch (t: Throwable) {
        Log.d(TAG, "Dhizuku.isPermissionGranted failed: ${t.message}")
        false
    }

    /** Asks Dhizuku to let this app use it; the decision is shown by Dhizuku's own activity. */
    fun requestPermission(context: Context) {
        try {
            if (!init(context)) return
            Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(requestCode: Int) {
                    Log.d(TAG, "Dhizuku permission result: $requestCode")
                }
            })
        } catch (t: Throwable) {
            Log.d(TAG, "Dhizuku.requestPermission failed: ${t.message}")
        }
    }

    /** The device owner's own package, or null. Shown in Settings so the person sees what is behind it. */
    fun ownerPackageName(): String? = try {
        if (!isPermissionGranted()) null else Dhizuku.getOwnerPackageName()
    } catch (t: Throwable) {
        null
    }

    /** Starts [argv] as the device owner. Null when Dhizuku cannot run it. */
    fun newProcess(context: Context, argv: List<String>): Process? = try {
        if (!init(context) || !isPermissionGranted()) null
        else Dhizuku.newProcess(argv.toTypedArray(), null, null as File?)
    } catch (t: Throwable) {
        Log.d(TAG, "Dhizuku.newProcess failed: ${t.message}")
        null
    }
}
