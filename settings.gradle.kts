pluginManagement {
    repositories {
        // KSP plugin artifacts are published to mavenCentral and gradlePluginPortal.
        // google() content filter must NOT restrict com.google.devtools.ksp — it is
        // NOT an Android/Google/AndroidX artifact, so we broaden the google() filter
        // and ensure gradlePluginPortal is included for KSP resolution.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kyzen"
include(":app")
