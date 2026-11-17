"""Four more Home wallpapers, 1920x1080, drawn like the Damascene set: the subject on the right, the left darker for
the clock and the verse, a gold patterned band top and bottom.

  madinah  the Prophet's Mosque at night: the Green Dome and its minarets
  kaaba    the Kaaba with its gold band in the lit Haram, minarets around
  aqsa     the Dome of the Rock at dusk, tiled drum and golden dome, olive trees
  andalus  an Andalusian arcade: horseshoe arches with red and white voussoirs, tile band

    python noor_wallpapers2.py <out-folder>
"""
import math
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

S = 2
W, H = 1920 * S, 1080 * S
GOLD = (214, 172, 92)


def gradient(stops):
    ys = np.linspace(0, 1, H)
    cols = np.zeros((H, 3))
    for c in range(3):
        cols[:, c] = np.interp(ys, [s[0] for s in stops], [s[1][c] for s in stops])
    return np.repeat(cols[:, None, :], W, axis=1)


def glow(img, center, color, radius, strength=0.5):
    cx, cy = center[0] * S, center[1] * S
    yy, xx = np.ogrid[:H, :W]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / (radius * S)
    a = np.clip(1 - d, 0, 1) ** 2 * strength
    for c in range(3):
        img[:, :, c] = img[:, :, c] * (1 - a) + color[c] * a
    return img


def left_shade(arr, amount=110):
    xs = np.linspace(1, 0, W)
    a = np.clip(xs * 1.6 - 0.55, 0, 1) * (amount / 255)
    return arr * (1 - a[None, :, None])


def star8(cx, cy, r):
    pts = []
    for i in range(16):
        a = math.pi / 8 * i - math.pi / 2
        rr = r if i % 2 == 0 else r * 0.62
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


def band(img, y0, y1, fill, flip=False):
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    d.rectangle([0, y0, W, y1], fill=fill)
    edge = y0 if flip else y1
    d.line([(0, edge), (W, edge)], fill=GOLD + (230,), width=3 * S)
    mid = (y0 + y1) / 2
    step = 64 * S
    for x in range(step // 2, W, step):
        d.polygon(star8(x, mid, 13 * S), outline=GOLD + (150,), width=2 * S)
        d.ellipse([x - 3 * S, mid - 3 * S, x + 3 * S, mid + 3 * S], fill=GOLD + (170,))
    img.alpha_composite(over)


def stars(img, n, seed=3, y1=600):
    rnd = random.Random(seed)
    d = ImageDraw.Draw(img)
    for _ in range(n):
        x, y = rnd.randint(0, W), rnd.randint(70 * S, y1 * S)
        r = rnd.choice([1, 1, 2]) * S
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 240, rnd.randint(110, 240)))


def finish(img, name, out, band_fill):
    arr = np.asarray(img).astype("float64")
    arr[:, :, :3] = left_shade(arr[:, :, :3])
    img = Image.fromarray(np.clip(arr, 0, 255).astype("uint8"), "RGBA")
    band(img, 0, 58 * S, band_fill)
    band(img, 1012 * S, H, band_fill, flip=True)
    img.convert("RGB").resize((1920, 1080), Image.LANCZOS).save(out / f"{name}.jpg", quality=92)
    print(out / f"{name}.jpg")


def minaret(d, cx, ground, h, w, color, cap=(0, 0, 0)):
    """A slim Madinah-style minaret: shaft, two balconies, a lantern and a pointed cap."""
    d.rectangle([cx - w, ground - h, cx + w, ground], fill=color)
    for f in (0.5, 0.78):
        y = ground - h * f
        d.rectangle([cx - w - 10 * S, y - 9 * S, cx + w + 10 * S, y], fill=color)
    d.rectangle([cx - w * 0.7, ground - h - 46 * S, cx + w * 0.7, ground - h], fill=color)
    top = ground - h - 46 * S
    d.polygon([(cx - w * 0.85, top), (cx + w * 0.85, top), (cx, top - 70 * S)], fill=color)
    d.ellipse([cx - 5 * S, top - 84 * S, cx + 5 * S, top - 74 * S], fill=cap if cap != (0, 0, 0) else color)


