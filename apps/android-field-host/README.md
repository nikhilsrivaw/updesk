# UpDesk Field

A **transmit-only** Android host — a live field bridge. Where `android-host`
shares the phone's *screen* (and accepts remote input), **UpDesk Field** streams
the phone's **camera + microphone + GPS location** to an UpDesk controller. It is
a completely separate app (`com.nikhil.updeskfield`) that installs alongside the
screen host and gets its own 9-digit connect ID + 6-digit PIN.

Intended use: a field officer's phone acting as a body-cam / live-location beacon
into the control room.

## What it does

- **Camera** — front/back, switchable mid-session, shown in a local preview.
- **Microphone** — always on (the point of the device is live A/V).
- **Location** — streamed as JSON over a dedicated `location` WebRTC data channel
  (`{lat, lon, accuracy, altitude, speed, bearing, provider, time}`), using the
  plain Android `LocationManager` — **no Google Play Services dependency**, so it
  works on de-Googled / AOSP / custody handsets.
- Same signaling server, Ed25519 identity, STUN/TURN, PIN auth, ICE-restart
  recovery, and signed-DTLS-fingerprint E2E as the rest of UpDesk.
- A foreground service (types `camera|microphone|location`) + wake lock keep the
  stream alive with the screen off.

It is transmit-only: **no** accessibility input, file browser, or root — a field
device only broadcasts.

## Controller side

The controller (`apps/controller-app`) recognizes the `location` channel and
shows a live **📍 Field location** panel (coordinates, accuracy, speed, heading,
and an "Open in Maps" link). Camera video + mic audio appear in the normal video
view. Because the field host announces `input:false`, the controller marks the
session view-only and hides the input/file UI.

## Build

Open `apps/android-field-host` in **Android Studio** (same as `android-host`) and
Run, or from a shell with the Gradle wrapper:

```
./gradlew :app:assembleRelease     # smallest APK (shrunk, arm64-only)
./gradlew :app:assembleDebug       # for quick testing
```

The release APK is **arm64-v8a only** with R8 code + resource shrinking (see
`app/build.gradle.kts` / `proguard-rules.pro`) to keep it as small as a WebRTC
app can be (~8–12 MB; the native WebRTC media engine is the floor and can't be
removed). To also support 32-bit devices, add `"armeabi-v7a"` to `abiFilters`.

## Permissions

Requested at first launch: **Camera**, **Microphone**, **Location** (fine), and
Notifications (Android 13+). All three capture permissions should be granted for
a full stream; the app degrades gracefully if one is missing (e.g. no camera →
mic + location only).
