package com.vythera.vyxelapps.api

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.vythera.vyxelapps.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Leaf `e.i` -- FCM device registration + token-refresh handling. Client half of Zealot Task 37d-iv
 * (Novu's push channel is a server-side concern; this object only gets a device token to Zealot).
 *
 * **Blank means "not configured yet", not an error** -- same posture as `ZealotClient.baseUrl`,
 * `FederatedCatalogClient.baseUrl` and `TenantConfig.configUrl`. Two independent gates, both
 * must pass or [init] is a silent no-op and the app behaves exactly as before this leaf
 * (local `UpdateCheckWorker` notifications only):
 *   1. Firebase options (`BuildConfig.FCM_*`, from Gradle properties `fcmProjectId`/`fcmAppId`/
 *      `fcmApiKey`/`fcmSenderId`) -- initialised programmatically via [FirebaseOptions] rather
 *      than the `google-services` plugin, because that plugin needs a committed
 *      `google-services.json` for a Firebase project that does not exist yet, and a
 *      flavor-per-tenant build (`1.c.i.zo`) will need a different project's values per flavor.
 *   2. [registrationUrl] (`BuildConfig.PUSH_REGISTRATION_URL`) -- the Zealot endpoint that stores
 *      a device token against a tenant. Must be https (a token is a delivery credential).
 *
 * Token lifecycle: [onNewToken] persists the token first, then tries to register it; the
 * "registered" marker is only written after a 2xx, so a failed POST is retried on the next app
 * launch ([init]) or next refresh -- never assumed delivered. No client secret is involved: the
 * request carries only `tenant_id` + the FCM token. Server-side abuse handling (rate limits,
 * rejecting unknown tenants) is Zealot's job, tracked in its own repo.
 */
object PushRegistrar {

    @Volatile var registrationUrl: String = BuildConfig.PUSH_REGISTRATION_URL

    private const val TAG = "PushRegistrar"
    private const val PREFS = "push_state"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_REGISTERED_TOKEN = "registered_token"

    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun firebaseOptions(): FirebaseOptions? {
        if (BuildConfig.FCM_PROJECT_ID.isBlank() || BuildConfig.FCM_APP_ID.isBlank() ||
            BuildConfig.FCM_API_KEY.isBlank()
        ) return null
        return FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setApplicationId(BuildConfig.FCM_APP_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID.ifBlank { null })
            .build()
    }

    /** Call from `Application.onCreate` (must run before any `FirebaseMessagingService` callback). */
    fun init(app: Application) {
        val options = firebaseOptions() ?: return
        try {
            if (FirebaseApp.getApps(app).isEmpty()) FirebaseApp.initializeApp(app, options)
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> onNewToken(app, token) }
                .addOnFailureListener { Log.w(TAG, "FCM token fetch failed", it) }
        } catch (e: Exception) {
            // Bad/mismatched Firebase config must never crash app start -- push simply stays off.
            Log.w(TAG, "Push init skipped", e)
        }
    }

    /** Also the entry point for `AppstoreMessagingService.onNewToken` (token rotation). */
    fun onNewToken(ctx: Context, token: String) {
        if (token.isBlank()) return
        val app = ctx.applicationContext
        prefs(app).edit().putString(KEY_TOKEN, token).apply()
        scope.launch { register(app) }
    }

    /** POSTs the persisted token unless that exact token is already acknowledged. */
    private fun register(ctx: Context) {
        val url = registrationUrl.trim()
        if (!url.startsWith("https://")) return
        val p = prefs(ctx)
        val token = p.getString(KEY_TOKEN, null) ?: return
        if (p.getString(KEY_REGISTERED_TOKEN, null) == token) return
        val body = gson.toJson(
            mapOf(
                "tenant_id" to TenantConfig.current.tenantId,
                "token" to token,
                "platform" to "android"
            )
        ).toRequestBody(jsonType)
        try {
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                if (resp.isSuccessful) p.edit().putString(KEY_REGISTERED_TOKEN, token).apply()
                else Log.w(TAG, "Token registration rejected: HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Token registration failed; will retry next launch", e)
        }
    }
}