def madinah(out):
    sky = gradient([(0, (4, 12, 22)), (0.55, (10, 34, 48)), (1, (24, 58, 66))])
    sky = glow(sky, (1380, 560), (40, 140, 110), 520, 0.45)
    img = Image.fromarray(np.clip(sky, 0, 255).astype("uint8")).convert("RGBA")
    stars(img, 260)
    d = ImageDraw.Draw(img)
    ground = 800 * S
    body = (8, 18, 24)
    # the long mosque wall with arched windows
    d.rectangle([900 * S, ground - 150 * S, 1880 * S, ground], fill=body)
    for x in range(930, 1860, 46):
        d.rounded_rectangle([x * S, (ground - 120 * S), (x + 18) * S, ground - 40 * S], radius=9 * S,
                            fill=(255, 214, 140, 255))
    # the Green Dome on its drum, with a small lantern and crescent
    cx = 1380 * S
    d.rectangle([cx - 100 * S, ground - 230 * S, cx + 100 * S, ground - 150 * S], fill=body)
    r = 120 * S
    d.pieslice([cx - r, ground - 230 * S - r * 1.2, cx + r, ground - 230 * S + r * 0.8], 180, 360, fill=(30, 140, 90))
    d.pieslice([cx - r * 0.55, ground - 230 * S - r * 1.15, cx - r * 0.1, ground - 230 * S + r * 0.3], 200, 300,
               fill=(70, 190, 130))   # light on the dome
    d.rectangle([cx - 14 * S, ground - 230 * S - r * 1.2 - 40 * S, cx + 14 * S, ground - 230 * S - r * 1.2], fill=(30, 140, 90))
    d.line([(cx, ground - 230 * S - r * 1.2 - 40 * S), (cx, ground - 230 * S - r * 1.2 - 80 * S)], fill=GOLD, width=4 * S)
    d.ellipse([cx - 10 * S, ground - 230 * S - r * 1.2 - 98 * S, cx + 10 * S, ground - 230 * S - r * 1.2 - 78 * S],
              outline=GOLD, width=3 * S)
    # a smaller silver dome beside it
    sx = 1640 * S
    d.pieslice([sx - 70 * S, ground - 150 * S - 90 * S, sx + 70 * S, ground - 150 * S + 50 * S], 180, 360, fill=(70, 86, 96))
    for mx, h in ((960, 430), (1150, 470), (1560, 470), (1820, 430)):
        minaret(d, mx * S, ground, h * S, 20 * S, body, GOLD)
    d.rectangle([0, ground, W, H], fill=(6, 14, 18))
    finish(img, "madinah", out, (6, 16, 22, 230))


def kaaba(out):
    sky = gradient([(0, (8, 8, 20)), (0.6, (26, 22, 44)), (1, (60, 46, 52))])
    sky = glow(sky, (1400, 700), (255, 210, 150), 700, 0.35)
    img = Image.fromarray(np.clip(sky, 0, 255).astype("uint8")).convert("RGBA")
    stars(img, 140)
    d = ImageDraw.Draw(img)
    ground = 820 * S
    # the clock tower far behind, and the arcades of the Haram
    d.rectangle([1720 * S, 260 * S, 1790 * S, ground], fill=(30, 26, 36))
    d.ellipse([1728 * S, 300 * S, 1782 * S, 354 * S], fill=(230, 240, 255))
    d.polygon([(1720 * S, 260 * S), (1790 * S, 260 * S), (1755 * S, 170 * S)], fill=(30, 26, 36))
    d.rectangle([880 * S, ground - 170 * S, 1900 * S, ground], fill=(34, 30, 38))
    for x in range(890, 1890, 54):
        d.pieslice([x * S, (ground - 150 * S), (x + 40) * S, (ground - 90 * S)], 180, 360, fill=(255, 226, 170))
        d.rectangle([x * S, ground - 120 * S, (x + 40) * S, ground - 40 * S], fill=(255, 226, 170))
    for mx, h in ((900, 520), (1080, 560), (1640, 560), (1870, 520)):
        minaret(d, mx * S, ground - 170 * S, h * S - 170 * S, 16 * S, (34, 30, 38), GOLD)
    # the white mataf and the Kaaba with its gold band and door
    d.ellipse([980 * S, ground - 40 * S, 1820 * S, ground + 140 * S], fill=(225, 222, 214))
    kx0, kx1, ktop = 1300 * S, 1520 * S, ground - 230 * S
    d.polygon([(kx0, ktop), (kx1, ktop), (kx1 + 40 * S, ktop + 20 * S), (kx1 + 40 * S, ground + 40 * S),
               (kx1, ground + 50 * S), (kx0, ground + 50 * S)], fill=(10, 10, 12))
    d.polygon([(kx1, ktop), (kx1 + 40 * S, ktop + 20 * S), (kx1 + 40 * S, ground + 40 * S), (kx1, ground + 50 * S)],
              fill=(22, 22, 26))
    d.rectangle([kx0, ktop + 50 * S, kx1, ktop + 74 * S], fill=GOLD)
    d.polygon([(kx1, ktop + 50 * S), (kx1 + 40 * S, ktop + 68 * S), (kx1 + 40 * S, ktop + 90 * S), (kx1, ktop + 74 * S)],
              fill=(170, 136, 70))
    d.rectangle([kx0 + 140 * S, ground - 120 * S, kx0 + 190 * S, ground - 20 * S], fill=GOLD)
    # people circling: small light dots on the mataf
    rnd = random.Random(9)
    for _ in range(700):
        a = rnd.uniform(0, 2 * math.pi)
        rr = rnd.uniform(0.55, 1.0)
        x = 1400 * S + math.cos(a) * 400 * S * rr
        y = ground + 50 * S + math.sin(a) * 85 * S * rr
        if kx0 - 10 * S < x < kx1 + 50 * S and y < ground + 55 * S:
            continue
        r = 2 * S
        d.ellipse([x - r, y - r, x + r, y + r], fill=rnd.choice([(250, 250, 250), (235, 235, 230), (90, 90, 100)]))
    d.rectangle([0, ground + 140 * S, W, H], fill=(20, 18, 24))
    finish(img, "kaaba", out, (14, 12, 22, 230))


