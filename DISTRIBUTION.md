# UpDesk — distribution & packaging

How to build, sign, and update the two apps for real deployment.

## Apps and roles

| App | Role | Installer |
|-----|------|-----------|
| `apps/host-agent` | the **controlled** machine (shares its screen, injects input) | `host-agent_0.1.0_x64-setup.exe` |
| `apps/controller-app` | the **support** machine (views + controls) | `controller-app_0.1.0_x64-setup.exe` |

## Building installers

From either app directory:

```sh
npx tauri build
```

Outputs land in `src-tauri/target/release/bundle/`:
- `nsis/*_x64-setup.exe` — the recommended installer (per-user, no admin needed)
- `msi/*_x64_en-US.msi` — MSI for GPO / managed deployment

Ready-to-ship copies live in `dist/`:
- `dist/host/` — host installer + `HOST-SETUP.md`
- `dist/machine2/` — controller installer + `MACHINE2-SETUP.md`

## Autostart (unattended host)

Built in: the host's config screen has **"Start UpDesk when I sign in"**. It
writes an HKCU `Run` entry (no admin) via `tauri-plugin-autostart`, so a machine
left signed-in comes back online after a reboot.

> **Why login-autostart and not a Windows service:** the host needs an
> interactive desktop session to capture the screen (`getDisplayMedia`) and to
> show the consent prompt. A session-0 service can't do either, so
> launch-at-login is the correct model for unattended access.

## Code signing (removes the SmartScreen warning)

Unsigned installers trigger "Windows protected your PC". To sign, get an
Authenticode certificate (OV or, to skip SmartScreen reputation warm-up, EV)
and point Tauri at it. In each app's `src-tauri/tauri.conf.json`:

```json
"bundle": {
  "windows": {
    "certificateThumbprint": "<SHA1 THUMBPRINT OF YOUR CERT IN THE CERT STORE>",
    "digestAlgorithm": "sha256",
    "timestampUrl": "http://timestamp.digicert.com"
  }
}
```

Then `npx tauri build` signs automatically. For a `.pfx` on disk instead of the
cert store, set env before building:

```sh
export TAURI_SIGNING_PRIVATE_KEY=...      # updater key (see below), not the code cert
# code signing uses the thumbprint above + signtool on PATH
```

(The cert itself is never committed — it's a machine/CI secret.)

## Auto-update (optional, not yet wired)

Deferred until there's a place to host update files. To enable later:

1. Add the plugin: `tauri-plugin-updater` (Rust) + init in `lib.rs`.
2. Generate the updater signing keypair:
   ```sh
   npx tauri signer generate -w updater.key
   ```
   Keep `updater.key` secret; put its **public** half in `tauri.conf.json`:
   ```json
   "plugins": { "updater": { "pubkey": "<PUBLIC KEY>", "endpoints": [
     "https://<your-host>/updesk/{{target}}/{{current_version}}"
   ] } }
   ```
3. On release, `npx tauri build` produces a signed `.sig` alongside the
   installer. Publish a `latest.json` manifest + the signed installer to the
   endpoint (GitHub Releases works). Clients check on launch and self-update.

Until then, distribute new installers manually from `dist/`.
