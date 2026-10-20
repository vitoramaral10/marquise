"""Draws Marquise's art (Pillow): the app icon, the TV banner and the repository images.

The mark is a cinema marquee: a warm board ringed with lit bulbs and a big "M".
Usage: python3 tools/make_art.py
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/assets/fonts/InterVariable.ttf")
TOP, BOTTOM = (122, 24, 48), (40, 14, 46)        # velvet red -> plum, the color of a theater
BULB, GLOW = (255, 214, 140), (255, 170, 70)     # warm incandescent
CREAM = (255, 244, 226)


def vertical(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line((0, y, w, y), fill=tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img


def font(px, weight=800):
    f = ImageFont.truetype(FONT, px)
    try:
        values = []
        for a in f.get_variation_axes():
            name = a["name"] if isinstance(a["name"], str) else a["name"].decode()
            values.append(weight if "eight" in name else 32 if "ptical" in name else a.get("default", 0))
        f.set_variation_by_axes(values)
    except Exception:
        pass
    return f


def bulbs(layer, glow, box, count_x, count_y, r):
    """Bulbs evenly spaced around the rectangle `box`, each with a soft halo drawn on `glow`."""
    x0, y0, x1, y1 = box
    points = []
    for i in range(count_x):
        x = x0 + (x1 - x0) * i / (count_x - 1)
        points += [(x, y0), (x, y1)]
    for j in range(1, count_y - 1):
        y = y0 + (y1 - y0) * j / (count_y - 1)
        points += [(x0, y), (x1, y)]
    d, g = ImageDraw.Draw(layer), ImageDraw.Draw(glow)
    for x, y in points:
        g.ellipse((x - r * 2.6, y - r * 2.6, x + r * 2.6, y + r * 2.6), fill=GLOW + (150,))
        d.ellipse((x - r, y - r, x + r, y + r), fill=BULB + (255,))
        d.ellipse((x - r * 0.45, y - r * 0.55, x + r * 0.15, y - r * 0.05), fill=(255, 252, 240, 255))


def mark(size, bg=True):
    """The icon: the marquee on its rounded board (bg) or the board alone on transparency."""
    s = size * 4
    img = vertical(s, s, TOP, BOTTOM).convert("RGBA") if bg else Image.new("RGBA", (s, s), (0, 0, 0, 0))
    if bg:
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.23), fill=255)
        img.putalpha(mask)
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    glow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    inset = s * 0.17
    bulbs(layer, glow, (inset, inset, s - inset, s - inset), 7, 7, s * 0.03)
    # The "M", heavy and warm, centered on the board.
    d = ImageDraw.Draw(layer)
    f = font(int(s * 0.46), 850)
    box = d.textbbox((0, 0), "M", font=f)
    x = (s - (box[2] - box[0])) / 2 - box[0]
    y = (s - (box[3] - box[1])) / 2 - box[1]
    shade = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(shade).text((x, y + s * 0.012), "M", font=f, fill=(20, 4, 12, 150))
    img.alpha_composite(shade.filter(ImageFilter.GaussianBlur(s * 0.012)))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(s * 0.018)))
    d.text((x, y), "M", font=f, fill=CREAM + (255,))
    img.alpha_composite(layer)
    if bg:   # keep the rounded corners after compositing
        img.putalpha(Image.composite(img.getchannel("A"), Image.new("L", (s, s), 0), mask))
    return img.resize((size, size), Image.LANCZOS)


def banner(w=640, h=360, scale=4):
    """The TV banner (and, bigger, the repository's social preview): icon + wordmark on the velvet gradient."""
    s = scale
    img = vertical(w * s, h * s, TOP, BOTTOM).convert("RGBA")
    m = mark(int(h * 0.56 * s))
    d = ImageDraw.Draw(img)
    f = font(int(h * 0.19 * s), 760)
    text = "Marquise"
    box = d.textbbox((0, 0), text, font=f)
    gap = int(w * 0.035 * s)
    total = m.width + gap + (box[2] - box[0])
    x = (w * s - total) // 2
    img.alpha_composite(m, (x, (h * s - m.height) // 2))
    y = (h * s - (box[3] - box[1])) // 2 - box[1]
    d.text((x + m.width + gap - box[0], y), text, font=f, fill=CREAM + (255,))
    return img.resize((w, h), Image.LANCZOS)


if __name__ == "__main__":
    res = os.path.join(ROOT, "app/res/drawable-nodpi")
    os.makedirs(res, exist_ok=True)
    mark(192).save(os.path.join(res, "icon.png"))
    mark(512).save(os.path.join(res, "logo.png"))   # the settings screen's big mark
    banner().save(os.path.join(res, "banner.png"))
    gh = os.path.join(ROOT, ".github")
    os.makedirs(gh, exist_ok=True)
    mark(512).save(os.path.join(gh, "icon.png"))
    banner(1280, 640, 2).convert("RGB").save(os.path.join(gh, "social-preview.png"))
    print("ok")