def aqsa(out):
    sky = gradient([(0, (30, 26, 70)), (0.45, (120, 70, 110)), (0.75, (230, 140, 90)), (1, (250, 196, 120))])
    sky = glow(sky, (1380, 640), (255, 200, 120), 600, 0.4)
    img = Image.fromarray(np.clip(sky, 0, 255).astype("uint8")).convert("RGBA")
    stars(img, 30, y1=300)
    d = ImageDraw.Draw(img)
    ground = 800 * S
    # the platform and the octagon with blue tiles
    d.rectangle([900 * S, ground - 20 * S, 1880 * S, ground], fill=(60, 40, 50))
    ox0, ox1 = 1180 * S, 1600 * S
    otop = ground - 190 * S
    d.rectangle([ox0, otop, ox1, ground - 20 * S], fill=(40, 70, 140))
    d.rectangle([ox0, otop, ox1, otop + 26 * S], fill=(30, 50, 110))
    for x in range(1195, 1590, 34):
        d.rounded_rectangle([x * S, otop + 50 * S, (x + 18) * S, otop + 120 * S], radius=9 * S, fill=(20, 30, 70))
    for x in range(1180, 1600, 20):
        d.rectangle([x * S, otop + 135 * S, (x + 10) * S, otop + 160 * S], fill=(210, 220, 240))
    # the drum and the golden dome
    cx = 1390 * S
    d.rectangle([cx - 120 * S, otop - 70 * S, cx + 120 * S, otop], fill=(46, 80, 150))
    r = 140 * S
    d.pieslice([cx - r, otop - 70 * S - r * 1.25, cx + r, otop - 70 * S + r * 0.75], 180, 360, fill=(222, 170, 60))
    d.pieslice([cx - r * 0.6, otop - 70 * S - r * 1.2, cx - r * 0.05, otop - 70 * S + r * 0.2], 200, 300,
               fill=(250, 214, 120))
    d.line([(cx, otop - 70 * S - r * 1.25), (cx, otop - 70 * S - r * 1.25 - 60 * S)], fill=(222, 170, 60), width=5 * S)
    d.ellipse([cx - 12 * S, otop - 70 * S - r * 1.25 - 84 * S, cx + 12 * S, otop - 70 * S - r * 1.25 - 60 * S],
              outline=(222, 170, 60), width=4 * S)
    # olive trees and cypresses
    rnd = random.Random(4)
    for tx in (960, 1040, 1700, 1800, 1110):
        h = rnd.randint(90, 140)
        d.rectangle([tx * S - 4 * S, ground - h * S * 0.4, tx * S + 4 * S, ground], fill=(40, 30, 30))
        d.ellipse([tx * S - 46 * S, ground - h * S, tx * S + 46 * S, ground - h * S * 0.35], fill=(46, 60, 46))
    for tx in (1650, 1080):
        d.polygon([(tx * S - 16 * S, ground), (tx * S + 16 * S, ground), (tx * S, ground - 230 * S)], fill=(30, 46, 36))
    d.rectangle([0, ground, W, H], fill=(48, 34, 42))
    finish(img, "aqsa", out, (24, 16, 30, 230))


