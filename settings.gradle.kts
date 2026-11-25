pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Settings plugins can't read the version catalog; this is the one version pinned outside it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "taptap"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":mesh-core", ":crypto-jvm", ":simulator", ":android")
