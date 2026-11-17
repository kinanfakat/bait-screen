"""Draws three 1920x1080 Home wallpapers for the "Damascene night" theme: morning, evening, night.

A sky that follows the time of day, Mount Qasioun behind the old city, a mosque in the spirit of the Umayyad
Mosque (long gabled prayer hall, the eagle dome on its drum, three minarets of different styles), and a gold
Islamic-pattern band at the top and bottom with a crescent medallion. Drawn at 2x and scaled down for smooth edges.

    python damascus_wallpapers.py <out-folder>
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
GOLD_SOFT = (214, 172, 92, 120)

MOODS = {
    "morning": dict(sky=[(0.0, (58, 110, 168)), (0.45, (128, 172, 208)), (0.72, (232, 214, 186)), (1.0, (240, 200, 150))],
                    hill=(96, 112, 128), hill2=(76, 90, 108), city=(52, 62, 80), mosque=(40, 46, 62),
                    window=(74, 86, 110, 255), stars=0, glow=((1450, 240), (255, 244, 214), 520), moon=False,
                    band=(16, 30, 44, 225), left_shade=110),
    "evening": dict(sky=[(0.0, (36, 26, 70)), (0.35, (98, 48, 96)), (0.62, (206, 104, 82)), (0.8, (238, 160, 92)), (1.0, (248, 196, 120))],
                    hill=(92, 52, 74), hill2=(66, 38, 60), city=(44, 26, 46), mosque=(30, 18, 34),
                    window=(255, 190, 110, 230), stars=40, glow=((1380, 640), (255, 186, 110), 620), moon=False,
                    band=(26, 14, 30, 225), left_shade=120),
    "night": dict(sky=[(0.0, (6, 10, 30)), (0.5, (14, 28, 66)), (0.78, (26, 52, 98)), (1.0, (44, 74, 116))],
                  hill=(20, 30, 56), hill2=(14, 22, 44), city=(10, 16, 34), mosque=(6, 10, 24),
                  window=(255, 196, 110, 255), stars=420, glow=((1470, 250), (210, 220, 255), 380), moon=True,
                  band=(6, 10, 26, 230), left_shade=90),
}


def gradient(stops):
    ys = np.linspace(0, 1, H)
    cols = np.zeros((H, 3))
    for c in range(3):
        cols[:, c] = np.interp(ys, [s[0] for s in stops], [s[1][c] for s in stops])
    return np.repeat(cols[:, None, :], W, axis=1)


def radial_glow(img, center, color, radius, strength=0.55):
    cx, cy = center[0] * S, center[1] * S
    yy, xx = np.ogrid[:H, :W]
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / (radius * S)
    a = np.clip(1 - d, 0, 1) ** 2 * strength
    for c in range(3):
        img[:, :, c] = img[:, :, c] * (1 - a) + color[c] * a
    return img


def left_shade(img, amount):
    """Darkens the left third so the clock and prayer lines stay easy to read."""
    xs = np.linspace(1, 0, W)
    a = np.clip(xs * 1.6 - 0.55, 0, 1) * (amount / 255)
    ys = np.linspace(1, 0.3, H)[:, None]
    a = a[None, :] * ys
    return img * (1 - a[:, :, None])


def ridge(draw, base, amp, color, seed, step=24):
    rnd = random.Random(seed)
    pts = [(0, H)]
    y = base
    for x in range(0, W + step * S, step * S):
        y += rnd.uniform(-1, 1) * amp * 0.35
        y = min(max(y, base - amp), base + amp * 0.4)
        pts.append((x, y))
    pts.append((W, H))
    draw.polygon(pts, fill=color)


def city(draw, color, window, seed, x0, x1, ground):
    rnd = random.Random(seed)
    x = x0
    while x < x1:
        w = rnd.randint(40, 110) * S
        h = rnd.randint(30, 95) * S
        draw.rectangle([x, ground - h, x + w, ground], fill=color)
        if rnd.random() < 0.25:  # small domes on the old city
            r = rnd.randint(14, 24) * S
            cx = x + w // 2
            draw.ellipse([cx - r, ground - h - r, cx + r, ground - h + r], fill=color)
        if window[3]:
            for _ in range(rnd.randint(0, 3)):
                wx = rnd.randint(x + 6 * S, max(x + 7 * S, x + w - 10 * S))
                wy = rnd.randint(ground - h + 8 * S, ground - 10 * S)
                draw.rectangle([wx, wy, wx + 5 * S, wy + 7 * S], fill=window)
        x += w + rnd.randint(-10, 6) * S


def minaret_square(draw, cx, ground, h, w, color):
    """Minaret of the Bride: a square tower with a gallery and a small pointed cap."""
    draw.rectangle([cx - w, ground - h, cx + w, ground], fill=color)
    draw.rectangle([cx - w - 8 * S, ground - h - 10 * S, cx + w + 8 * S, ground - h], fill=color)
    draw.rectangle([cx - w * 0.6, ground - h - 70 * S, cx + w * 0.6, ground - h - 10 * S], fill=color)
    draw.polygon([(cx - w * 0.75, ground - h - 70 * S), (cx + w * 0.75, ground - h - 70 * S), (cx, ground - h - 120 * S)], fill=color)
    draw.line([(cx, ground - h - 120 * S), (cx, ground - h - 140 * S)], fill=color, width=3 * S)


def minaret_pencil(draw, cx, ground, h, w, color):
    """Minaret of Jesus: tall square base, octagonal shaft, needle top."""
    draw.rectangle([cx - w, ground - h * 0.55, cx + w, ground], fill=color)
    draw.rectangle([cx - w - 8 * S, ground - h * 0.55 - 10 * S, cx + w + 8 * S, ground - h * 0.55], fill=color)
    draw.rectangle([cx - w * 0.7, ground - h, cx + w * 0.7, ground - h * 0.55], fill=color)
    draw.rectangle([cx - w * 0.85, ground - h - 10 * S, cx + w * 0.85, ground - h], fill=color)
    draw.polygon([(cx - w * 0.7, ground - h - 10 * S), (cx + w * 0.7, ground - h - 10 * S), (cx, ground - h - 150 * S)], fill=color)
    draw.line([(cx, ground - h - 150 * S), (cx, ground - h - 172 * S)], fill=color, width=3 * S)


def minaret_qaitbay(draw, cx, ground, h, w, color):
    """Minaret of Qaitbay: slim octagonal shaft with two balconies and an onion cap."""
    draw.rectangle([cx - w, ground - h, cx + w, ground], fill=color)
    for f in (0.45, 0.8):
        y = ground - h * f
        draw.rectangle([cx - w - 9 * S, y - 8 * S, cx + w + 9 * S, y], fill=color)
    draw.rectangle([cx - w * 0.75, ground - h - 50 * S, cx + w * 0.75, ground - h], fill=color)
    r = w * 1.05
    top = ground - h - 50 * S
    draw.ellipse([cx - r, top - r * 1.6, cx + r, top + r * 0.4], fill=color)
    draw.polygon([(cx - r * 0.5, top - r * 1.3), (cx + r * 0.5, top - r * 1.3), (cx, top - r * 2.4)], fill=color)
    draw.line([(cx, top - r * 2.4), (cx, top - r * 2.4 - 18 * S)], fill=color, width=3 * S)


def mosque(draw, color, window, ground):
    x0, x1 = 980 * S, 1820 * S
    hall_h = 120 * S
    # long prayer hall with a gabled roof
    draw.rectangle([x0, ground - hall_h, x1, ground], fill=color)
    draw.polygon([(x0, ground - hall_h), (x1, ground - hall_h), (x1 - 20 * S, ground - hall_h - 46 * S), (x0 + 20 * S, ground - hall_h - 46 * S)], fill=color)
    # central transept with the eagle dome on an octagonal drum
    cx = 1400 * S
    tw = 110 * S
    draw.rectangle([cx - tw, ground - hall_h - 90 * S, cx + tw, ground - hall_h], fill=color)
    draw.polygon([(cx - tw - 16 * S, ground - hall_h - 90 * S), (cx + tw + 16 * S, ground - hall_h - 90 * S), (cx, ground - hall_h - 150 * S)], fill=color)
    drum_w, drum_h = 78 * S, 60 * S
    dy = ground - hall_h - 120 * S
    draw.rectangle([cx - drum_w, dy - drum_h, cx + drum_w, dy], fill=color)
    r = 92 * S
    draw.pieslice([cx - r, dy - drum_h - r * 1.15, cx + r, dy - drum_h + r * 0.85], 180, 360, fill=color)
    draw.polygon([(cx - r * 0.3, dy - drum_h - r * 0.9), (cx + r * 0.3, dy - drum_h - r * 0.9), (cx, dy - drum_h - r * 1.35)], fill=color)
    draw.line([(cx, dy - drum_h - r * 1.35), (cx, dy - drum_h - r * 1.35 - 26 * S)], fill=color, width=4 * S)
    # crescent finial
    fy = dy - drum_h - r * 1.35 - 34 * S
    draw.ellipse([cx - 10 * S, fy - 10 * S, cx + 10 * S, fy + 10 * S], outline=color, width=3 * S)
    # arched windows: drum and hall
    if window[3]:
        for i in range(-2, 3):
            wx = cx + i * 28 * S
            draw.rounded_rectangle([wx - 7 * S, dy - drum_h + 16 * S, wx + 7 * S, dy - 10 * S], radius=7 * S, fill=window)
        for wx in range(x0 + 40 * S, x1 - 30 * S, 52 * S):
            if abs(wx - cx) < tw + 10 * S:
                continue
            draw.rounded_rectangle([wx, ground - hall_h + 34 * S, wx + 16 * S, ground - 26 * S], radius=8 * S, fill=window)
        for wx in range(cx - tw + 26 * S, cx + tw - 20 * S, 46 * S):
            draw.rounded_rectangle([wx, ground - hall_h - 64 * S, wx + 18 * S, ground - hall_h - 14 * S], radius=9 * S, fill=window)
    # three minarets
    minaret_qaitbay(draw, x0 + 30 * S, ground, 300 * S, 18 * S, color)
    minaret_square(draw, 1180 * S, ground, 250 * S, 26 * S, color)
    minaret_pencil(draw, x1 - 40 * S, ground, 360 * S, 24 * S, color)


def star8(cx, cy, r):
    pts = []
    for i in range(16):
        a = math.pi / 8 * i - math.pi / 2
        rr = r if i % 2 == 0 else r * 0.62
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    return pts


def band(img, y0, y1, fill, flip=False, gap=True):
    """A dark strip edged in gold with a row of eight-point stars between thin rules."""
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    d.rectangle([0, y0, W, y1], fill=fill)
    edge = y0 if flip else y1
    d.line([(0, edge), (W, edge)], fill=GOLD + (230,), width=3 * S)
    d.line([(0, edge + (6 * S if flip else -6 * S)), (W, edge + (6 * S if flip else -6 * S))], fill=GOLD + (120,), width=1 * S)
    mid = (y0 + y1) / 2
    step = 64 * S
    for x in range(step // 2, W, step):
        if gap and abs(x - W / 2) < 120 * S:
            continue
        d.polygon(star8(x, mid, 13 * S), outline=GOLD + (150,), width=2 * S)
        d.ellipse([x - 3 * S, mid - 3 * S, x + 3 * S, mid + 3 * S], fill=GOLD + (170,))
        d.line([(x + 18 * S, mid), (x + step - 18 * S, mid)], fill=GOLD + (60,), width=1 * S)
    img.alpha_composite(over)


def medallion(img, cx, cy, r, bg):
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=bg)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=GOLD + (240,), width=3 * S)
    d.ellipse([cx - r + 8 * S, cy - r + 8 * S, cx + r - 8 * S, cy + r - 8 * S], outline=GOLD + (120,), width=1 * S)
    d.polygon(star8(cx, cy, r * 0.78), outline=GOLD + (140,), width=2 * S)
    # crescent: a gold disc with an offset dark disc cut out of it, plus a small star
    cr = r * 0.42
    d.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=GOLD + (255,))
    off = cr * 0.38
    d.ellipse([cx - cr + off, cy - cr - off * 0.3, cx + cr + off, cy + cr - off * 0.3], fill=bg)
    d.polygon(star8(cx + cr * 0.55, cy - cr * 0.15, cr * 0.22), fill=GOLD + (255,))
    img.alpha_composite(over)


def corner_pattern(img, alpha):
    """A faint lattice of eight-point stars in the top-right sky, the zakhrafa touch."""
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    step = 96 * S
    for gy in range(140 * S, 520 * S, step):
        for gx in range(1100 * S, W + step, step):
            fade = max(0.0, 1 - (520 * S - gy) / (420 * S) * 0.2) * min(1.0, (gx - 1100 * S) / (500 * S))
            a = int(alpha * fade)
            if a <= 2:
                continue
            d.polygon(star8(gx, gy, 30 * S), outline=GOLD + (a,), width=2 * S)
            d.rectangle([gx - 21 * S, gy - 21 * S, gx + 21 * S, gy + 21 * S], outline=GOLD + (a // 2,), width=1 * S)
    img.alpha_composite(over.filter(ImageFilter.GaussianBlur(0.6 * S)))


def moon(img, cx, cy, r):
    over = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(over)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 246, 214, 255))
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).ellipse([cx - r + r * 0.45, cy - r - r * 0.2, cx + r + r * 0.45, cy + r - r * 0.2], fill=255)
    over.putalpha(Image.composite(Image.new("L", img.size, 0), over.getchannel("A"), mask))
    img.alpha_composite(over)


def render(name, m, out):
    rnd = random.Random(7)
    sky = gradient(m["sky"])
    c, col, rad = m["glow"]
    sky = radial_glow(sky, c, col, rad)
    img = Image.fromarray(np.clip(sky, 0, 255).astype("uint8")).convert("RGBA")
    d = ImageDraw.Draw(img)
    for _ in range(m["stars"]):
        x, y = rnd.randint(0, W), rnd.randint(70 * S, 640 * S)
        r = rnd.choice([1, 1, 1, 2]) * S
        a = rnd.randint(120, 255)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 240, a))
    corner_pattern(img, 34 if name != "morning" else 26)
    if m["moon"]:
        moon(img, 1470 * S, 250 * S, 58 * S)
    d = ImageDraw.Draw(img)
    ground = 790 * S
    ridge(d, 600 * S, 70 * S, m["hill"], 11)      # Qasioun, far
    ridge(d, 680 * S, 40 * S, m["hill2"], 23, 16)  # nearer slope
    city(d, m["city"], m["window"], 5, 0, W, ground)
    mosque(d, m["mosque"], m["window"], ground)
    d.rectangle([0, ground, W, H], fill=m["mosque"])
    arr = np.asarray(img).astype("float64")
    arr[:, :, :3] = left_shade(arr[:, :, :3], m["left_shade"])
    img = Image.fromarray(np.clip(arr, 0, 255).astype("uint8"), "RGBA")
    band(img, 0, 58 * S, m["band"])
    band(img, 1012 * S, H, m["band"], flip=True, gap=False)
    medallion(img, W // 2, 50 * S, 46 * S, m["band"][:3] + (255,))
    img = img.convert("RGB").resize((1920, 1080), Image.LANCZOS)
    img.save(out / f"{name}.jpg", quality=92)


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for n, m in MOODS.items():
        render(n, m, out)
        print(out / f"{n}.jpg")
