import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Secrets come from the gitignored secrets.properties (see .example). They are
// DEFAULTS only — every value can be changed in Settings on the controller.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// ── version: ONE source of truth ────────────────────────────────────────────
// The VERSION file at the repo root (e.g. 0.3.0). `-PsentryVersion=0.2.9`
// overrides it for a local test build (used to test the self-updater).
// versionCode = major*10000 + minor*100 + patch (0.3.0 -> 300), so it rises
// monotonically with the version name (0.2.0 was versionCode 2).
val sentryVersion: String = (findProperty("sentryVersion") as String?)?.trim()
    ?: rootProject.file("VERSION").readText().trim()
val sentryVersionCode: Int = run {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(sentryVersion)
        ?: throw GradleException("VERSION must be MAJOR.MINOR.PATCH, got '$sentryVersion'")
    val (maj, min, pat) = m.destructured
    require(min.toInt() < 100 && pat.toInt() < 100) { "minor/patch must be < 100 for the versionCode formula" }
    maj.toInt() * 10000 + min.toInt() * 100 + pat.toInt()
}

// ── release signing ────────────────────────────────────────────────────────
// Looked up in this order (the keystore and its password NEVER enter the repo):
//  1. environment: SENTRY_KEYSTORE_FILE, SENTRY_KEYSTORE_PASSWORD, SENTRY_KEY_ALIAS (CI)
//  2. secrets.properties (gitignored): the same three keys
//  3. ~/.sentry-release.jks + ~/.sentry-release.env (the owner's machine)
// Nothing found -> `assembleRelease` fails with a clear message; debug builds are unaffected.
val homeEnv = Properties().apply {
    val f = File(System.getProperty("user.home"), ".sentry-release.env")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: secrets.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: homeEnv.getProperty(key)?.takeIf { it.isNotBlank() }
val releaseKeystore: File? = (signingValue("SENTRY_KEYSTORE_FILE")?.let { file(it) }
    ?: File(System.getProperty("user.home"), ".sentry-release.jks")).takeIf { it.isFile }
val releaseStorePassword: String? = signingValue("SENTRY_KEYSTORE_PASSWORD")
val releaseKeyAlias: String = signingValue("SENTRY_KEY_ALIAS") ?: "sentry"
val hasReleaseKey = releaseKeystore != null && releaseStorePassword != null

android {
    namespace = "com.uasflightdeck.sentry"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.uasflightdeck.sentry"
        minSdk = 26            // RC Plus is Android 10 (29); 26 for headroom
        targetSdk = 33
        versionCode = sentryVersionCode
        versionName = sentryVersion

        buildConfigField("String", "FLEET_TOKEN", "\"${secrets.getProperty("FLEET_TOKEN", "")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = releaseKeystore
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseStorePassword
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            // Minify stays off for now: no smoke-tested proguard config yet.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Local convenience: when the release key is on this machine, debug builds use it too,
            // so a local build installs over a GitHub release (and back) without an uninstall.
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}

// `./gradlew testDebugUnitTest` is the documented gate. The engine tests live in
// the pure-JVM :core module, so make the gate run them too — otherwise a green
// testDebugUnitTest could mean "no engine tests ran".
tasks.matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" }.configureEach {
    dependsOn(":core:test")
}

// A release build without the release key must fail loudly, not quietly produce an
// unsigned APK that can never update the installed one.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (!hasReleaseKey) throw GradleException(
            "Release signing key not found. Set SENTRY_KEYSTORE_FILE + SENTRY_KEYSTORE_PASSWORD (+ SENTRY_KEY_ALIAS) " +
                "in the environment or secrets.properties, or put ~/.sentry-release.jks + ~/.sentry-release.env on this machine."
        )
    }
}
