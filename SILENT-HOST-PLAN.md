# UpDesk silent native host — plan

Goal: a **fully silent** host — captures + streams the screen with **no gesture,
no picker, no window**, so it can run headless / as a service / from boot. This
is the RustDesk-class core (RustDesk uses a native binary, not a webview).

Built as a **standalone Rust binary** (`crates/native-host`) — separate from the
Tauri webview host — so it has no webview capture limits.

## Why native (vs the webview host)
The Tauri host uses `getDisplayMedia`, which the browser forces to require a user
gesture + a screen picker. A native binary captures the framebuffer directly
(Desktop Duplication / Windows.Graphics.Capture) — silent by nature.

## Phases (each verified before the next)
1. **Silent capture** ✅ target — grab screen frames in Rust, no UI. (`scrap`)
2. **Encode** — H.264/VP8 the frames (hardware if available).
3. **Native WebRTC** — `webrtc-rs` peer: add the encoded video track, do
   offer/answer/ICE.
4. **Signaling** — connect to `wss://updesk.duckdns.org`, run the same Ed25519
   auth + register + incoming_request protocol as the other hosts.
5. **Silent unattended** — auto-accept on the unattended password; no UI at all.
6. **Service + input** — run as a Windows service (survives boot, reaches the
   login/UAC secure desktop) + native input injection (SendInput). Unlocks true
   unattended AND service-mode AND is the base for cross-platform hosts.

## Status
Phase 1 in progress. This is a multi-week build; expect on-machine iteration.
