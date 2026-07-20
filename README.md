# UpDesk

**A secure, peer-to-peer remote desktop system with an unattended native host — written predominantly in Rust.**

UpDesk lets one machine (the **controller**) see and control another (the **host**)
over a **direct, end-to-end encrypted WebRTC connection**. Screen frames and input
events travel straight between the two peers; a small signaling server only
introduces them and never sees the session content. A separate **native host**
captures the screen with no window, no gesture and no picker, installs as a
**Windows service**, and is reachable from boot — including before anyone has
logged in.

---

## Why UpDesk

Existing remote desktop tools force a compromise:

| | Reachable through NAT | End-to-end encrypted | Open / auditable | Unattended from boot |
|---|:---:|:---:|:---:|:---:|
| VNC | ✗ | optional | ✓ | ✗ |
| Microsoft RDP | via gateway | ✓ | ✗ | ✓ |
| TeamViewer / AnyDesk | ✓ | claimed | ✗ | ✓ |
| **UpDesk** | ✓ | ✓ | ✓ | ✓ |

UpDesk separates the two hard problems and solves each with a standard answer:

- **Reachability** → WebRTC's ICE framework (STUN for discovery, TURN as fallback relay).
- **Trust** → media keys are negotiated **directly between the peers** (DTLS-SRTP),
  so the signaling server is *structurally unable* to decrypt what it helps arrange.

Every endpoint is identified by an **Ed25519 public key** and proves itself with a
per-connection **challenge–response**, with enrollment gated by single-use codes.

---

## Architecture

```
        ┌──────────────────────────────┐
        │   Signaling Server (Rust)     │   authenticates + relays SDP/ICE
        │   ws:// or wss://             │   — never touches media keys
        └──────┬────────────────┬───────┘
        auth + │                │ auth +
        SDP/ICE│  (dashed)      │ register
        ┌──────▼──────┐   ┌─────▼───────┐
        │ Controller  │   │    Host     │
        │ (Tauri app) │   │ native / Tauri │
        └──────┬──────┘   └─────┬───────┘
               │  direct WebRTC │
               └────────────────┘
          DTLS-SRTP media + control  (peer-to-peer)
```

Once the peer connection is live, the server can disappear without dropping the
session.

---

## Repository layout

| Path | Contents |
|---|---|
| `crates/signaling-server` | Rust authentication + rendezvous server (Tokio, WebSocket/WSS). |
| `crates/native-host` | Headless host: silent screen capture → H.264 → WebRTC, plus native input injection. |
| `crates/host-service` | Windows service that runs the host from boot and keeps it in the active session. |
| `apps/host-agent` | Attended Tauri host (WebView2 + `getDisplayMedia`). |
| `apps/controller-app` | Tauri controller application. |
| `apps/android-host`, `apps/android-controller` | Exploratory mobile clients. |
| `src/`, `shared/` | Node.js signaling logic and shared client used in the early milestones. |
| `scripts/` | Admin CLI, enrollment-code minting, dev helpers. |
| `infra/` | Docker Compose (Postgres), coturn (TURN) config. |
| `vendor/webrtc-dtls` | Patched DTLS crate — fixes ECDHE curve negotiation against modern Chrome. |
| `certs/` | TLS dev certificates *(gitignored)*. |
| `report/` | Project report — final `.docx`, LaTeX source, and diagrams. |

---

## Prerequisites

- **Rust** (stable, 2021 edition) — <https://rustup.rs>
- **Node.js** 18+ and npm
- **Windows 10/11** for the native host and service (host capture is Windows-specific)
- *(optional)* **Docker** — for Postgres persistence and the TURN relay
- *(optional)* **WiX 3** — only if you want MSI installers

---

## Quick start (local, two windows on one LAN)

Three processes speak the same Ed25519-authenticated protocol: the **signaling
server**, the **host**, and the **controller**.

### 0. Enrollment codes

`.env` seeds one-time codes (create `.env` from the keys `PORT`, `ENROLL_CODES`,
`ADMIN_TOKEN`):

```
ENROLL_CODES=DEV0-TEST:device,CTL0-TEST:controller
```

Each code is consumed on first successful enrollment. Mint more with:

```bash
node scripts/genEnrollCode.js device        # or: controller
```

### 1. Start the signaling server

```bash
cargo run -p signaling-server      # listens on ws://localhost:8080
```

Optional environment variables (all off by default):

