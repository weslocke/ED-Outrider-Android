#!/usr/bin/env python3
"""Builds the README's theme carousels: docs/images/themes-rail.webp (Outrider on the gaming PC, with the ship
controls) and docs/images/themes-server.webp (Outrider on a server, without them), each the nine themes' Nearby as
one animated image that slides from theme to theme (GitHub strips scripts and styles, and shrinks wide tables, so a
README can't scroll sideways).

    python3 tools/theme_carousel.py

Needs Pillow. Re-run it after changing a rail-*.webp or server-*.webp screenshot.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
IMAGES = ROOT / "docs" / "images"
# the two sets: (screenshot prefix, carousel file)
SETS = [("rail", "themes-rail.webp"), ("server", "themes-server.webp")]

# (theme id, name as the page's picker shows it, a word on its look), in the order they slide past
SLIDES = [
    ("elite", "Elite", "the cockpit HUD"),
    ("babylon5", "Babylon 5 - Earthforce", "Earth Alliance consoles"),
    ("narn", "Babylon 5 - Narn", "the Narn Regime"),
    ("minbari", "Babylon 5 - Minbari", "the Minbari Federation"),
    ("centauri", "Babylon 5 - Centauri", "the Centauri Republic"),
    ("lcars", "LCARS", "Star Trek"),
    ("sith", "Sith", "Star Wars"),
    ("alliance", "Alliance", "Star Wars"),
    ("dark", "Dark", "a modern dark interface"),
]

WIDTH = 960
SHOT_H = WIDTH * 800 // 1280
BAR_H = 64
HOLD_MS = 2800
SLIDE_FRAMES = 8
SLIDE_MS = 40
BAR_BG = (11, 14, 20)
TEXT = (236, 240, 245)
MUTED = (140, 150, 165)
ACCENT = (255, 140, 26)   # the README's ED Outrider orange

BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
REGULAR = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"


def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        return ImageFont.load_default()


def caption(index):
    """The bar under a screenshot: the theme's name, what it shows, and one dot per theme."""
    bar = Image.new("RGB", (WIDTH, BAR_H), BAR_BG)
    d = ImageDraw.Draw(bar)
    _, name, about = SLIDES[index]
    big, small = font(BOLD, 24), font(REGULAR, 16)
    d.text((24, BAR_H // 2), name, font=big, fill=TEXT, anchor="lm")
    x = 24 + d.textlength(name, font=big) + 16
    d.text((x, BAR_H // 2 + 1), about, font=small, fill=MUTED, anchor="lm")
    r, gap = 5, 20
    x0 = WIDTH - 24 - gap * (len(SLIDES) - 1)
    for i in range(len(SLIDES)):
        cx, cy = x0 + i * gap, BAR_H // 2
        d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=ACCENT if i == index else (60, 68, 80))
    return bar


def slide(prefix, index):
    shot = Image.open(IMAGES / f"{prefix}-{SLIDES[index][0]}.webp").convert("RGB").resize((WIDTH, SHOT_H), Image.LANCZOS)
    frame = Image.new("RGB", (WIDTH, SHOT_H + BAR_H), BAR_BG)
    frame.paste(shot, (0, 0))
    frame.paste(caption(index), (0, SHOT_H))
    return frame


def build(prefix, out):
    slides = [slide(prefix, i) for i in range(len(SLIDES))]
    frames, durations = [], []
    for i, current in enumerate(slides):
        frames.append(current)
        durations.append(HOLD_MS)
        following = slides[(i + 1) % len(slides)]
        for step in range(1, SLIDE_FRAMES + 1):
            t = step / (SLIDE_FRAMES + 1)
            t = t * t * (3 - 2 * t)   # ease in and out
            offset = round(WIDTH * t)
            f = Image.new("RGB", current.size)
            f.paste(current, (-offset, 0))
            f.paste(following, (WIDTH - offset, 0))
            frames.append(f)
            durations.append(SLIDE_MS)
    frames[0].save(out, save_all=True, append_images=frames[1:], duration=durations, loop=0,
                   quality=72, method=6, minimize_size=True)
    print(f"{out.relative_to(ROOT)}: {len(frames)} frames, {out.stat().st_size // 1024} KB")


def main():
    for prefix, name in SETS:
        build(prefix, IMAGES / name)


if __name__ == "__main__":
    main()
