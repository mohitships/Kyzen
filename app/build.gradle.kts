plugins {
    alias(libs.plugins.android.application)
    // kotlin-android is NOT declared here — AGP 9.0.1 bundles Kotlin internally.
    // Declaring it separately causes IllegalArgumentException: duplicate 'kotlin' extension.
    alias(libs.plugins.ksp)
    // Compose compiler plugin — Phase 0 smoke test. Pinned to 2.2.10 to match
    // AGP 9.0.1's bundled Kotlin. Validates Compose + KSP source-set coexistence
    // under android.disallowKotlinSourceSets=false.
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.binarybrigade.kyzen"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.binarybrigade.kyzen"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true
        multiDexKeepProguard = file("multidex-config.pro")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // Compose build feature — Phase 0 smoke test. Enables the Compose runtime
    // compiler to process @Composable functions in the app module.
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Room - Local SQLite Persistence (Phase 3)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Kotlin Coroutines - Safe background threading for Room (Phase 3)
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle - lifecycleScope for Activities and coroutine-aware services (Phase 3)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Glide - Animated GIF rendering for the rotating gem on the child dashboard
    implementation(libs.glide)

    // Compose - Phase 0 smoke test. activity-compose hosts the probe ComponentActivity;
    // the BOM pins ui/foundation/material3. This block exists to verify that the
    // Compose compiler plugin (2.2.10) coexists with KSP (2.2.10-2.0.2) and Room
    // codegen under android.disallowKotlinSourceSets=false without source-set conflicts.
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // :feature-youtube-flow — the embedded YouTube player library module (Phase 2).
    // Provides FlowPlayerActivity (launched via explicit Intent from the intercept
    // overlay) and FlowPlaybackState (the in-process bridge singleton read by
    // UsageMonitorService's effectiveTrackablePackage() helper).
    implementation(project(":feature-youtube-flow"))
// Testing
testImplementation(libs.junit)
testImplementation(libs.mockito.core)
androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
