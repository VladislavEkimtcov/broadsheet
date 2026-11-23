#!/usr/bin/env python3
"""Renders res/drawable-xhdpi/banner.png (the 320x180 dp TV launcher banner at 2x): the
Broadsheet masthead on a dark page. Needs Pillow. Run from the repository root: python3 tools/art.py"""
from PIL import Image, ImageDraw, ImageFont

W, H = 640, 360
img = Image.new("RGB", (W, H), (0x11, 0x10, 0x0E))
d = ImageDraw.Draw(img)
for i, y in enumerate(range(236, 330, 22)):  # faint lines of type
    d.rectangle([64, y, 64 + (512 if i < 3 else 330), y + 7], fill=(0x2B, 0x28, 0x24))
mast = ImageFont.truetype("assets/fonts/newsreader-display.ttf", 92)
d.text((W // 2, 128), "Broadsheet", font=mast, fill=(0xF4, 0xEF, 0xE6), anchor="mm")
d.rectangle([W // 2 - 44, 186, W // 2 + 44, 192], fill=(0xE5, 0x48, 0x4D))
img.save("res/drawable-xhdpi/banner.png", optimize=True)
print("wrote res/drawable-xhdpi/banner.png")
