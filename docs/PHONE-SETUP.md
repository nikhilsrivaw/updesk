# UpDesk — Phone Provisioning Sheet

Print/follow this when setting up a field or host phone.

---

## A. UpDesk Field (camera + mic + GPS, runs 24/7)

Install: copy `UpDeskField-debug.apk` to the phone and tap it (allow "install from
unknown sources"), or `adb install -r UpDeskField-debug.apk`.

Open the app, then grant these **once** (the app prompts on **Go online**):
1. **Camera, Microphone, Location** — when asked, choose **Allow**; for location
   pick **"Allow all the time"**.
2. **Disable battery optimization** — tap the button / accept the prompt. *(Critical
   — without this the OS suspends it after inactivity.)*
3. **Allow display over other apps** — tap the button / toggle it on. *(Lets it
   start the camera unattended when a controller connects from a cold background.)*
4. **OEM Autostart** (Xiaomi/Oppo/Vivo/Realme/Samsung only): open Settings and
   enable **Autostart / auto-launch** for UpDesk Field. *(No app can do this itself;
   required for boot-start on these brands.)*

Then tap **Go online (run 24/7)**. Note the **ID + password** shown — give them to
the control room. They stay the same forever.

Behaviour after setup:
- Survives being swiped from Recents, screen sleep/Doze, and reboot.
- After a **full power-off**, someone must **unlock the phone once** (Android
  encryption rule) — then it reconnects on its own, no tapping "Go online".
- **Stop** takes it offline deliberately (stays off across reboots until re-enabled).

Keep the phone on **power** for a stationary 24/7 device.

> Honest limits: the mandatory foreground-service notification can't be fully
> removed (set to minimum importance — no status-bar icon). The camera/mic "in use"
> green dot can't be hidden without enrolling the phone as **Device Owner (MDM)**.

---

## B. UpDesk Host (screen share + remote control, attended)

Install `apps/android-host/.../app-debug.apk`.

1. Open the app → **Enable remote control** → turn on **UpDesk** in Accessibility
   settings (needed for the controller to tap/type; without it, sessions are
   view-only). *Re-do this after every reinstall — it gets disabled.*
2. (Optional) **Enable file access**, **Use root input** (custody devices only).
3. Tap **Go online**, share the **ID + PIN** with the control room.
4. When a controller connects, tap **Start now** on the screen-capture prompt.

Tuned for Wi-Fi: sharp (H.264) + smooth (up to 60fps). Over mobile data it stays
smooth by auto-reducing quality.

---

## C. Verify it works
From the control room, connect with the ID + password/PIN. For Field, confirm the
Field Monitor shows camera + audio + a moving map. For the screen host, confirm you
can see the screen and (after enabling Accessibility) tap on it.
