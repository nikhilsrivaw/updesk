#!/usr/bin/env python3
"""UpDesk branding — generates all app icons from code (Pillow).

Marks (flat, white glyph on a brand-colour field):
  * monitor  : a screen with an up-chevron + stand  -> "Up" + desk  (hosts/controller)
  * field    : a location pin (donut) with a lens dot -> camera + GPS (UpDesk Field)

Outputs:
  * Android adaptive-icon foregrounds (transparent PNGs) into each app's mipmap dirs
  * Tauri desktop icon sets (square PNGs + multi-size .ico) for controller + host
  * A master logo PNG + .ico under infra/branding/out/ for docs / the native host

Run:  python infra/branding/make-icons.py
Requires: Pillow
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SS = 4  # supersample factor for smooth (anti-aliased) edges
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)

BRAND = {
    "blue":   (37, 99, 235),    # #2563EB  hosts / controller / desktop
    "green":  (22, 163, 74),    # #16A34A  Field
    "indigo": (79, 70, 229),    # #4F46E5  native (parked) host
}


def _draw_monitor(d, S, col):
    # screen (rounded-rect outline), up-chevron inside, stand below
    w = max(2, int(S * 0.055))
    d.rounded_rectangle([S*0.16, S*0.14, S*0.84, S*0.60], radius=S*0.09,
                        outline=col, width=w)
    d.line([(S*0.34, S*0.47), (S*0.50, S*0.29), (S*0.66, S*0.47)],
           fill=col, width=int(S*0.075), joint="curve")
    d.line([(S*0.50, S*0.60), (S*0.50, S*0.71)], fill=col, width=w)
    d.line([(S*0.35, S*0.73), (S*0.65, S*0.73)], fill=col, width=int(S*0.06))


def _draw_field(d, S, col):
    # location pin (teardrop) with a transparent lens hole = donut pin
    cx, top, bot = S*0.50, S*0.12, S*0.86
    r = S*0.24
    d.ellipse([cx-r, top, cx+r, top+2*r], fill=col)                 # head
    d.polygon([(cx-r*0.86, top+r*1.15), (cx+r*0.86, top+r*1.15),
               (cx, bot)], fill=col)                                # point
    hr = S*0.105
    d.ellipse([cx-hr, top+r-hr, cx+hr, top+r+hr], fill=CLEAR)       # lens hole


GLYPHS = {"monitor": _draw_monitor, "field": _draw_field}


def glyph_png(size, glyph, col=WHITE):
    """Transparent square with the white glyph centred (adaptive-icon safe zone)."""
    S = size * SS
    img = Image.new("RGBA", (S, S), CLEAR)
    # draw the glyph into an inner box (~62% of canvas) then paste centred
    g = int(S * 0.62)
    sub = Image.new("RGBA", (g, g), CLEAR)
    GLYPHS[glyph](ImageDraw.Draw(sub), g, col)
    img.alpha_composite(sub, (int((S-g)/2), int((S-g)/2)))
    return img.resize((size, size), Image.LANCZOS)


def full_icon(size, bg, glyph):
    """Square app icon: rounded brand field + white glyph (desktop/Windows)."""
    S = size * SS
    img = Image.new("RGBA", (S, S), CLEAR)
    d = ImageDraw.Draw(img)
    m = int(S * 0.03)
    d.rounded_rectangle([m, m, S-m, S-m], radius=int(S*0.22), fill=bg + (255,))
    g = int(S * 0.52)
    sub = Image.new("RGBA", (g, g), CLEAR)
    GLYPHS[glyph](ImageDraw.Draw(sub), g, WHITE)
    img.alpha_composite(sub, (int((S-g)/2), int((S-g)/2)))
    return img.resize((size, size), Image.LANCZOS)


def ensure(p):
    os.makedirs(p, exist_ok=True)
    return p


# Adaptive-icon foreground densities (px), foreground is 108dp.
FG_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# Android apps: module res dir -> (glyph, brand)
ANDROID = {
    "apps/android-host/app/src/main/res":        ("monitor", "blue"),
    "apps/android-field-host/app/src/main/res":  ("field",   "green"),
    "apps/android-host-native/app/src/main/res": ("monitor", "indigo"),
}

def build_android():
    for res, (glyph, brand) in ANDROID.items():
        base = os.path.join(ROOT, res)
        if not os.path.isdir(os.path.dirname(base)):
            continue
        for dens, px in FG_DENSITIES.items():
            out = ensure(os.path.join(base, f"mipmap-{dens}"))
            glyph_png(px, glyph).save(os.path.join(out, "ic_launcher_fg.png"))
        print(f"android icons -> {res}  ({glyph}/{brand})")


# Tauri icon set (square, full-bleed brand).
TAURI = {
    "apps/controller-app/src-tauri/icons": ("monitor", "blue"),
    "apps/host-agent/src-tauri/icons":     ("monitor", "blue"),
}
TAURI_PNGS = {"32x32.png": 32, "128x128.png": 128, "128x128@2x.png": 256, "icon.png": 512}
STORE = {"Square107x107Logo.png": 107, "Square142x142Logo.png": 142,
         "Square150x150Logo.png": 150, "Square284x284Logo.png": 284,
         "Square30x30Logo.png": 30, "Square44x44Logo.png": 44,
         "Square71x71Logo.png": 71, "Square89x89Logo.png": 89,
         "StoreLogo.png": 50}

def build_tauri():
    for rel, (glyph, brand) in TAURI.items():
        d = ensure(os.path.join(ROOT, rel))
        bg = BRAND[brand]
        for name, sz in {**TAURI_PNGS, **STORE}.items():
            full_icon(sz, bg, glyph).save(os.path.join(d, name))
        # multi-size .ico
        full_icon(256, bg, glyph).save(
            os.path.join(d, "icon.ico"),
            sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
        print(f"tauri icons  -> {rel}")


def build_master():
    out = ensure(os.path.join(ROOT, "infra", "branding", "out"))
    full_icon(512, BRAND["blue"], "monitor").save(os.path.join(out, "updesk.png"))
    full_icon(512, BRAND["green"], "field").save(os.path.join(out, "updesk-field.png"))
    full_icon(256, BRAND["blue"], "monitor").save(
        os.path.join(out, "updesk.ico"),
        sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print(f"master logos -> infra/branding/out/")


if __name__ == "__main__":
    build_android()
    build_tauri()
    build_master()
    print("done.")