def andalus(out):
    sky = gradient([(0, (40, 20, 30)), (0.6, (120, 60, 50)), (1, (210, 140, 90))])
    img = Image.fromarray(np.clip(sky, 0, 255).astype("uint8")).convert("RGBA")
    d = ImageDraw.Draw(img)
    # a faint tile pattern behind
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(over)
    step = 110 * S
    for gy in range(80 * S, 760 * S, step):
        for gx in range(800 * S, W + step, step):
            od.polygon(star8(gx, gy, 40 * S), outline=GOLD + (60,), width=2 * S)
            od.rectangle([gx - 28 * S, gy - 28 * S, gx + 28 * S, gy + 28 * S], outline=(230, 200, 150, 40), width=S)
    img.alpha_composite(over)
    d = ImageDraw.Draw(img)
    # the arcade: horseshoe arches with red and white voussoirs on slim columns
    ground = 800 * S
    wall = (70, 34, 30)
    d.rectangle([860 * S, 300 * S, W, ground], fill=wall)
    span, gap = 150 * S, 34 * S
    x = 880 * S
    while x + span < W:
        cx = x + span / 2
        top = 430 * S
        r = span / 2
        # voussoirs: alternating wedges around the horseshoe
        for k in range(18):
            a0 = math.radians(-205 + k * (230 / 18))
            a1 = math.radians(-205 + (k + 1) * (230 / 18))
            col = (190, 60, 50) if k % 2 == 0 else (240, 226, 200)
            d.pieslice([cx - r - 24 * S, top - r - 24 * S, cx + r + 24 * S, top + r + 24 * S],
                       math.degrees(a0), math.degrees(a1), fill=col)
        # the opening: the evening light through the arch
        d.ellipse([cx - r, top - r, cx + r, top + r], fill=(250, 196, 120))
        d.rectangle([cx - r * 0.86, top, cx + r * 0.86, ground], fill=(250, 196, 120))
        d.rectangle([x - gap / 2, top + 20 * S, x + 6 * S, ground], fill=(225, 210, 190))
        x += span + gap
    d.rectangle([860 * S, 300 * S, W, 330 * S], fill=(40, 20, 18))
    for xx in range(860, 1920, 40):
        d.polygon(star8(xx * S + 20 * S, 315 * S, 10 * S), fill=GOLD + (255,))
    d.rectangle([0, ground, W, H], fill=(60, 30, 26))
    for xx in range(0, 1920, 60):
        d.rectangle([xx * S, ground + 20 * S, xx * S + 30 * S, ground + 50 * S], fill=(30, 80, 90))
        d.rectangle([xx * S + 30 * S, ground + 20 * S, xx * S + 60 * S, ground + 50 * S], fill=(200, 170, 110))
    finish(img, "andalus", out, (30, 14, 14, 230))


def lantern(img, cx, top, length, size, seed):
    """A fanous on a thin chain: a glowing body with a cap, a pointed top and a little ring."""
    rnd = random.Random(seed)
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    d.line([(cx, 0), (cx, top + length)], fill=GOLD + (200,), width=2 * S)
    y = top + length
    w, h = size, size * 1.6
    glow_r = size * 2.4
    gl = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(gl).ellipse([cx - glow_r, y + h * 0.2 - glow_r, cx + glow_r, y + h * 0.2 + glow_r],
                               fill=(255, 190, 90, 70))
    img.alpha_composite(gl.filter(ImageFilter.GaussianBlur(size * 0.9)))
    d.polygon([(cx - w * 0.35, y), (cx + w * 0.35, y), (cx, y - h * 0.35)], fill=GOLD + (255,))
    d.ellipse([cx - 6 * S, y - h * 0.5, cx + 6 * S, y - h * 0.38], outline=GOLD + (255,), width=2 * S)
    body = [(cx - w * 0.5, y + h * 0.15), (cx - w * 0.38, y), (cx + w * 0.38, y), (cx + w * 0.5, y + h * 0.15),
            (cx + w * 0.42, y + h * 0.75), (cx, y + h), (cx - w * 0.42, y + h * 0.75)]
    d.polygon(body, fill=rnd.choice([(255, 196, 96, 255), (255, 170, 80, 255), (250, 214, 130, 255)]))
    d.line(body + [body[0]], fill=GOLD + (255,), width=3 * S)
    d.line([(cx, y), (cx, y + h)], fill=(190, 130, 50, 200), width=2 * S)
    d.line([(cx - w * 0.5, y + h * 0.15), (cx + w * 0.5, y + h * 0.15)], fill=(190, 130, 50, 200), width=2 * S)
    d.line([(cx - w * 0.42, y + h * 0.75), (cx + w * 0.42, y + h * 0.75)], fill=(190, 130, 50, 200), width=2 * S)
    img.alpha_composite(over)


