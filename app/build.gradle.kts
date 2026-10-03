plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// f.vi: inputs of the `tenant` flavor below. `.github/workflows/build-tenant-apk.yml` writes
// `app/tenant.properties` (gitignored) from distr's build-config answer before it builds; nobody
// edits it by hand. Absent file = a plain checkout, where the `tenant` flavor only has to configure
// (so IDE sync and `./gradlew assemble` keep working) and an explicit tenant task is refused below.
val tenantProps = java.util.Properties().apply {
    val f = file("tenant.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val tenantId: String? = tenantProps.getProperty("tenant_id")
if (tenantId == null && gradle.startParameter.taskNames.any { it.contains("Tenant") }) {
    throw GradleException(
        "A tenant build was asked for but app/tenant.properties does not exist. " +
            "It is written by .github/scripts/prepare_tenant_build.py; see HANDOVER.md f.vi."
    )
}
if (tenantId != null && !Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\$").matches(tenantId)) {
    throw GradleException("tenant_id in app/tenant.properties is not a DNS-label-safe id")
}
val tenantVersionCode: Int = tenantProps.getProperty("version_code")?.toIntOrNull()?.takeIf { it > 0 } ?: 1

android {
    namespace = "com.vythera.vyxelapps"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.vythera.vyxelapps"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // e.i: push config, blank by default = push disabled (see api/PushRegistrar.kt). Supplied
        // via Gradle properties (-PfcmProjectId=... or ~/.gradle/gradle.properties), never committed.
        fun prop(name: String) = (project.findProperty(name) as String? ?: "").replace("\"", "")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${prop("fcmProjectId")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${prop("fcmAppId")}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${prop("fcmApiKey")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${prop("fcmSenderId")}\"")
        buildConfigField("String", "PUSH_REGISTRATION_URL", "\"${prop("pushRegistrationUrl")}\"")
        // 7.b.iv.zi: D-Store catalog API base URL (e.g. https://<deployment>), blank = D-Store browse is empty. Gradle property dstoreCatalogUrl, never committed.
        buildConfigField("String", "DSTORE_CATALOG_URL", "\"${prop("dstoreCatalogUrl")}\"")
    }

    // Packaging-identity-only product flavors -- leaf `1.c.i.zo`. One flavor per distributable
    // tenant (`spec/tenant-config-schema.md`'s `tenant_id`), covering ONLY what Android makes
    // genuinely build-time-fixed: `applicationId`, launcher name, launcher icon. Everything else
    // -- business logic, UI, the data layer, and in-app branding (`TenantConfig.branding`,
    // `1.c.ii.zi`, still open) -- stays one codebase, one path; flavors select packaging metadata,
    // never behavior (`HANDOVER.md`'s "Multi-tenant model" decision).
    flavorDimensions += "tenant"
    productFlavors {
        // The seed/default tenant -- spec's `is_default_tenant: true`. Deliberately overrides
        // nothing: no `applicationId`, no `resValue`, no flavor-specific `res/` folder. This
        // flavor's entire point is to reproduce today's pre-multi-tenant identity byte-for-byte,
        // so it inherits `applicationId`/`app_name`/launcher icon straight from `defaultConfig`
        // and the main source set rather than restating them here, where a typo could quietly
        // diverge from what's already shipping. A future white-label tenant's own flavor block is
        // where `applicationId` gets overridden and a `src/<flavor>/res/` folder supplies its own
        // `app_name`/launcher icon -- this flavor is the "nothing to see here" baseline that
        // pattern gets added alongside, not built speculatively ahead of an actual second tenant.
        create("default") {
            dimension = "tenant"
        }

        // f.vi: the second flavor, the one `1.c.i.zo` had no tenant to build for. ONE flavor for ALL
        // distr-provisioned tenants, not one block per tenant: the workflow supplies the tenant's
        // identity at build time (`app/tenant.properties`, plus `src/tenant/res/` for the launcher
        // label and icon), so a new tenant never needs a commit here. It still selects packaging
        // metadata only (applicationId, launcher label, launcher icon, version), never behavior.
        // `t_` keeps every applicationId segment starting with a letter whatever the tenant_id
        // starts with; hyphens become underscores because a package segment cannot hold one.
        create("tenant") {
            dimension = "tenant"
            applicationId = "com.vythera.tenant.t_" + (tenantId ?: "unconfigured").replace('-', '_')
            versionCode = tenantVersionCode
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.airbnb.android:lottie-compose:6.4.0")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // e.i: FCM. Options are supplied programmatically (no google-services plugin / json).
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Ed25519 for the Zealot catalog-index trust check (1.a.ii.zi). Not java.security/platform
    // crypto: Android's own java.security.interfaces.EdECKey (and the Ed25519 Signature/KeyFactory
    // support behind it) was only added at API 33 (confirmed against Android's reference docs),
    // and this module's minSdk is 26 -- the platform provider would silently have no working
    // Ed25519 below Android 13. BouncyCastle's algorithm classes (Ed25519Signer /
    // Ed25519PublicKeyParameters, used directly -- never registered as a JCA Provider) work
    // identically on every API level this app supports.
    implementation("org.bouncycastle:bcprov-jdk18on:1.84")
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowSize)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // Networking - for GitHub API calls
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")

// Image loading - for app icons/avatars
    implementation("io.coil-kt:coil-compose:2.6.0")

// ViewModel - for managing app state
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

// Coroutines - for background API calls
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
