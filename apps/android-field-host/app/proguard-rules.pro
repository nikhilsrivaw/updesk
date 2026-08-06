# Keep names readable (no obfuscation) — safest with reflection-heavy libs, and
# stack traces stay meaningful for a field-deployed app.
-dontobfuscate

# --- WebRTC (native JNI bridge — must not be stripped/renamed) ---
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }

# --- OkHttp / Okio (used by signaling) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# --- BouncyCastle (Ed25519 identity signing) ---
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.** { *; }
