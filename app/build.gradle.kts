plugins {
    alias(libs.plugins.android.application)
    // kotlin-android is NOT declared here — AGP 9.0.1 bundles Kotlin internally.
    // Declaring it separately causes IllegalArgumentException: duplicate 'kotlin' extension.
    alias(libs.plugins.ksp)
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
// Testing
testImplementation(libs.junit)
testImplementation(libs.mockito.core)
androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}