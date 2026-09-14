#!/usr/bin/env python3
"""Generate Android launcher + notification icons from HotShare branding art.

Source (branding/):  primary-logo.png (white glyph, transparent), shield-icon.png
(white shield glyph, transparent), wordmark.png.
Outputs into app/src/main/res/.
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
BRAND = os.path.join(ROOT, "branding")

NAVY = (10, 16, 32, 255)          # #0A1020
DENSITIES = {                     # dir suffix: (launcher px, foreground px, notif px)
    "mdpi": (48, 108, 24),
    "hdpi": (72, 162, 36),
    "xhdpi": (96, 216, 48),
    "xxhdpi": (144, 324, 72),
    "xxxhdpi": (192, 432, 96),
}


def load_glyph(path):
    im = Image.open(path).convert("RGBA")
    bbox = im.getchannel("A").getbbox()
    if bbox:
        im = im.crop(bbox)
    return im


def fit(im, box):
    """Scale image to fit inside a box (box, box) preserving aspect."""
    w, h = im.size
    s = min(box / w, box / h)
    return im.resize((max(1, int(w * s)), max(1, int(h * s))), Image.LANCZOS)


def rounded_bg(size, radius_ratio=0.22):
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    r = int(size * radius_ratio)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=NAVY)
    return bg


def circle_bg(size):
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(bg)
    d.ellipse([0, 0, size - 1, size - 1], fill=NAVY)
    return bg


def paste_center(base, glyph, frac):
    size = base.size[0]
    g = fit(glyph, int(size * frac))
    base.alpha_composite(g, ((size - g.size[0]) // 2, (size - g.size[1]) // 2))
    return base


def white_glyph(im):
    """Force solid white, keep alpha (Android tints the notification icon white)."""
    im = im.copy()
    px = im.load()
    for y in range(im.size[1]):
        for x in range(im.size[0]):
            r, g, b, a = px[x, y]
            px[x, y] = (255, 255, 255, a)
    return im


def main():
    logo = load_glyph(os.path.join(BRAND, "primary-logo.png"))
    shield = load_glyph(os.path.join(BRAND, "shield-icon.png"))

    for d, (icon, fg, notif) in DENSITIES.items():
        mip = os.path.join(RES, f"mipmap-{d}")
        drw = os.path.join(RES, f"drawable-{d}")
        os.makedirs(mip, exist_ok=True)
        os.makedirs(drw, exist_ok=True)

        # Legacy square + round launcher (navy bg + glyph).
        paste_center(rounded_bg(icon), logo, 0.62).save(os.path.join(mip, "ic_launcher.png"))
        paste_center(circle_bg(icon), logo, 0.56).save(os.path.join(mip, "ic_launcher_round.png"))

        # Adaptive foreground: transparent, glyph inside the safe zone.
        fgi = Image.new("RGBA", (fg, fg), (0, 0, 0, 0))
        paste_center(fgi, logo, 0.52).save(os.path.join(mip, "ic_launcher_foreground.png"))

        # Notification small icon: solid white shield, transparent bg.
        n = Image.new("RGBA", (notif, notif), (0, 0, 0, 0))
        paste_center(n, white_glyph(shield), 0.94).save(os.path.join(drw, "ic_stat_hotshare.png"))

    # Adaptive icon XML (API 26+).
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    adaptive = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n'
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w") as f:
            f.write(adaptive)

    # Colors.
    values = os.path.join(RES, "values")
    os.makedirs(values, exist_ok=True)
    with open(os.path.join(values, "colors.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources>\n'
            '    <color name="ic_launcher_background">#0A1020</color>\n'
            '    <color name="hotshare_accent">#0E9F6E</color>\n'
            '</resources>\n'
        )
    print("icons generated into", RES)


if __name__ == "__main__":
    main()
