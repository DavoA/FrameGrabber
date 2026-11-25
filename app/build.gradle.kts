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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
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

dependencies {
    // OCR ("copy text from a frame"): Tesseract, works offline, reads Latin, Cyrillic and Armenian
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.8.0")
    // on-emulator tests only
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
