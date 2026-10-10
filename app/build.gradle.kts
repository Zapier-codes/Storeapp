// Imported here because inside a Gradle Kotlin script `java` is the Java plugin extension, so the
// fully qualified `java.util.Properties()` does not compile (CI run 37121169324, line 10).
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// f.vi: inputs of the `tenant` flavor below. `.github/workflows/build-tenant-apk.yml` writes
// `app/tenant.properties` (gitignored) from distr's build-config answer before it builds; nobody
// edits it by hand. Absent file = a plain checkout, where the `tenant` flavor only has to configure
// (so IDE sync and `./gradlew assemble` keep working) and an explicit tenant task is refused below.
val tenantProps = Properties().apply {
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

// 7.a.viii.zi: the first-party release version is read from two Gradle properties that
// `.github/workflows/release-aab.yml` sets (-PreleaseVersionName from the tag, -PreleaseVersionCode from the
// run number plus an offset), so a second release can never carry the first one's version. A plain checkout
// sets neither and keeps the local defaults below. A property that is set but malformed stops the build with
// a message: silently falling back to the default is exactly the bug this closes. The `tenant` flavor is not
// touched; it keeps `tenantVersionCode` (f.vi).
// j.ii: the local defaults were stale (`1.0.2`), so a plain `assembleDebug` reported an old version in the
// About card. They now name the current line from `version.properties` (`major_minor=1.1`); CI still
// overrides both from the tag and the run number, so a released build never reads these.
val defaultVersionCode = 3
val defaultVersionName = "1.1.0"
val releaseVersionCode: Int = project.findProperty("releaseVersionCode")?.toString()?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { raw ->
        raw.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
            ?: throw GradleException("-PreleaseVersionCode must be a whole number from 1 to 2100000000, got '$raw'")
    }
    ?: defaultVersionCode
val releaseVersionName: String = project.findProperty("releaseVersionName")?.toString()?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.also { raw ->
        if (!Regex("^[0-9]+(\\.[0-9]+){1,3}(-[0-9A-Za-z.]+)?\$").matches(raw)) {
            throw GradleException("-PreleaseVersionName must look like 1.2.3 or 1.2.3-rc.1, got '$raw'")
        }
    }
    ?: defaultVersionName

android {
    namespace = "com.vythera.vyxelapps"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.vythera.vyxelapps"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName

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
        // 1.a.i.zo: Zealot's signed catalog index, served from its Render host (Zealot Task 45g). The app adds
        // /index.json and /index.json.sig. A public URL, not a secret, so it is the default; -PzealotCatalogUrl
        // overrides it (a blank property falls back to the default).
        buildConfigField("String", "ZEALOT_CATALOG_URL", "\"${prop("zealotCatalogUrl").ifBlank { "https://zealot-deploy-latest.onrender.com/catalog" }}\"")
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
        debug {
            // Distinct package so an open-core debug build installs alongside a
            // released Vyxel rather than replacing it — the two are signed with
            // different keys, so same-package installs would be refused anyway.
            //
            // Safe here specifically because this build has no google-services.json:
            // that file pins the package name, which is why the paid build cannot
            // carry a suffix.
            applicationIdSuffix = ".opencore"
            versionNameSuffix = "-opencore"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            // The Expressive shell is built on Material 3 Expressive, which is still
            // opt-in on the 1.5.0-alpha line (MaterialExpressiveTheme, MotionScheme,
            // LoadingIndicator, the wavy progress indicators).
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
                "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }
    testOptions {
        // Pure-logic unit tests touch a few android.* stubs (Log, TextUtils);
        // returning defaults keeps them off Robolectric.
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {


    implementation("io.coil-kt:coil-compose:2.6.0")

    // --- Expressive UI (com.vythera.vyxelapps.expressive) ---
    // Coil 3 sits alongside Coil 2 rather than replacing it: the Classic UI is built
    // against the Coil 2 API throughout, and the two live in different packages
    // (io.coil-kt vs io.coil-kt.coil3) so they don't collide.
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("io.github.kyant0:backdrop:2.0.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

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
    // Z-P13: bzip2 reading for the archive-patcher File-by-File patch's inner bsdiff stream. The
    // JDK has no bzip2; Zealot writes the three bsdiff blocks with bzip2 and the client must read
    // them. Commons Compress is the standard, small API for this (BZip2CompressorInputStream);
    // commons-io is its own transitive dependency, declared so the version is pinned here too.
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("commons-io:commons-io:2.18.0")
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowSize)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // Networking - for GitHub API calls
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")

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
