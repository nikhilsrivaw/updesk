# UpDesk — Security Hardening (pre-handover)

Everything below uses demo/default values today. Replace before a real deployment.

## 1. Credentials to change

| Secret | Where it lives now | Change to |
|---|---|---|
| **Native-host password** | default `updesk` (`crates/native-host`: `password.txt` / `UPDESK_PW` env / built-in default) | a strong per-device password. Put it in `C:\ProgramData\UpDesk\password.txt` at install time. |
| **TURN password** | hard-coded `updesk_turn_9fKq2mXz7L` in `apps/*/rtcConfig.js`, `apps/android-*/.../WebRtcClient.kt` (setPassword), and coturn `turnserver.conf` | a new shared secret; must match in **all** apps + the server, then rebuild. |
| **Android unattended password** | auto-generated per install (`Identity.getPassword`) — OK, but shown in-app; operators should record it securely. | (no code change; process only) |
| **Signing keystore** | demo `dist/field/updesk-field.keystore`, password `updeskfield`, **in the repo** | a real keystore kept in a secret store; never commit. See DEPLOY-AND-BUILD.md. |

After changing the TURN password: update it in every `rtcConfig.js` + the two
Android `WebRtcClient.kt` files + `turnserver.conf`, then rebuild all apps and
`sudo systemctl restart coturn`.

## 2. <a name="end-to-end"></a>End-to-end encryption

Status: **warn-only, not enforced, not device-tested.**

- The transport is already encrypted (WebRTC DTLS-SRTP + wss/TLS to the server).
- The E2E layer (signed DTLS fingerprints + TOFU key pinning) is *implemented in
  the signaling/verify code* but currently only **warns** on mismatch instead of
  aborting — so a malicious/compromised **server** could in theory MITM.
- **Decision needed:**
  - **Enforce** — flip the warn to a hard-abort on fingerprint/key mismatch, then
    test that a normal session still shows "🔒 verified" with matching key
    fingerprints on both ends. (Closes the server-MITM gap — the "just like
    RustDesk" goal.)
  - **Accept TLS-only** — document that trust rests on the server, and lock the
    server down (below).

## 3. Server lockdown

- SSH (port 22) restricted to admin IPs only (not `0.0.0.0/0`).
- Keep the OS patched; the disk-fill incident is fixed (EBS 30 GB + journald caps)
  but keep an eye on `df -h`.
- Consider fail2ban on SSH.
- coturn: `lt-cred-mech` on (it is); rotate the TURN secret periodically.
- Signaling server already has PIN rate-limiting (5 fails / 60 s).

## 4. Android app hardening

- Ed25519 identity seed currently in SharedPreferences — for higher assurance move
  it to the Android Keystore (hardware-backed). Noted in `Identity.kt`.
- `allowBackup="false"` is already set on the apps.
- Field/native hosts: the fixed password is the whole access control — treat it
  like a device credential and rotate if a phone is lost (change it in-app).

## 5. Chain of custody (already built)

The controller logs each file transfer + audio/video recording with a **SHA-256
hash + timestamp + examiner + device ID**, exportable as CSV/JSON. Have the
customer's evidence process review the export format.
