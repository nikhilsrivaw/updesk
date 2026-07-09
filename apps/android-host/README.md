# UpDesk Android Host (baseline)

A native Android host that captures the phone's screen and streams it to your
existing UpDesk **controller**, through the **same cloud signaling server**
(`wss://updesk.duckdns.org`) and the same ID+PIN model. This baseline is
**screen-view first** — input (Accessibility/Knox/root) comes in later layers.

## What works in this baseline
- Self-enrolls to the cloud server (Ed25519, open enrollment — no code).
- Shows **Your ID + PIN** like the desktop host.
- On an incoming request with the right PIN → asks for screen-capture permission
  (Android's one-time MediaProjection dialog) → streams the screen over WebRTC.
- The desktop/existing controller sees the phone's screen live.

## Not in this baseline (next layers)
- Remote **input** (tap/type) — needs an Accessibility Service (layer 2), or
  Knox (managed Samsung, layer 3), or root (custody, layer 4).
- Unattended (no-PIN) mode (layer 5).

## Build & run
1. Install **Android Studio** (Giraffe+), with JDK 17 and the Android SDK
   (API 34). Enable **USB debugging** on your test phone.
2. Open this folder (`apps/android-host`) in Android Studio → let Gradle sync.
3. Plug in the phone → **Run**. The app installs and opens.
4. Tap **Go online** → it shows an ID + PIN.
5. On your desktop **controller**, enter that ID + PIN → Connect. The phone will
   prompt "Start recording/​casting?" — accept → your screen streams.

## Protocol note
This host speaks the identical JSON-over-WebSocket protocol as the desktop host
(auth_init → auth_challenge → auth_response → auth_ok; register → registered;
incoming_request; offer/answer/ice_candidate). No server changes needed.

## Status
Build-ready scaffold, **not yet compiled/tested on-device** (no Android toolchain
in the dev shell it was written from). Expect to iterate the first build in
Android Studio — the wiring and protocol are the parts most carefully matched.
