# UpDesk silent native host

A fully **silent, native** remote-access host (no webview, no window, no picker,
no consent prompt). Screen capture → H.264 → WebRTC, connected to the same
signaling server as the other UpDesk hosts. View **and** control.

## Run it (streaming mode)
```
native-host.exe
```
It prints a 9-digit **ID** and an unattended **password**. A controller connects
with those and immediately sees + controls the screen — no prompt on the host.

Config (env):
- `UPDESK_PW` — unattended password (default `updesk`)
- `UPDESK_URL` — signaling server (default `wss://updesk.duckdns.org`)

## Run it unattended from boot (Windows service)
Run an **elevated** prompt:
```
native-host.exe install       # register + auto-start at boot (LocalSystem)
sc start UpDeskNativeHost
```
The service launches the host in the **active user session** and keeps it alive
across reboots + session switches — so a machine comes back online after a reboot
with no one touching it. Remove with:
```
native-host.exe uninstall
```

## What works
- Silent capture + H.264 + WebRTC (verified)
- Ed25519 auth to the cloud, register, auto-accept on the password
- View + native input control (mouse/keyboard via SendInput)
- Unattended service (auto-start, active-session, survives reboot)

## Known limits / frontier
- **Login screen / UAC (secure desktop):** the service captures the *logged-in*
  desktop. Capturing the Winlogon secure desktop (before login, or during a UAC
  prompt) needs SYSTEM + desktop-switching (`OpenInputDesktop`/`SetThreadDesktop`)
  and Desktop Duplication is finicky there — a further step, not yet done.
- **fps** is screen-change-driven (Desktop Duplication emits on change); a static
  screen is kept alive via periodic keyframes.
- Built + tested on Windows. macOS/Linux would need per-OS capture/input.
