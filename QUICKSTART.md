# UpDesk — Quickstart (Milestone A: one-way screen view)

Three processes: the **signaling server** (Rust) plus the **host agent** and
**controller** (Tauri apps). All speak the same Ed25519-authenticated protocol.

## 0. Enrollment codes

`.env` already seeds one-time codes:

```
ENROLL_CODES=DEV0-TEST:device,CTL0-TEST:controller
```

Each code is consumed on first successful enrollment. Mint more with
`node scripts/genEnrollCode.js device` (or `controller`) and add them to `.env`.

## 1. Start the signaling server

```
cargo run -p signaling-server      # from repo root; listens on ws://localhost:8080
```

Optional environment variables (all off by default):

| Var | Effect |
|-----|--------|
| `DATABASE_URL` | Persist enrolled keys + write a session audit log to Postgres. e.g. `postgres://updesk:updesk@localhost:5433/updesk` |
| `TLS_PFX` | Serve `wss://` instead of `ws://`. Point at a PKCS#12 identity, e.g. `certs/identity.pfx` |
| `TLS_PFX_PASSWORD` | Password for the PKCS#12 (blank for the dev cert) |

See **Optional services** below for bringing up Postgres/TURN.

## 2. Start the host agent (machine being controlled)

```
cd apps/host-agent
npm install          # first time only (installs the Tauri CLI)
npm run tauri dev
```

In the window: server `ws://localhost:8080`, Device ID `host-1`, Enroll code
`DEV0-TEST` (first run only) → **Go online**. Status becomes
"online — waiting for a controller".

## 3. Start the controller (support staff)

```
cd apps/controller-app
npm install          # first time only
npm run tauri dev
```

Server `ws://localhost:8080`, Controller ID `sup-1`, Target device `host-1`,
Enroll code `CTL0-TEST` (first run only) → **Connect**.

## 4. Consent + view + control

The host window shows "**sup-1** wants to control this machine" →
**Accept & share screen** → pick a screen/window in the WebView2 picker.
The controller window shows the **live screen**. Click the video to focus it,
then your mouse moves/clicks/scroll and keyboard are injected on the host
(Milestone B). Right-click passes through; `Esc`/arrows/etc. are mapped.

> Input injection uses the OS SendInput path — the host app may need to run
> **as administrator** to send input into elevated windows (UAC).

> After the first run, the enroll-code fields are left blank — each identity
> authenticates by its persisted key (TOFU). Delete the app's IndexedDB (or the
> server's in-memory state on restart) to re-enroll.

## What's built vs. next

- **Done:** signaling server (Rust, verified), Ed25519 TOFU auth, consent flow,
  bidirectional signaling relay, host screen-capture + controller render.
- **Milestone B (done):** input control — controller captures mouse/keyboard,
  sends over the `input` data channel, host injects via `enigo` (the
  `input_event` command in `host-agent/src-tauri/src/lib.rs`).
- **Done:** TLS (`wss://`), Postgres persistence + audit log, TURN config.

## Admin CLI

Manage identities and codes at runtime (needs `ADMIN_TOKEN` in `.env`):

```
node scripts/admin.js identities          # list enrolled devices/controllers
node scripts/admin.js devices             # who's online now
node scripts/admin.js sessions            # audit history (needs DATABASE_URL)
node scripts/admin.js revoke <identityId> # remove an identity (memory + DB) + kick it
node scripts/admin.js mint-code <device|controller>   # new enroll code, no restart
```

`revoke` is the fix for "public key does not match" after re-installing a client:
revoke the old identity, then re-enroll with a minted code.

## Optional services

### Postgres (persistence + audit log)

```
docker compose -f infra/docker-compose.yml up -d postgres   # host port 5433
```
Then start the server with
`DATABASE_URL=postgres://updesk:updesk@localhost:5433/updesk`. Enrolled keys now
survive restarts, and every session is written to the `sessions` table:

```
psql -h localhost -p 5433 -U updesk -d updesk \
  -c "SELECT controller_id, device_id, status, duration_ms FROM sessions;"
```

### TLS (wss://)

A dev cert is in `certs/` (regenerate with the openssl commands in the repo).
Run the server with `TLS_PFX=certs/identity.pfx`, then use `wss://localhost:8080`
in each app's server field. Because the cert is self-signed, WebView2 will
reject it until you import `certs/cert.pem` into **Local Machine → Trusted Root
Certification Authorities** (or use a real cert / Let's Encrypt in production).

### TURN (coturn) — cross-network use

STUN alone covers a LAN. For real remote support across NATs, run coturn on a
publicly reachable host (a cloud VM), edit `infra/coturn/turnserver.conf`
(set `user`, `realm`, `external-ip`), and add the matching `turn:`/`turns:`
entry in `apps/*/src/rtcConfig.js`.

## Installers

Tauri builds native Windows installers (NSIS `.exe` auto-downloads; MSI needs
WiX 3):

```
cd apps/host-agent && npm install && npm run tauri build
cd apps/controller-app && npm install && npm run tauri build
```
Artifacts land in `apps/<app>/src-tauri/target/release/bundle/`.
