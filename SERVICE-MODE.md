# UpDesk — Windows service mode (unattended + UAC / login-screen access)

**Goal:** let a controller see and control the host even at the Windows **login
screen** and through **UAC elevation prompts** — the things a normal user-session
app (our WebView2 + `getDisplayMedia`) fundamentally cannot capture.

This is a multi-phase project. Each phase is independently useful and testable.

## Why our current capture can't do it

Today the host captures via `getDisplayMedia` **inside WebView2**, running in the
interactive user session. Windows renders the **login screen** and **UAC prompts**
on a separate, isolated **secure desktop** (Winlogon) in a different session, which
a user-session webview can neither see nor send input to. Reaching it requires a
**SYSTEM service** + **native capture** that can attach to whichever desktop is
active. This is exactly why RustDesk has `libs/scrap` + a service.

## Architecture (target)

```
┌─ SYSTEM service (session 0, runs at boot, before login) ──────────┐
│  • detects the active console session + current desktop           │
│  • launches/keeps a capture-helper in the active session/desktop  │
│  • relaunches it across session switches (login, lock, UAC)       │
└───────────────┬───────────────────────────────────────────────────┘
                │ CreateProcessAsUser / desktop switch
┌───────────────▼─ capture-helper (per active desktop) ─────────────┐
│  • native screen capture (Desktop Duplication API, DXGI)          │
│  • native input injection (SendInput on that desktop)             │
│  • streams frames + takes input over the existing WebRTC path     │
└───────────────────────────────────────────────────────────────────┘
```

## Phases

**Phase 1 — service lifecycle (this is what's scaffolded now).**
`crates/host-service` — a Windows service that installs/uninstalls itself and, at
boot (as SYSTEM), launches the host-agent in the **active console session** and
keeps it alive across logon/session switches. Gives **unattended, survives-reboot,
pre-login presence**. Does *not* yet capture the secure desktop.
Test: `host-service.exe install` → `sc start UpDeskHost` → reboot → host appears.

**Phase 2 — native screen capture.**
Replace/augment `getDisplayMedia` with native capture (`windows-capture` / Desktop
Duplication). Feed frames into WebRTC via a `MediaStreamTrackGenerator` (Rust →
webview) so the encoder/transport stay as-is. Prerequisite for secure-desktop
capture.

**Phase 3 — secure-desktop capture + input.**
Have the service run the capture-helper on the **Winlogon/secure desktop**
(`OpenInputDesktop` / `SetThreadDesktop`), switching as the active desktop changes,
so UAC and the login screen become visible and controllable.

**Phase 4 — packaging.**
Installer registers + starts the service (needs admin), plus a "run as service"
toggle and clean uninstall.

## Honest status / caveats

- **Session-0, service install, and desktop-switching can only be validated on real
  machines** (ideally with a reboot + a UAC prompt) — not from a dev shell.
- Phase 1 code is **unsafe Win32** (token + process APIs); it compiles, but expect
  iterative on-machine debugging of privileges/session edge cases.
- Full UAC/login capture isn't reached until Phase 3.
