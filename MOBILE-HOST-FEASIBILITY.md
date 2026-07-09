# UpDesk — Mobile host feasibility (for the cyber-forensics use case)

**Question from the stakeholder:** can we install a "host" app on a phone so an
officer's controller can access/control it — ideally without exchanging an ID/PIN
each time?

**Short answer:**
- **Android host: partially yes** — but *not* as a plain "install APK → silent full
  control." It needs the phone's cooperation (a visible one-time setup, or a
  managed/rooted device). On devices you **manage** or **physically hold under
  warrant**, this is achievable and legitimate.
- **iOS host: effectively no** — Apple does not let any third-party app remotely
  control the device. View-only screen broadcast (with a visible indicator) is the
  ceiling.
- **Mobile controller (officer's side): easy** — that's the high-value, low-risk
  win.

This is not a limitation of our code — it's the Android/iOS security model, which
deliberately stops one app from silently watching and driving the whole device.
Legitimate tools (TeamViewer, AnyDesk) hit the exact same walls and solve them the
ways below.

---

## What an Android host actually needs

A host has to do two things: **see the screen** and **inject input**. Android
gates both.

### 1. Screen capture — `MediaProjection`
- The only third-party API. Starting it shows a **system consent dialog** and a
  persistent status indicator. Once granted, a foreground service can keep it
  running, but the **first grant is a visible user tap** — there is no silent
  bypass on stock Android (Android 14 tightened this further).
- **Secure surfaces are blocked:** password fields, banking/DRM apps that set
  `FLAG_SECURE`, and the lock screen render **black** in the capture. This is
  non-negotiable at the OS level.

### 2. Input injection (the hard part) — ranked by what it takes
Global input injection (`INJECT_EVENTS`) is **system-signature only** — a normal
app cannot do it. The realistic options:

| Method | Control level | What it requires | Silent? |
|--------|---------------|------------------|---------|
| **Accessibility Service** | Taps, swipes, text into most apps (not secure fields) | User enables it once in Settings (visible) | No — visible setup |
| **OEM Add-on** (Samsung/Xiaomi/etc.) | Full input | A manufacturer-signed plugin (this is how TeamViewer controls Samsung phones) | Semi — needs the OEM add-on installed |
| **Root** | Full input via `/dev/uinput` | Device must be rooted | Yes, once rooted |
| **Device Owner / MDM** | Provisioning, auto-start, some perms | Enroll device in Android Enterprise before use | Partial — still can't silently grant MediaProjection on stock AOSP |
| **System app (OEM pre-install)** | Everything | Manufacturer bakes it into the firmware | Yes — not realistic for you |

**Takeaway:** the standard, buildable path is **Accessibility Service +
MediaProjection**, both of which involve a **visible one-time setup on the phone**.
"Silent from a bare APK" only exists on **rooted** or **OEM-cooperating** or
**system-signed** devices.

---

## Mapping to your two real scenarios

### A. Devices you own / manage (MDM — Android Enterprise)
**Most workable.** Enroll the phone as a managed device (Device Owner) before it's
issued. You can then:
- Pre-install the host, auto-start it, pre-grant most runtime permissions,
  suppress uninstall.
- Still need the one-time MediaProjection/accessibility acceptance during
  provisioning — but *you* do that at setup, so day-to-day it's effectively
  unattended.
- **This is the clean, legitimate "install and it's accessible" path.**

### B. Devices in custody (warrant-backed, physical possession)
**Also workable, because you hold the unlocked device.** With the phone in hand
you can, in one sitting: install the host, enable the accessibility service, accept
screen capture, (optionally root for full/silent control) — then access it remotely
for the duration of the examination. Physical possession legitimately removes the
"consent dialog" obstacle.

> For both A and B, the enabling factor is **authority over the device** (you own
> it, or lawfully hold it) — not defeating Android's protections covertly.

---

## iOS host — the honest limit
- **No third-party remote control exists.** iOS has no input-injection API for
  outside apps. MDM can lock/wipe/inspect a supervised device but **cannot live-
  drive the touchscreen**.
- **Screen view only:** ReplayKit can broadcast the screen, but the user starts it
  from Control Center and a **red recording indicator** stays visible. No silent,
  no control.
- **Recommendation:** don't promise an iOS host. Offer iOS only as a *controller*.

---

## The line we keep (so this stays sellable and lawful)
The host must be a **transparent, enrolled agent** — a clear "this device is under
remote access" indicator, installed with authority over the device (managed, or
warrant-held). That's a legitimate RMM/forensics tool. What we won't build is a
**hidden, undetectable** remote-access app for phones people don't know is there —
that's stalkerware, and it also gets an app permanently banned from every store and
flagged by every AV. The good news: your two use cases (managed + custody) don't
need covert behavior to work.

---

## What we can build, and rough effort

| Deliverable | Feasibility | Effort (solo) |
|-------------|-------------|---------------|
| **Android controller** (officer views/controls a desktop host) | Straightforward | ~1–2 weeks |
| **Android host — attended** (accessibility + MediaProjection, visible setup) | Doable | ~4–8 weeks |
| **Android host — managed/unattended** (MDM/Device Owner provisioning) | Doable, OEM-dependent | +several weeks, per-OEM tuning |
| **Android host — rooted (full silent control)** | Doable on rooted devices only | ~3–5 weeks on top of attended |
| **iOS controller** | Doable | ~2–3 weeks |
| **iOS host (control)** | Not possible | — |
| **iOS host (view-only broadcast)** | Marginal, visible | ~2 weeks, low value |

---

## Recommended sequence
1. **Android controller first** — real value, low risk, lets officers connect from
   the field to desktop hosts we already support.
2. **Android host (attended)** — accessibility + MediaProjection, honest about the
   one-time on-device setup.
3. **MDM/managed provisioning** — for department-owned phones, to make it
   effectively unattended within their fleet.
4. **Set expectations on iOS** up front: controller yes, full host no.

## One thing to confirm with them
Ask whether their target phones are **department-managed (can be MDM-enrolled)** or
**arbitrary seized devices**. That single answer decides whether "install and it's
accessible" is realistic (managed) vs. "needs hands-on setup per device" (custody),
and keeps you from promising silent control that stock Android won't allow.
