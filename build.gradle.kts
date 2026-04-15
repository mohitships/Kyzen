// Top-level build file where you can add configuration options common to all sub-projects/modules.
// NOTE: AGP 9.0.1 bundles Kotlin internally — do NOT declare kotlin-android separately.
// Declaring it causes "Cannot add extension with name 'kotlin'" IllegalArgumentException.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
}