def ramadan(out):
    sky = gradient([(0, (6, 8, 26)), (0.6, (20, 18, 52)), (1, (44, 30, 60))])
    sky = glow(sky, (1500, 260), (220, 220, 255), 380, 0.35)
    img = Image.fromarray(np.clip(sky, 0, 255).astype("uint8")).convert("RGBA")
    stars(img, 300)
    # crescent
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(over)
    cx, cy, r = 1500 * S, 250 * S, 70 * S
    od.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 244, 210, 255))
    od.ellipse([cx - r + r * 0.42, cy - r - r * 0.18, cx + r + r * 0.42, cy + r - r * 0.18], fill=(0, 0, 0, 0))
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse([cx - r + r * 0.42, cy - r - r * 0.18, cx + r + r * 0.42, cy + r - r * 0.18], fill=255)
    over.putalpha(Image.composite(Image.new("L", img.size, 0), over.getchannel("A"), mask))
    img.alpha_composite(over)
    # a mosque on the right, windows lit
    d = ImageDraw.Draw(img)
    ground = 800 * S
    body = (10, 10, 28)
    d.rectangle([1000 * S, ground - 140 * S, 1880 * S, ground], fill=body)
    dc = 1440 * S
    wall_top = ground - 140 * S
    d.rectangle([dc - 120 * S, wall_top - 50 * S, dc + 120 * S, wall_top], fill=body)          # drum
    d.pieslice([dc - 150 * S, wall_top - 50 * S - 150 * S, dc + 150 * S, wall_top - 50 * S + 150 * S], 180, 360, fill=body)
    d.line([(dc, wall_top - 200 * S), (dc, wall_top - 250 * S)], fill=GOLD, width=4 * S)
    d.ellipse([dc - 12 * S, wall_top - 274 * S, dc + 12 * S, wall_top - 250 * S], outline=GOLD, width=3 * S)
    for x in range(-90, 100, 36):
        d.rounded_rectangle([dc + x * S - 7 * S, wall_top - 40 * S, dc + x * S + 7 * S, wall_top - 10 * S],
                            radius=7 * S, fill=(255, 200, 110, 255))
    for mx, h in ((1060, 430), (1820, 430)):
        minaret(d, mx * S, ground, h * S, 20 * S, body, GOLD)
    for x in range(1040, 1860, 48):
        d.rounded_rectangle([x * S, ground - 110 * S, (x + 18) * S, ground - 40 * S], radius=9 * S,
                            fill=(255, 200, 110, 255))
    d.rectangle([0, ground, W, H], fill=(8, 8, 22))
    # string of small lights and the lanterns
    for i, x in enumerate(range(980, 1900, 28)):
        y = 120 + 40 * math.sin((x - 980) / 920 * math.pi)
        d.ellipse([x * S - 4 * S, y * S - 4 * S, x * S + 4 * S, y * S + 4 * S],
                  fill=[(255, 214, 120), (255, 160, 90), (240, 230, 200)][i % 3])
    for i, (x, length, size) in enumerate(((1060, 140, 34), (1200, 220, 46), (1340, 120, 30), (1640, 200, 44), (1780, 130, 32))):
        lantern(img, x * S, 60 * S, length * S, size * S, i)
    finish(img, "ramadan", out, (8, 8, 24, 230))


if __name__ == "__main__":
    o = Path(sys.argv[1])
    o.mkdir(parents=True, exist_ok=True)
    for f in ([globals()[n] for n in sys.argv[2:]] or (madinah, kaaba, aqsa, andalus, ramadan)):
        f(o)
