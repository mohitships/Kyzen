// Top-level build file where you can add configuration options common to all sub-projects/modules.
// NOTE: AGP 9.0.1 bundles Kotlin internally — do NOT declare kotlin-android separately.
// Declaring it causes "Cannot add extension with name 'kotlin'" IllegalArgumentException.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
    // Compose compiler plugin — declared at root, applied in :app (Phase 0 smoke test).
    // Version pinned to 2.2.10 to match AGP 9.0.1's bundled Kotlin toolchain.
    alias(libs.plugins.compose.compiler) apply false
    // kotlinx-serialization — for InnerTube JSON models in :feature-youtube-flow.
    alias(libs.plugins.kotlin.serialization) apply false
}