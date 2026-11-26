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
        // CI passes the build number: every build gets its own version (1.42 → code 42)
        val build = (System.getenv("BUILD_NUMBER") ?: "0").toInt()
        versionCode = maxOf(1, build)
        versionName = "2.$build"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // One fixed key for every build: a new version installs over the old one (keeps settings and projects).
    // The key is in the repo on purpose (personal app, chosen by Dave).
    signingConfigs {
        create("fixed") {
            storeFile = file("signing/framegrabber.jks")
            storePassword = "framegrabber"
            keyAlias = "framegrabber"
            keyPassword = "framegrabber"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
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

// TDLib (Telegram client library), built from the official source by .github/workflows/tdlib.yml
// and unpacked into app/tdlib by the CI build (Java API + native libraries).
android.sourceSets.getByName("main") {
    java.srcDir("tdlib/java")
    jniLibs.srcDir("tdlib/libs")
}

dependencies {
    // TDLib's generated TdApi.java uses these annotations
    implementation("androidx.annotation:annotation:1.8.2")
    // OCR ("copy text from a frame"): Tesseract, works offline, reads Latin, Cyrillic and Armenian
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.8.0")
    // FFmpeg frame decoder for files Android can't open itself (AVI, FLV, MPG, WMV, some TS)
    implementation("com.github.wseemann:FFmpegMediaMetadataRetriever-core:1.0.19")
    implementation("com.github.wseemann:FFmpegMediaMetadataRetriever-native-arm64-v8a:1.0.19")
    implementation("com.github.wseemann:FFmpegMediaMetadataRetriever-native-armeabi-v7a:1.0.19")
    implementation("com.github.wseemann:FFmpegMediaMetadataRetriever-native-x86_64:1.0.19")
    // on-emulator tests only
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
