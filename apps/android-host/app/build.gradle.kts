plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nikhil.updeskhost"
    compileSdk = 36
    buildToolsVersion = "36.0.0"   // matches what's installed locally

    defaultConfig {
        applicationId = "com.nikhil.updeskhost"
        minSdk = 26          // Android 8.0 — MediaProjection + foreground services
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // WebRTC (org.webrtc.*) — screen capture + peer connection.
    implementation("io.getstream:stream-webrtc-android:1.1.1")

    // WebSocket signaling client.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Ed25519 identity/signing (matches the server's SPKI-base64 scheme).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
