pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FlightDeckSentry"
// :core  = pure Kotlin/JVM alert engine + feed parsers (no Android, no DJI).
// :app   = the Android shell: foreground service, voice, UI.
include(":core", ":app")
