plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nikhil.updeskfield"
    compileSdk = 36
    buildToolsVersion = "36.0.0"   // matches what's installed locally

    defaultConfig {
        applicationId = "com.nikhil.updeskfield"
        minSdk = 26          // Android 8.0 — foreground services + Camera2
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Ship the arm64 native libraries only. Every modern phone is arm64, and
        // this drops the x86/armeabi-v7a copies of the (multi-MB) WebRTC engine —
        // the single biggest size win, roughly halving the APK. To also target
        // older 32-bit devices, add "armeabi-v7a" here.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            // Shrink unused Java/Kotlin code + resources. Obfuscation is left OFF
            // (proguard-rules keeps names) so stack traces stay readable and the
            // reflection-heavy WebRTC/OkHttp code can't be broken by renaming.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    // The release build's "lintVital" step pulls an extra artifact at build time;
    // it's not needed to produce the APK and shouldn't gate packaging. Disable it
    // so an offline/proxied build environment can still assemble the release APK.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // WebRTC (org.webrtc.*) — camera capture + peer connection.
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // WebSocket signaling client.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Ed25519 identity/signing (matches the server's SPKI-base64 scheme).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
