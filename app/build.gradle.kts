import java.util.Properties
import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val testSecrets = Properties().apply {
    val secretsFile = rootProject.file("test-secrets.properties")
    if (secretsFile.isFile) secretsFile.inputStream().use(::load)
}
val playBuild = providers.gradleProperty("playBuild").orNull?.toBoolean() == true ||
    providers.gradleProperty("playFeasibility").orNull?.toBoolean() == true
val privacyPolicyUrl = providers.gradleProperty("privacyPolicyUrl").orNull
    ?: "https://github.com/techjarves/Mobile-Harness/blob/main/PRIVACY.md"
val uploadStorePath = providers.environmentVariable("MH_UPLOAD_STORE_FILE").orNull
val uploadStorePassword = providers.environmentVariable("MH_UPLOAD_STORE_PASSWORD").orNull
val uploadKeyAlias = providers.environmentVariable("MH_UPLOAD_KEY_ALIAS").orNull
val uploadKeyPassword = providers.environmentVariable("MH_UPLOAD_KEY_PASSWORD").orNull
val hasUploadSigning = listOf(
    uploadStorePath,
    uploadStorePassword,
    uploadKeyAlias,
    uploadKeyPassword,
).all { !it.isNullOrBlank() }
val runtimeReleaseBaseUrl =
    "https://github.com/techjarves/Mobile-Harness/releases/download/runtime-2026.09.4"
// The Hermes bundle is too big to ride inside every online update (~52 MB of
// the ~118 MB APK), so it is published once as a release asset in the user's
// own repo and the online flavor downloads it on first install instead (the
// installer verifies it against the bundle's pinned SHA-256). The offline
// flavor still embeds it — see offlineRuntimeBundleFiles below.
val hermesBundleBaseUrl =
    "https://github.com/radhesolanki319-pixel/Mobile-Harness/releases/download/hermes-bundle-2026.11.21"
// The Core bundle follows the same pattern since v40: the Ubuntu 26.04
// core is published as a release asset in the user's own repo (the
// shared upstream runtime release still carries the old Ubuntu 20.04
// core under the same file naming scheme, so the new core must NOT be
// fetched from there). The offline flavor embeds the file from
// dist/runtime-bundles when it is present at build time.
val coreBundleBaseUrl =
    "https://github.com/radhesolanki319-pixel/Mobile-Harness/releases/download/core-bundle-2026.11.4"
val appUpdateManifestUrl =
    "https://github.com/techjarves/Mobile-Harness/releases/latest/download/mobile-harness-update.json"
// v31: the v26 gateway pre-connect is REMOVED (Hermes is embedded only —
// "Woh render wala computer hata do"). The GATEWAY_URL / GATEWAY_KEY
// BuildConfig fields and their HERMES_GATEWAY_KEY env plumbing are gone
// with it; nothing in the app reads them. (The Actions secret and the
// workflow env line may linger repo-side — inert, and not this file's
// concern.)
// v27: FreeLLM — the user's own 158-model API on Render — is pre-connected
// from inside the app: the endpoint is a public constant, and
// the API key reaches the build ONLY through the FREELLM_API_KEY
// environment variable (a GitHub Actions secret on the user's repo, mapped
// into the build step's env); no key material is ever committed here. A
// build without the variable carries an empty key, the baked connection
// stays inactive, and behaviour is exactly as before. In the app the baked
// key only ever fills a BLANK in the shared `custom` provider pool (see
// FreeLlmPreconnect in data/ProviderKeys.kt): a key the user stored always
// wins, and the baked key is never written into the vault.
val freellmBaseUrl = "https://freellmapi-backup.onrender.com/v1"
val freellmApiKey = System.getenv("FREELLM_API_KEY")?.takeIf { it.isNotBlank() } ?: "freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206"
val runtimeBundleDir = rootProject.layout.projectDirectory.dir("dist/runtime-bundles")
val generatedRuntimeAssets = layout.buildDirectory.dir("generated/runtime-assets")