| Var | Effect |
|---|---|
| `DATABASE_URL` | Persist enrolled keys + write a session audit log to Postgres. |
| `TLS_PFX` | Serve `wss://` instead of `ws://` (point at a PKCS#12 identity). |
| `TLS_PFX_PASSWORD` | Password for the PKCS#12 (blank for the dev cert). |

### 2. Start the host (machine being controlled)

```bash
cd apps/host-agent
npm install          # first time only
npm run tauri dev
```

In the window: server `ws://localhost:8080`, Device ID `host-1`, Enroll code
`DEV0-TEST` (first run only) → **Go online**.

### 3. Start the controller (support side)

```bash
cd apps/controller-app
npm install          # first time only
npm run tauri dev
```

Server `ws://localhost:8080`, Controller ID `sup-1`, Target device `host-1`,
Enroll code `CTL0-TEST` (first run only) → **Connect**.

### 4. Consent, view, control

The host shows "**sup-1** wants to control this machine" → **Accept & share
screen**. The controller then shows the **live screen**; click the video to focus
it, and your mouse and keyboard are injected on the host.

> Input injection uses the OS `SendInput` path — run the host **as
> administrator** to send input into elevated (UAC) windows.

---

## Unattended native host (silent, from boot)

The native host removes the browser from the loop: it captures the framebuffer
directly (Desktop Duplication API via `scrap`), encodes to H.264 (OpenH264),
publishes a native WebRTC track (`webrtc-rs`), and injects input — all with **no
window and no picker**.

```bash
# Build and run the native host
cargo run -p native-host

# Show this host's 9-digit connect ID + unattended password
cargo run -p native-host -- id
```

Install it as a Windows service so it survives reboot and session switching:

```bash
cargo build -p host-service --release
host-service.exe install
sc start UpDeskHost
```

See [`SILENT-HOST-PLAN.md`](SILENT-HOST-PLAN.md) and
[`SERVICE-MODE.md`](SERVICE-MODE.md) for the design and phase breakdown.

---

## Admin CLI

Manage identities and codes at runtime (needs `ADMIN_TOKEN` in `.env`):

```bash
node scripts/admin.js identities          # list enrolled devices/controllers
node scripts/admin.js devices             # who's online now
node scripts/admin.js sessions            # audit history (needs DATABASE_URL)
node scripts/admin.js revoke <identityId> # remove an identity + kick it
node scripts/admin.js mint-code <device|controller>
```

`revoke` fixes "public key does not match" after reinstalling a client: revoke
the old identity, then re-enroll with a minted code.

---

## Optional services

**Postgres (persistence + audit log)**

```bash
docker compose -f infra/docker-compose.yml up -d postgres    # host port 5433
```
Start the server with `DATABASE_URL=postgres://updesk:updesk@localhost:5433/updesk`.

**TLS (`wss://`)** — a dev cert lives in `certs/`. Run the server with
`TLS_PFX=certs/identity.pfx` and use `wss://localhost:8080` in each app. Import
`certs/cert.pem` into *Local Machine → Trusted Root Certification Authorities*
(the dev cert is self-signed), or use a real certificate in production.

**TURN (coturn)** — STUN alone covers a LAN. For cross-NAT use, run coturn on a
publicly reachable host, edit `infra/coturn/turnserver.conf`, and add the
matching `turn:`/`turns:` entry in `apps/*/src/rtcConfig.js`.

---

## Building installers

```bash
cd apps/host-agent && npm install && npm run tauri build
cd apps/controller-app && npm install && npm run tauri build
```
Artifacts land in `apps/<app>/src-tauri/target/release/bundle/`.

---

## Security model

- **Identity is a key, not a password.** Each peer is an Ed25519 public key; the
  private half never leaves the machine.
- **Enrollment is closed.** A key is accepted only in exchange for a single-use code.
- **Freshness defeats replay.** Every connection is authenticated over a fresh
  random nonce.
- **The server cannot read the session.** DTLS-SRTP keys are negotiated peer-to-peer.
- **Unattended acceptance is host-verified.** Auto-accept is gated by a password
  the host checks itself — the server never sees it.

---

## Documentation

- [`report/`](report/) — full project report (`.docx` + LaTeX + diagrams)
- [`QUICKSTART.md`](QUICKSTART.md) — the original milestone walkthrough
- [`SILENT-HOST-PLAN.md`](SILENT-HOST-PLAN.md) — native host design + phases
- [`SERVICE-MODE.md`](SERVICE-MODE.md) — Windows service / secure-desktop notes
- [`DISTRIBUTION.md`](DISTRIBUTION.md) — packaging notes
- [`MOBILE-HOST-FEASIBILITY.md`](MOBILE-HOST-FEASIBILITY.md) — mobile host study

---

## Status

Working: signaling server (Rust, Ed25519 auth, consent flow, SDP/ICE relay),
attended host + controller with live screen and bidirectional input, TLS,
Postgres persistence + audit log, TURN config, and the native host through silent
capture, H.264 encode, native WebRTC, input injection, and Windows-service mode.
See the report and the plan docs for what remains (notably full secure-desktop
capture and hardware-accelerated low-latency encoding).

---

## License

See repository for license terms.
