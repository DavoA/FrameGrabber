plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dave.framegrabber"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dave.framegrabber"
        minSdk = 29          // Android 10+: saving to gallery without storage permission
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No external libraries: everything uses the Android framework only.
dependencies {}