// CI note (2026-11-17): the *.tar.zst runtime bundles live in dist/runtime-bundles/
// but are gitignored — this repo copy only has manifest.json. The app downloads
// missing runtimes at first run (RUNTIME_RELEASE_BASE_URL), so skip absent bundles
// instead of failing the build. On a machine with the bundles present nothing changes.
// The Hermes bundle is the one exception to "shared": since v16 it is embedded
// ONLY in the offline flavor (offlineRuntimeBundleFiles below). The online flavor
// downloads the same archive once, at first Hermes install, from
// hermesBundleBaseUrl — so online updates no longer re-download its ~52 MB.
val agyBundleFile = runtimeBundleDir.file("pocketdev-agy-arm64-2026.09.1.tar.zst")
val hermesBundleFile = runtimeBundleDir.file("pocketdev-hermes-arm64-2026.11.21.tar.zst")
val sharedBundleFiles = listOf(agyBundleFile)
val prepareBundledAgentAssets = tasks.register<Sync>("prepareBundledAgentAssets") {
    onlyIf { sharedBundleFiles.any { it.asFile.isFile } }
    from(sharedBundleFiles.filter { it.asFile.isFile })
    into(generatedRuntimeAssets.map { it.dir("shared/runtime") })
}

val offlineBundleFiles = listOf(
    "pocketdev-core-arm64-2026.11.2.tar.zst",
    "pocketdev-claude-arm64-2026.11.1.tar.zst",
    "pocketdev-python-arm64-2026.09.2.tar.zst",
    "pocketdev-android-arm64-2026.09.1.tar.zst",
).map { runtimeBundleDir.file(it) }
// Hermes joins the offline-only set: it is embedded in the offline flavor
// (same single Sync into offline/runtime — two Sync tasks must never share a
// destination dir, the later one would wipe the earlier one's output) and
// deliberately NOT in the shared set, so the online flavor never embeds it.
val offlineRuntimeBundleFiles = offlineBundleFiles + hermesBundleFile
val prepareOfflineRuntimeAssets = tasks.register<Sync>("prepareOfflineRuntimeAssets") {
    onlyIf { offlineRuntimeBundleFiles.any { it.asFile.isFile } }
    from(offlineRuntimeBundleFiles.filter { it.asFile.isFile })
    into(generatedRuntimeAssets.map { it.dir("offline/runtime") })
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.jarves.mh"
    compileSdk = 36
    // NDK 28 emits flexible-page-size binaries for the static PRoot loader.
    // NDK 26 accepted the max-page-size flag but still produced 4 KiB LOAD
    // segments, which crash under 16 KiB Android runtimes. Builders may still
    // override this explicitly with -PmhNdkVersion=<version>.
    ndkVersion = providers.gradleProperty("mhNdkVersion").orNull ?: "28.2.13676358"

    signingConfigs {
        // One stable signing identity for every debug build, committed in the
        // repo (keystore/hermes-debug.keystore). Without it, each CI runner
        // generated a fresh throwaway debug key, so no build could ever update
        // an installed copy (signature mismatch) and the in-app updater rightly
        // refused every update. Conventional debug alias/passwords ("android"):
        // this identifies the app's builds; it is not an account secret.
        create("stable") {
            storeFile = rootProject.file("keystore/hermes-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasUploadSigning) {
            create("upload") {
                storeFile = rootProject.file(checkNotNull(uploadStorePath))
                storePassword = checkNotNull(uploadStorePassword)
                keyAlias = checkNotNull(uploadKeyAlias)
                keyPassword = checkNotNull(uploadKeyPassword)
                // AGP drops the legacy JAR signature for minSdk >= 24, but some
                // OEM installers (vivo) reject APKs without it as "missing the
                // developer certificate". Keep both; the key is unchanged.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.jarves.mh"
        minSdk = 28
        // The direct APK retains the proven target-28 PRoot execution path. The
        // Play build targets current Android while its runtime path is validated.
        targetSdk = if (playBuild) 36 else 28
        // Keep literal defaults so F-Droid's static manifest parser can detect
        // the tagged release. Gradle properties may still override Play builds.
        versionCode = 56
        versionName = "1.0.55"
        providers.gradleProperty("appVersionCode").orNull?.toIntOrNull()?.let { versionCode = it }
        providers.gradleProperty("appVersionName").orNull?.let { versionName = it }

        ndk.abiFilters += "arm64-v8a"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        buildConfigField("boolean", "IS_PLAY_BUILD", playBuild.toString())
        buildConfigField("String", "PRIVACY_POLICY_URL", buildConfigString(privacyPolicyUrl))
        buildConfigField("String", "HERMES_BUNDLE_BASE_URL", buildConfigString(hermesBundleBaseUrl))
        buildConfigField("String", "CORE_BUNDLE_BASE_URL", buildConfigString(coreBundleBaseUrl))

        buildConfigField(
            "String",
            "TEST_OPENROUTER_API_KEY",
            "\"\"",
        )
    }

    flavorDimensions += "runtimeDelivery"
    productFlavors {
        create("online") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "false")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"online\"")
            buildConfigField("String", "FREELLM_BASE_URL", buildConfigString(freellmBaseUrl))
            buildConfigField("String", "FREELLM_KEY", buildConfigString(freellmApiKey))
        }
        create("offline") {
            dimension = "runtimeDelivery"
            buildConfigField("boolean", "OFFLINE_RUNTIME_BUNDLES", "true")
            buildConfigField("String", "RUNTIME_RELEASE_BASE_URL", buildConfigString(runtimeReleaseBaseUrl))
            buildConfigField("String", "APP_UPDATE_MANIFEST_URL", buildConfigString(appUpdateManifestUrl))
            buildConfigField("String", "APP_VARIANT", "\"offline\"")
            buildConfigField("String", "FREELLM_BASE_URL", buildConfigString(freellmBaseUrl))
            buildConfigField("String", "FREELLM_KEY", buildConfigString(freellmApiKey))
        }
    }

    sourceSets.getByName("offline").assets.srcDir(generatedRuntimeAssets.map { it.dir("offline") })
    sourceSets.getByName("main").assets.srcDir(generatedRuntimeAssets.map { it.dir("shared") })

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
            buildConfigField(
                "String",
                "TEST_OPENROUTER_API_KEY",
                buildConfigString(testSecrets.getProperty("openrouter.apiKey", "")),
            )
        }
        release {
            isMinifyEnabled = false
            if (hasUploadSigning) {
                signingConfig = signingConfigs.getByName("upload")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    packaging.jniLibs.useLegacyPackaging = true
    androidResources.noCompress += "zst"
}

tasks.matching { it.name.startsWith("mergeOffline") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(prepareBundledAgentAssets) }

tasks.matching { it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareBundledAgentAssets) }

