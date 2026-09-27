#!/usr/bin/env python3
"""Render the "Flight View" stand-in frame: a synthetic FPV scene + a generic ground-station HUD, 1920x1200.
Everything is procedurally drawn (value noise terrain, PIL shapes). No photos, logos, or real names.
usage: render.py out.png"""
import sys, math
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1920, 1200
rng = np.random.default_rng(7)
F = "/usr/share/fonts/truetype/dejavu/"
def font(sz, bold=False, mono=False):
    n = "DejaVuSansMono" if mono else "DejaVuSans"
    return ImageFont.truetype(F + n + ("-Bold" if bold else "") + ".ttf", sz)

def noise(h, w, octaves=6, base=4, seed=0):
    r = np.random.default_rng(seed); out = np.zeros((h, w)); amp = 1.0; tot = 0
    for o in range(octaves):
        n = base * 2 ** o
        g = r.random((n + 1, int(n * w / h) + 2))
        img = Image.fromarray((g * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
        out += amp * np.asarray(img, float) / 255; tot += amp; amp *= 0.5
    return out / tot

# ---- scene: oblique aerial view, horizon at y=300, slight bank --------------------------------------------------
HOR = 300
img = np.zeros((H, W, 3))
yy, xx = np.mgrid[0:H, 0:W].astype(float)
# sky
t = np.clip(yy / HOR, 0, 1)[..., None]
sky = (1 - t) * np.array([70, 120, 180]) + t * np.array([188, 204, 214])
# ground: project each pixel onto a flat ground plane (camera 312 ft up, pitched down) and sample a big noise map
TS = 2048
T1 = noise(TS, TS, 8, 4, 1); T2 = noise(TS, TS, 6, 16, 2); T3 = noise(TS, TS, 5, 48, 3)
dy = np.maximum(yy - HOR, 0.5)
z = 60000.0 / dy                                  # ground distance along the view axis (arbitrary units)
X = (xx - W / 2) * z / 900.0                      # lateral ground coordinate
u = ((X * 3.0 + TS / 2) % TS).astype(int); v = ((z * 1.2 + 300) % TS).astype(int)
tex = T1[v, u] * 0.65 + T2[v, u] * 0.35
depth = np.clip((yy - HOR) / (H - HOR), 1e-3, 1)
forest = np.array([46, 70, 40]); dry = np.array([158, 142, 100]); rock = np.array([124, 116, 104])
m = np.clip((tex - 0.47) * 5, 0, 1)[..., None]
ground = forest * (1 - m) + dry * m
r2 = np.clip((T2[v, u] - 0.64) * 6, 0, 1)[..., None]
ground = ground * (1 - r2) + rock * r2
ground *= (0.72 + 0.4 * T3[v, u])[..., None]      # tree-crown speckle, finer near the camera
haze = np.clip(1 - depth * 2.6, 0, 1)[..., None] ** 1.6
ground = ground * (1 - haze * 0.85) + np.array([178, 188, 194]) * haze * 0.85
g = (yy >= HOR)[..., None]
img = np.where(g, ground, sky)
# distant ridge line
ridge = HOR - 18 - 40 * noise(1, W, 5, 3, 4)[0] ** 1.2 * 1.4
for x in range(W):
    r = int(ridge[x]); img[r:HOR, x] = img[r:HOR, x] * 0.35 + np.array([118, 132, 142]) * 0.65
im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
d = ImageDraw.Draw(im)
# a dirt road winding toward the horizon
pts = []
for i in range(60):
    s = i / 59; y = H - s * (H - HOR - 8)
    x = 1080 + 260 * math.sin(s * 5.2) * (1 - s) ** 1.2 - 380 * s
    pts.append((x, y, 44 * (1 - s) ** 1.6 + 1.5))
for (x0, y0, w0), (x1, y1, w1) in zip(pts, pts[1:]):
    d.line([(x0, y0), (x1, y1)], fill=(170, 150, 118), width=max(1, int(w0)))
im = im.filter(ImageFilter.GaussianBlur(0.6))

# ---- HUD --------------------------------------------------------------------------------------------------------
hud = Image.new("RGBA", (W, H), (0, 0, 0, 0)); h = ImageDraw.Draw(hud)
WHITE = (245, 248, 250, 255); DIM = (200, 210, 218, 255); GREEN = (80, 230, 120, 255); AMBER = (255, 196, 64, 255)
def shadow_text(xy, s, f, fill=WHITE, anchor="la"):
    x, y = xy
    h.text((x + 2, y + 2), s, font=f, fill=(0, 0, 0, 170), anchor=anchor)
    h.text((x, y), s, font=f, fill=fill, anchor=anchor)
# top bar
h.rectangle([0, 0, W, 86], fill=(8, 12, 16, 150))
shadow_text((28, 20), "Flight View", font(34, True))
h.rounded_rectangle([262, 22, 432, 64], 8, fill=(30, 140, 70, 230)); h.text((347, 43), "IN FLIGHT", font=font(24, True), fill=WHITE, anchor="mm")
# record dot + timer
h.ellipse([470, 30, 496, 56], fill=(230, 40, 40, 255)); shadow_text((508, 43), "REC 00:12:41", font(28, True, True), anchor="lm")
# right cluster: GPS, RC bars, battery
x = W - 30
h.rounded_rectangle([x - 150, 22, x - 30, 64], 6, outline=WHITE, width=3)
h.rectangle([x - 144, 28, x - 144 + int(108 * 0.71), 58], fill=GREEN); h.rectangle([x - 28, 34, x - 20, 52], fill=WHITE)
h.text((x - 90, 43), "71%", font=font(22, True), fill=(10, 20, 10, 255), anchor="mm")
shadow_text((x - 170, 43), "BAT", font(22, True), fill=DIM, anchor="rm")
bx = x - 330
for i in range(5):
    bh = 8 + i * 7; h.rectangle([bx + i * 14, 58 - bh, bx + i * 14 + 9, 58], fill=WHITE if i < 4 else (120, 120, 120, 255))
shadow_text((bx - 12, 43), "RC", font(22, True), fill=DIM, anchor="rm")
shadow_text((bx - 90, 43), "GPS 17", font(26, True), anchor="rm")
# heading tape under the top bar
cx, ty = W // 2, 100
h.rounded_rectangle([cx - 420, ty, cx + 420, ty + 58], 8, fill=(8, 12, 16, 120))
hdg = 247
for deg in range((hdg - 60) // 5 * 5, hdg + 61, 5):
    px = cx + (deg - hdg) * 7
    major = deg % 15 == 0
    h.line([(px, ty + 4), (px, ty + (22 if major else 12))], fill=WHITE, width=2)
    if deg % 30 == 0:
        lab = {0: "N", 90: "E", 180: "S", 270: "W"}.get(deg % 360, str((deg % 360) // 10))
        h.text((px, ty + 40), lab, font=font(20, True), fill=WHITE, anchor="mm")
h.polygon([(cx - 12, ty + 58), (cx + 12, ty + 58), (cx, ty + 44)], fill=AMBER)
h.rounded_rectangle([cx - 38, ty + 60, cx + 38, ty + 92], 6, fill=(8, 12, 16, 200)); h.text((cx, ty + 76), f"{hdg}°", font=font(22, True), fill=AMBER, anchor="mm")
# centre reticle + a light pitch ladder
cy = H // 2
h.line([(cx - 90, cy), (cx - 30, cy)], fill=WHITE, width=3); h.line([(cx + 30, cy), (cx + 90, cy)], fill=WHITE, width=3)
h.line([(cx, cy - 30), (cx, cy - 12)], fill=WHITE, width=3); h.ellipse([cx - 5, cy - 5, cx + 5, cy + 5], outline=WHITE, width=2)
for k, p in ((-1, -10), (1, 10)):
    y = cy + k * 150
    h.line([(cx - 160, y), (cx - 70, y)], fill=(245, 248, 250, 150), width=2); h.line([(cx + 70, y), (cx + 160, y)], fill=(245, 248, 250, 150), width=2)
    h.text((cx - 175, y), str(abs(p)), font=font(18), fill=(245, 248, 250, 170), anchor="rm")
# left telemetry block
lx, ly = 28, 300
h.rounded_rectangle([lx, ly, lx + 300, ly + 360], 12, fill=(8, 12, 16, 150))
rows = [("ALT", "312", "ft AGL"), ("DIST", "0.4", "nm"), ("SPD", "18", "kt"), ("V/S", "0", "ft/min"), ("H.DIST", "2,430", "ft")]
for i, (k, v, u) in enumerate(rows):
    y = ly + 22 + i * 68
    h.text((lx + 20, y), k, font=font(20, True), fill=DIM)
    h.text((lx + 20, y + 24), v, font=font(34, True, True), fill=WHITE)
    h.text((lx + 20 + font(34, True, True).getlength(v) + 10, y + 36), u, font=font(20), fill=DIM)
# gimbal pitch readout right of the reticle area
shadow_text((W - 470, 330), "GIMBAL -32°", font(22, True), fill=DIM)
shadow_text((W - 470, 362), "ZOOM 2.0x   EV 0", font(22, True), fill=DIM)
# bottom-left button row
for i, (lab, col) in enumerate((("RTH", (200, 110, 20, 235)), ("PAUSE", (40, 60, 80, 235)), ("PHOTO", (40, 60, 80, 235)))):
    x0 = 28 + i * 190
    h.rounded_rectangle([x0, H - 120, x0 + 170, H - 36], 14, fill=col, outline=(255, 255, 255, 90), width=2)
    h.text((x0 + 85, H - 78), lab, font=font(30, True), fill=WHITE, anchor="mm")
# bottom map inset
mw, mh = 470, 330; mx, my = W - mw - 28, H - mh - 28
mp = Image.new("RGB", (mw, mh), (214, 222, 206)); md = ImageDraw.Draw(mp)
mn = noise(mh, mw, 5, 3, 9)
tint = (np.clip((mn - 0.5) * 3, 0, 1)[..., None] * np.array([-40, -20, -50]) + np.array([214, 222, 206]))
mp = Image.fromarray(np.clip(tint, 0, 255).astype(np.uint8)); md = ImageDraw.Draw(mp)
for i in range(0, mw, 58): md.line([(i, 0), (i, mh)], fill=(200, 206, 194), width=1)
for i in range(0, mh, 58): md.line([(0, i), (mw, i)], fill=(200, 206, 194), width=1)
md.line([(0, 250), (140, 210), (260, 220), (470, 120)], fill=(250, 250, 250), width=7)
md.line([(0, 250), (140, 210), (260, 220), (470, 120)], fill=(236, 190, 90), width=4)
md.line([(310, 330), (300, 200), (330, 0)], fill=(250, 250, 250), width=5)
md.line([(60, 0), (120, 110), (90, 330)], fill=(120, 170, 220), width=4)   # creek
hx, hy = 150, 240
md.line([(hx, hy), (262, 146)], fill=(40, 120, 230), width=3)
md.ellipse([hx - 14, hy - 14, hx + 14, hy + 14], fill=(255, 196, 64), outline=(40, 40, 40), width=2)
md.text((hx, hy), "H", font=font(18, True), fill=(20, 20, 20), anchor="mm")
dx, dy = 262, 146; a = math.radians(247)
tri = [(dx + 18 * math.sin(a), dy - 18 * math.cos(a)), (dx + 12 * math.sin(a + 2.5), dy - 12 * math.cos(a + 2.5)), (dx + 12 * math.sin(a - 2.5), dy - 12 * math.cos(a - 2.5))]
md.polygon(tri, fill=(230, 40, 40), outline=(255, 255, 255))
md.text((mw - 10, mh - 10), "0.25 nm", font=font(16, True), fill=(40, 40, 40), anchor="rd")
md.line([(mw - 110, mh - 34), (mw - 10, mh - 34)], fill=(40, 40, 40), width=3)
md.text((12, 10), "N ↑", font=font(18, True), fill=(40, 40, 40))
mask = Image.new("L", (mw, mh), 0); ImageDraw.Draw(mask).rounded_rectangle([0, 0, mw - 1, mh - 1], 16, fill=255)
out = Image.alpha_composite(im.convert("RGBA"), hud)
out.paste(mp, (mx, my), mask)
ImageDraw.Draw(out).rounded_rectangle([mx, my, mx + mw - 1, my + mh - 1], 16, outline=(255, 255, 255, 255), width=3)
out.convert("RGB").save(sys.argv[1])
