# UpDesk — Operator (Control Room) Guide

For the person running the **controller** app and connecting to devices.

## Connect to a device
1. Open **UpDesk Controller**.
2. Enter the device's **ID** (9 digits) and its **PIN or unattended password**.
3. Enter your name / badge ID under **Examiner** (goes into the evidence log).
4. Click **Connect**.

The device shows its ID + PIN/password in its own UpDesk app (or `Show-ID.bat` on
a native Windows host).

## The top-bar badge (health)
While connected, the bar shows: `direct|relay(udp/tcp) • <ms> • <codec> ▸ <WxH> · <fps> · <kbps>`.
- **direct** = peer-to-peer (fast). **relay** = via the server (cross-network; higher latency).
- Use it to sanity-check quality/latency (e.g. `direct • 20 ms • H264 ▸ 720×1600 · 58fps · 6000 kbps`).

## Screen hosts (Windows / Android screen)
- The video is the remote screen. **Click/drag** to control it (Android needs its
  Accessibility service enabled — the phone warns "VIEW-ONLY" if not).
- **Quality** dropdown, **Files**, **Network**, **Chat**, **Evidence log**, and (Android)
  **Back/Home/Recents** buttons are in the top bar.
- Android note: **moving** the mouse shows no cursor (Android has none for injected
  input) — you must **click** to tap.

## Field devices (UpDesk Field — camera/mic/GPS)
Connecting to a Field phone auto-switches to the **Field Monitor**:
- **Left:** live camera. **🔊 Listen** to hear audio (click once — browsers block
  auto-sound). **🔄 Flip camera** switches front/back (controlled from here).
- **Right:** live **map with a breadcrumb trail**, distance, speed, heading, and an
  **Open in Google Maps** link. (Map tiles need internet on the controller PC.)
- **● Record audio** / **⬤ Record video** — saves the clip locally to
  `Downloads\UpDesk\` and logs it (with a SHA-256 hash) in the **Evidence log**.

## Evidence log (chain of custody)
- Every file transfer + recording is logged with timestamp, examiner, device ID,
  and SHA-256 hash. Open **Evidence log**, export **CSV** or **JSON**.

## Troubleshooting
- **"no online device with that ID"** — the device isn't online / wrong ID, or its
  network blocks the server. Confirm the device app shows "online".
- **Laggy over mobile data** — it's relaying through the server (unavoidable on
  carrier NAT); Wi-Fi gives a direct, faster path.
- **Android taps do nothing** — enable the phone's **Accessibility** service (it
  gets disabled whenever the app is reinstalled).
- **Field Monitor didn't appear** — check the log panel for
  "field host detected (location channel)"; if missing, the Field app isn't online.
