#!/usr/bin/env python3
"""Draws windows/Broadsheet.ico: the window icon from MainWindow.MakeIcon (white Newsreader
Display "B" and rule on the red square), at the sizes the Start menu, taskbar and Explorer use.
The exe embeds it (ApplicationIcon in Broadsheet.csproj), so shortcuts show it too.
Needs Pillow:  python3 tools/windows-icon.py"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RED, WHITE = (0xC4, 0x26, 0x2C, 255), (255, 255, 255, 255)


def draw(px):
    """MakeIcon's 64-unit drawing, at px pixels."""
    k = px / 64.0
    img = Image.new("RGBA", (px, px), RED)
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(os.path.join(ROOT, "assets/fonts/newsreader-display.ttf"), round(50 * k))
    ascent, descent = font.getmetrics()
    # WPF centres the line box (ascent + descent) and the advance width, one unit lower.
    x = (px - font.getlength("B")) / 2
    baseline = (px - (ascent + descent)) / 2 + 1 * k + ascent
    d.text((x, baseline), "B", font=font, fill=WHITE, anchor="ls")
    d.rectangle([14 * k, 54 * k, 50 * k - 1, 57 * k - 1], fill=WHITE)
    return img


if __name__ == "__main__":
    out = os.path.join(ROOT, "windows/Broadsheet.ico")
    sizes = [16, 20, 24, 32, 40, 48, 64, 96, 128, 256]
    big = draw(1024)  # drawn large and scaled down, so thin parts stay soft at 16 px
    imgs = [big.resize((s, s), Image.LANCZOS) for s in sizes]
    imgs[-1].save(out, sizes=[(s, s) for s in sizes], append_images=imgs[:-1])
    imgs[-1].save(os.path.join(os.environ.get("TMPDIR", "/tmp"), "broadsheet-icon-256.png"))
    print(out, os.path.getsize(out), "bytes")
