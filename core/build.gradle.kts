// Pure Kotlin/JVM module: the alert engine, geodesy, CPA math and every feed
// parser. It has NO Android dependency on purpose, so the logic that decides
// whether a pilot hears "Warning, traffic" is unit-tested on the JVM against
// a real encounter recorded from public ADS-B data (the demo replay).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    jvmToolchain(17)
}

dependencies {
    // JSON tree parsing (parseToJsonElement) — no compiler plugin needed.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The demo replay fixture lives in the app's assets so the app replays
    // exactly the bytes the tests assert on.
    systemProperty("sentry.assets", rootProject.file("app/src/main/assets").absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
