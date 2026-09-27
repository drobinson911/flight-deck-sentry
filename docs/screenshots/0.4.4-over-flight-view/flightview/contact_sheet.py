#!/usr/bin/env python3
"""Tile every heads-up shot in this folder 2 columns wide with a label under each: contact-sheet.png."""
import glob, os
from PIL import Image, ImageDraw, ImageFont
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
F = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
LABELS = {"advisory": "ADVISORY", "caution": "CAUTION", "zone": "Zone entry (TFR)", "track": "TRACK", "warning": "WARNING",
          "collision": "COLLISION RISK", "lost": "Drone position lost", "regain": "Drone position regained",
          "quiet-before": "Before tapping Quiet 5 min", "quiet-tapped": "Just after tapping Quiet 5 min"}
shots = sorted(f for f in glob.glob(os.path.join(HERE, "[0-9][0-9]-*-headsup*.png")))
TW, TH, LH, PAD, HEAD = 940, 588, 46, 14, 70
rows = (len(shots) + 1) // 2
sheet = Image.new("RGB", (2 * TW + 3 * PAD, HEAD + rows * (TH + LH + PAD) + PAD), (18, 22, 28))
d = ImageDraw.Draw(sheet)
d.text((PAD, 18), "Flight Deck Sentry 0.4.4: heads-up banners over a full-screen flight app (RC Plus, 1920x1200 @ 400 dpi, emulated)",
       font=ImageFont.truetype(F, 26), fill=(235, 240, 245))
f = ImageFont.truetype(F, 24)
for i, p in enumerate(shots):
    name = os.path.basename(p)[3:-4]
    key = name.replace("-headsup", "").replace("-night", "")
    label = f"{os.path.basename(p)[:2]}  {LABELS.get(key, key)}" + ("  (night mode ON)" if name.endswith("-night") else "")
    x = PAD + (i % 2) * (TW + PAD); y = HEAD + (i // 2) * (TH + LH + PAD)
    sheet.paste(Image.open(p).convert("RGB").resize((TW, TH), Image.LANCZOS), (x, y))
    d.text((x + 4, y + TH + 10), label, font=f, fill=(235, 240, 245))
out = os.path.join(HERE, "contact-sheet.png")
sheet.save(out, optimize=True)
print(out, os.path.getsize(out) // 1024, "KB", len(shots), "tiles")
