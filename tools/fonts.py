#!/usr/bin/env python3
"""Makes assets/fonts/*.ttf: static instances of Newsreader (Production Type, SIL OFL 1.1), the
serif designed for news reading on screens. Android 7.1 can't pick a variable font's weight or
optical size from Java without API 26, so the app ships the few cuts it uses.

Needs fontTools (pip install fonttools). Run from the repository root: python3 tools/fonts.py
It downloads the variable fonts from github.com/google/fonts unless given their paths:
python3 tools/fonts.py Newsreader[opsz,wght].ttf Newsreader-Italic[opsz,wght].ttf"""
import os
import sys
import tempfile
import urllib.request

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

BASE = "https://github.com/google/fonts/raw/main/ofl/newsreader/"
ROMAN = "Newsreader%5Bopsz%2Cwght%5D.ttf"
ITALIC = "Newsreader-Italic%5Bopsz%2Cwght%5D.ttf"

# file name: (italic?, weight, optical size)
CUTS = {
    "newsreader-display.ttf": (False, 700, 72),   # masthead, drop caps, the reader's headline
    "newsreader-head.ttf": (False, 600, 24),      # card and hero headlines, subheads
    "newsreader-text.ttf": (False, 400, 16),      # body text
    "newsreader-text-bold.ttf": (False, 650, 16),  # bold in body text
    "newsreader-text-italic.ttf": (True, 400, 16),  # standfirst, captions, italics
    "newsreader-head-italic.ttf": (True, 500, 30),  # pull quotes
}


def fetch(name, folder):
    path = os.path.join(folder, name.replace("%5B", "[").replace("%5D", "]").replace("%2C", ","))
    if not os.path.exists(path):
        urllib.request.urlretrieve(BASE + name, path)
    return path


def main():
    if len(sys.argv) == 3:
        roman, italic = sys.argv[1], sys.argv[2]
    else:
        tmp = tempfile.mkdtemp()
        roman, italic = fetch(ROMAN, tmp), fetch(ITALIC, tmp)
        urllib.request.urlretrieve(BASE + "OFL.txt", "assets/fonts/OFL.txt")
    os.makedirs("assets/fonts", exist_ok=True)
    for out, (is_italic, wght, opsz) in CUTS.items():
        font = TTFont(italic if is_italic else roman)
        static = instancer.instantiateVariableFont(font, {"wght": wght, "opsz": opsz}, updateFontNames=False)
        static.save(os.path.join("assets/fonts", out))
        print("wrote assets/fonts/%s (%d KB)" % (out, os.path.getsize(os.path.join("assets/fonts", out)) // 1024))


if __name__ == "__main__":
    main()
