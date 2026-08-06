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
rootProject.name = "UpDeskHostNative"

// === PARKED (2026-08-01) — unattended Android screen host is SHELVED for now. ===
// All source is preserved and compiles; it's just disabled from the build. To
// bring it back, UNCOMMENT the line below and build :app as usual.
// include(":app")
