# UpDesk — On-Device Test Plan

The one thing that can't be done from the dev machine. Run every check on the
actual hardware before signing off. Mark P (pass) / F (fail).

## Server / connectivity
- [ ] `systemctl status updesk-signaling caddy coturn` all active on EC2.
- [ ] AWS Security Group has UDP 3478 **and** UDP 49152–65535 open.
- [ ] From two different networks, a session connects (not just LAN).

## Desktop controller ↔ desktop host
- [ ] Attended host (`host-agent`): connect, see screen, control it.
- [ ] Badge shows `direct` on same network; `relay` across networks.
- [ ] Over cellular the desktop host is smooth (no periodic freeze) — the stutter fix.

## Windows native host (unattended)
- [ ] `install.bat` (extracted, as admin) installs the service.
- [ ] `Show-ID.bat` shows ID + password; controller connects with them.
- [ ] Reboot the PC → it comes back online with no login/interaction.
- [ ] `uninstall.bat` removes it.

## Android host (screen, attended)
- [ ] Install debug APK; enable Accessibility; Go online.
- [ ] Controller sees the screen; clicking taps the phone.
- [ ] **Wi-Fi:** sharp + smooth; badge codec = **H264**, fps ~50–60.
- [ ] Sleep the phone, wake it → screen recovers (not frozen).
- [ ] Cellular: stays smooth (auto lower quality), not frozen.

## UpDesk Field (camera/mic/GPS, 24/7) — the big one
- [ ] Grant camera/mic/location + battery-opt off + display-over-apps (+ OEM autostart).
- [ ] Go online; controller shows **Field Monitor**: camera + audio + moving map.
- [ ] **🔄 Flip camera** works from the controller.
- [ ] **● Record audio** / **⬤ Record video** → files land in `Downloads\UpDesk\`,
      appear in Evidence log with a SHA-256 hash.
- [ ] **Swipe from Recents** → still streaming / reconnects.
- [ ] **Leave idle 30–60 min** (screen off, on battery) → still online (Doze test).
- [ ] **Reboot phone, unlock once, don't open the app** → reconnects on its own.
- [ ] **Cold-background connect:** with the app fully backgrounded, connect from the
      controller → camera starts unattended (overlay path). *If it fails on a
      specific phone, note the make/model.*

## Evidence / custody
- [ ] Evidence log exports valid CSV + JSON with hashes.

## Notes / device quirks
(Record any phone make/model where autostart, overlay, or 60fps behaves differently.)
