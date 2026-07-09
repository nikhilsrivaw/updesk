# Knox (managed Samsung) integration — what it needs

Knox is the path to **near-zero-touch** control on department-owned **Samsung**
devices: no "enable Accessibility", no "allow restricted settings" — the input
and screen APIs are granted by the managed profile. It's the polished managed
experience RustDesk offers via OEM add-ons.

**Why there's no code yet:** Knox is a **licensed Samsung SDK** plus a **Samsung
device to test on**. It cannot be written or verified without both. This file is
the procurement + integration checklist so it's ready to build the moment you
have them.

## 1. What you must obtain (business steps, not code)
1. **Samsung Knox partner account** — register at `samsungknox.com` (Knox
   Partner Program). Free to enroll.
2. **A Knox license key** — a **KPE (Knox Platform for Enterprise) Standard**
   key, generated in the Knox console. This is the key your app activates at
   runtime. (Standard KPE covers the RemoteControl/screen APIs.)
3. **The Knox SDK** — download the Knox SDK AAR/JAR from the Samsung developer
   portal (it is NOT on Maven Central; it's gated behind the partner account).
4. **A physical Samsung device** — Knox APIs are Samsung-only and don't run on
   emulators or other brands.

## 2. What Knox gives us (the APIs to use)
- **`RemoteInjection`** (`com.samsung.android.knox.remotecontrol.RemoteInjection`)
  — inject touch/key events **without the Accessibility service** (`injectPointerEvent`,
  `injectKeyEvent`). This replaces `InputAccessibilityService`.
- **Screen capture** — on managed devices the MediaProjection consent can be
  auto-granted via the managed profile, or Knox's own capture path is used.
- **Silent provisioning** — via an EMM/MDM (Knox Manage or any Android Enterprise
  EMM) you pre-install the app, auto-grant permissions, and block uninstall.

## 3. Integration plan (once SDK + license + device are in hand)
1. Add the Knox SDK AAR to `app/libs/` and reference it in `build.gradle.kts`
   (`implementation(files("libs/knoxsdk.aar"))`).
2. Activate the license at app start:
   `KnoxEnterpriseLicenseManager.getInstance(ctx).activateLicense(KEY)`.
3. Add a `KnoxInput` class mirroring `RootInput`/`InputAccessibilityService`,
   backed by `RemoteInjection.injectPointerEvent(...)`.
4. In `WebRtcClient`, add Knox as the **first-choice** input path:
   `Knox → Root → Accessibility` fallback chain (all already isolated behind the
   same `handle(json)` shape).
5. Test on the Samsung device: no Accessibility enable step should be needed.

## 4. Effort estimate (after procurement)
- License activation + `KnoxInput`: ~2–3 days.
- EMM/MDM provisioning flow for true zero-touch: ~1 week (EMM-specific).

## Bottom line
The **code structure is already Knox-ready** — our input is behind a single
`handle(json)` entry point with a fallback chain, so dropping in `KnoxInput` is
small. The blocker is **procurement**: partner account → KPE license → SDK → a
Samsung device. Get those, and Knox is a few days of work.
