# UpDesk — Build & Deploy

How to build, sign, and distribute every component. Machine-specific paths below
match the current dev box; adjust as needed.

Toolchain used on the dev machine:
- JDK 17: `C:\Users\nikhil\AppData\Local\Programs\Eclipse Adoptium\jdk-17.0.11.9-hotspot`
- Gradle 8.11.1 (cached under `~/.gradle/wrapper/dists/gradle-8.11.1-bin/...`)
- Android SDK: `C:\Users\nikhil\AppData\Local\Android\Sdk` (platform-36, build-tools 36.0.0)
- Rust/Tauri toolchain (for the desktop apps + native host)

> RAM note: this box OOMs the Kotlin/R8 daemons on heavy builds. All Android
> builds here pass `-Pkotlin.compiler.execution.strategy=in-process` and keep
> `org.gradle.jvmargs=-Xmx2560m`. The Field **release** (R8) build still OOMs —
> build release APKs on a machine with more RAM, or use the debug APKs for pilots.

---

## Android apps

Each app is a standalone Gradle project. Build the **debug** APK (installable,
debug-signed) or the **release** APK (smaller, arm64-only + R8, needs signing).

```bash
export JAVA_HOME="C:\\Users\\nikhil\\AppData\\Local\\Programs\\Eclipse Adoptium\\jdk-17.0.11.9-hotspot"
GRADLE=~/.gradle/wrapper/dists/gradle-8.11.1-bin/*/gradle-8.11.1/bin/gradle

# from the module dir (apps/android-host, apps/android-field-host):
"$GRADLE" :app:assembleDebug   --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process
"$GRADLE" :app:assembleRelease --no-daemon --console=plain -Pkotlin.compiler.execution.strategy=in-process
```

Outputs: `app/build/outputs/apk/{debug,release}/`.

### Signing a release APK
```bash
BT="C:/Users/nikhil/AppData/Local/Android/Sdk/build-tools/36.0.0"
"$BT/zipalign.exe" -f -p 4 app-release-unsigned.apk aligned.apk
"$BT/apksigner.bat" sign --ks <YOUR-RELEASE.keystore> --ks-pass pass:<PW> \
  --ks-key-alias <ALIAS> --out UpDesk-<app>-release.apk aligned.apk
"$BT/apksigner.bat" verify UpDesk-<app>-release.apk
```

> **Create a real release keystore** (do NOT ship the demo `dist/field/updesk-field.keystore`):
> ```
> keytool -genkeypair -keystore updesk-release.keystore -alias updesk \
>   -keyalg RSA -keysize 2048 -validity 10000
> ```
> Store it + its password OUTSIDE the repo, in a secret manager. Same key must be
> reused for every future update of a given app.

### Current pilot APKs (debug)
- `apps/android-host/app/build/outputs/apk/debug/app-debug.apk` (screen host)
- `dist/field/UpDeskField-debug.apk` (Field host)

### Size
`android-host` debug is ~51 MB (all ABIs, unstripped). To match the other apps
(~15–20 MB), add arm64-only + R8 to `apps/android-host/app/build.gradle.kts`
(copy the block from `apps/android-field-host/app/build.gradle.kts`).

---

## Desktop (Windows)

Both desktop apps are Tauri (Rust + WebView2).

```bash
cd apps/controller-app   # or apps/host-agent
npm install
npm run tauri build      # release exe + MSI + NSIS installer
# outputs: src-tauri/target/release/{app}.exe
#          src-tauri/target/release/bundle/{msi,nsis}/...
```

- **Controller** → install on control-room PCs.
- **Host (host-agent)** → install on PCs to be viewed (attended).

> Rebuild both from current source before handover — the committed binaries may
> predate recent fixes (e.g. the desktop-host cellular-stutter fix in `host.js`).

---

## Windows native host (unattended)

Silent background service, built from `crates/native-host`.

```bash
cargo build --release -p native-host    # -> target/release/native-host.exe
```

Distribute the pre-made bundle: **`dist/UpDesk-NativeHost.zip`** (install.bat,
native-host.exe, Show-ID.bat, uninstall.bat, README). On the target machine:
extract → run `install.bat` (elevates) → `Show-ID.bat` for the ID + password.

> Change the default password (`updesk`) — see SECURITY-HARDENING.md.

---

## Server

Deployed on EC2 (`13.234.64.162`) behind `wss://up-desk.online` (Caddy TLS)
with coturn TURN. See `infra/AWS-DEPLOY.md` + `infra/aws-setup.sh`.

Services (systemd): `updesk-signaling`, `caddy`, `coturn`.
```bash
sudo systemctl status updesk-signaling caddy coturn
```

Redeploy the signaling server after code changes:
```bash
scp target/release/signaling-server ec2:/... ;  # or build on the box
export PATH=$HOME/.cargo/bin:$PATH
cargo build --release -p signaling-server
sudo systemctl restart updesk-signaling
```

### <a name="server"></a>AWS Security Group — REQUIRED inbound rules
| Type | Proto | Port | Source |
|---|---|---|---|
| Custom | TCP | 443 | 0.0.0.0/0 | (wss via Caddy) |
| Custom | TCP | 3478 | 0.0.0.0/0 | (TURN) |
| Custom | UDP | 3478 | 0.0.0.0/0 | (TURN) |
| Custom | **UDP** | **49152–65535** | 0.0.0.0/0 | (**TURN relay range — the one usually missing; fixes relay latency**) |
| Custom | TCP | 22 | your IP | (SSH admin) |

### coturn (`/etc/coturn/turnserver.conf`) — verify on the server
- `external-ip=13.234.64.162` **must be set** (uncommented) or UDP relay advertises a private IP.
- `user=updesk:<REAL-PASSWORD>` matches the apps' `rtcConfig.js` / Android `WebRtcClient`.
- `min-port=49152` / `max-port=65535` present.
```bash
sudo systemctl restart coturn
```