tasks.matching { it.name.contains("Offline") && it.name.contains("lint", ignoreCase = true) }
    .configureEach { dependsOn(prepareOfflineRuntimeAssets) }

tasks.register("playReadinessCheck") {
    group = "verification"
    description = "Checks configuration required before uploading a Mobile Harness Play bundle."
    doLast {
        check(playBuild) { "Run with -PplayBuild=true." }
        check(privacyPolicyUrl.startsWith("https://")) {
            "privacyPolicyUrl must be a public HTTPS URL."
        }
        check(hasUploadSigning) {
            "Set MH_UPLOAD_STORE_FILE, MH_UPLOAD_STORE_PASSWORD, MH_UPLOAD_KEY_ALIAS, and MH_UPLOAD_KEY_PASSWORD."
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.luben:zstd-jni:1.5.7-12@aar")
    // Shizuku for phone control (Feature 2)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// CI (2026-11-17): .github/workflows/build.yml uploads app/build/outputs/apk/debug/*.apk,
// but this app has online/offline flavors, so AGP writes the APKs to
// outputs/apk/<flavor>/debug/ and the upload step found nothing. Copy both flavor
// APKs into outputs/apk/debug/ after every assembleDebug so the upload works.
val copyDebugApksForCi = tasks.register<Copy>("copyDebugApksForCi") {
    from(layout.buildDirectory.dir("outputs/apk/online/debug")) { include("*.apk") }
    from(layout.buildDirectory.dir("outputs/apk/offline/debug")) { include("*.apk") }
    into(layout.buildDirectory.dir("outputs/apk/debug"))
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(copyDebugApksForCi) }
