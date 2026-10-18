"""Organic 3D-bevel skins (plate / whale / orb / strip). Height-field + chrome environment shading."""
import numpy as np, json, sys
from PIL import Image, ImageDraw
from scipy import ndimage as ndi
from art import *
OUT = sys.argv[1] if len(sys.argv) > 1 else '.'
SC = 2  # supersample

def blobs(W, H, items, thr=0.30):
    Y, X = np.mgrid[0:H, 0:W].astype(np.float32); F = np.zeros((H, W), np.float32)
    for cx, cy, rx, ry, rot in items:
        c, s = np.cos(rot), np.sin(rot); dx, dy = X - cx * SC, Y - cy * SC
        u = (dx * c + dy * s) / (rx * 1.5 * SC); v = (-dx * s + dy * c) / (ry * 1.5 * SC)
        q = u * u + v * v; F += np.clip(1 - q, 0, 1) ** 2
    return F > thr

def rr(W, H, x, y, w, h, r):
    im = Image.new('L', (W, H), 0); ImageDraw.Draw(im).rounded_rectangle([x * SC, y * SC, (x + w) * SC, (y + h) * SC], r * SC, fill=255); return np.array(im) > 127
def circ(W, H, cx, cy, r):
    Y, X = np.mgrid[0:H, 0:W]; return (X - cx * SC) ** 2 + (Y - cy * SC) ** 2 <= (r * SC) ** 2

def build(name, W, H, mask, R, holes, buttons, extra, dome=0.35, nk=1.0):
    SW, SH = W * SC, H * SC
    h, _ = pillow_height(mask, R * SC, dome)
    floors = []
    for m, kind, depth, edge in holes:
        h, d_in = carve(h, m, depth * SC, edge * SC); floors.append((m, kind, d_in, edge * SC))
    for b in buttons:  # sockets
        h, _ = carve(h, circ(SW, SH, b['cx'], b['cy'], b['r'] + 9), 7 * SC, 7 * SC)
    rgb = shade_normals(height_to_normals(h, nk))
    for m, kind, d_in, edge in floors:
        if kind == 'lcd': fl = lcd_floor((SH, SW), d_in, edge)
        elif kind == 'dark': fl = lcd_floor((SH, SW), d_in, edge, (0.035, 0.075, 0.05), (0.02, 0.05, 0.035))
        elif kind == 'grill': fl = lcd_floor((SH, SW), d_in, edge, (0.18, 0.2, 0.22), (0.12, 0.13, 0.15))
        a = (m * sstep(edge * 0.8, edge, d_in))[..., None]; rgb = rgb * (1 - a) + fl * a
    out = Image.fromarray((np.dstack([rgb, np.ones((SH, SW))]) * 255).astype(np.uint8), 'RGBA')
    for b in buttons:
        s = int(2 * b['r'] * SC); g = gel_button(s, b.get('hue', (0.12, 0.30, 0.86)))
        out.alpha_composite(Image.fromarray((g * 255).astype(np.uint8), 'RGBA'), (int(b['cx'] * SC - s / 2), int(b['cy'] * SC - s / 2)))
    arr = np.array(out).astype(np.float32) / 255; arr[..., 3] = mask
    pm = arr[..., :3] * arr[..., 3:4]; pm = np.dstack([pm, arr[..., 3]])
    pm = np.array(Image.fromarray((pm * 255).astype(np.uint8), 'RGBA').resize((W, H), Image.LANCZOS)).astype(np.float32) / 255
    a = pm[..., 3:4]; rgbo = np.where(a > 0.002, pm[..., :3] / np.maximum(a, 0.002), 0)
    Image.fromarray((np.dstack([np.clip(rgbo, 0, 1), a]) * 255).astype(np.uint8), 'RGBA').save(f'{OUT}/{name}.png')
    meta = dict(w=W, h=H, buttons=[{k: v for k, v in b.items() if k != 'hue'} for b in buttons]); meta.update(extra)
    json.dump(meta, open(f'{OUT}/{name}.json', 'w'))

def R_(x, y, w, h, r=24): return dict(x=x, y=y, w=w, h=h, r=r)

# ---------- PLATE (main-screen body under the head) ----------
W, H = 1080, 1050; SW, SH = W * SC, H * SC
m = blobs(SW, SH, [(540, 90, 250, 130, 0), (320, 600, 310, 320, 0), (760, 600, 310, 320, 0), (540, 560, 400, 310, 0), (190, 400, 105, 190, 0), (890, 400, 105, 190, 0)])
panel = R_(115, 265, 850, 640, 64)
grill = np.zeros((SH, SW), bool)
for i in range(9): grill |= rr(SW, SH, 300 + i * 56, 950, 26, 56, 13)
build('plate', W, H, m, 70, [(rr(SW, SH, *[panel[k] for k in 'xywhr']), 'dark', 22, 30), (grill, 'grill', 10, 12)], [],
      dict(panel=panel), dome=0.25)

# ---------- WHALE popup (copy of the "ILLUSION" idea: organic body, LCD, gel buttons, blue knob, EQ strip) ----------
W, H = 1000, 600; SW, SH = W * SC, H * SC
m = blobs(SW, SH, [(300, 190, 300, 135, -.03), (520, 225, 215, 120, 0), (740, 205, 205, 95, -.14), (890, 115, 90, 60, -.6), (235, 395, 190, 120, .1), (400, 335, 200, 110, 0), (590, 450, 230, 60, -.05), (800, 425, 150, 50, -.1), (745, 305, 170, 80, -.1)])
lcd = R_(95, 95, 380, 125, 30); info = R_(95, 242, 470, 52, 24); spec = R_(335, 425, 510, 50, 20)
btn = [dict(id='prev', cx=605, cy=225, r=40), dict(id='play', cx=700, cy=185, r=52), dict(id='next', cx=800, cy=160, r=40), dict(id='tool', cx=665, cy=315, r=34),
       dict(id='mode', cx=760, cy=290, r=34), dict(id='app', cx=850, cy=262, r=30), dict(id='close', cx=905, cy=120, r=28, hue=(0.75, 0.12, 0.12))]
knob = dict(id='knob', cx=175, cy=440, r=68, hue=(0.10, 0.25, 0.9))
build('whale', W, H, m, 46, [(rr(SW, SH, *[lcd[k] for k in 'xywhr']), 'lcd', 14, 18), (rr(SW, SH, *[info[k] for k in 'xywhr']), 'lcd', 10, 14), (rr(SW, SH, *[spec[k] for k in 'xywhr']), 'dark', 10, 14)],
      btn + [knob], dict(lcd=lcd, info=info, spec=spec, knob=knob))

# ---------- ORB popup (round, K-Jofol "misery" idea) ----------
W, H = 760, 760; SW, SH = W * SC, H * SC
m = circ(SW, SH, 380, 380, 372); angs = [0, 60, 120, 180, 240, 300]; ids = ['play', 'next', 'tool', 'close', 'app', 'prev']
btn = [dict(id=i, cx=round(380 + 292 * np.sin(np.radians(a)), 1), cy=round(380 - 292 * np.cos(np.radians(a)), 1), r=54 if i == 'play' else 42, **({'hue': (0.75, .12, .12)} if i == 'close' else {})) for a, i in zip(angs, ids)]
lcd = R_(190, 190, 380, 380, 190)
build('orb', W, H, m, 52, [(circ(SW, SH, 380, 380, 190), 'lcd', 16, 22)], btn, dict(lcd=lcd, info=R_(255, 468, 250, 36, 18), spec=R_(250, 510, 260, 40, 16)), dome=0.45)

# ---------- STRIP (notification: wavy 3-D pod, staggered buttons) ----------
W, H = 1080, 360; SW, SH = W * SC, H * SC
m = blobs(SW, SH, [(195, 170, 170, 125, 0), (440, 160, 170, 120, 0), (650, 185, 160, 130, 0), (850, 175, 165, 130, 0), (955, 175, 90, 120, 0), (735, 240, 155, 95, 0), (320, 250, 200, 70, 0)])
lcd = R_(70, 78, 470, 130, 28); info = R_(70, 228, 470, 56, 24)
btn = [dict(id='prev', cx=610, cy=120, r=50), dict(id='play', cx=740, cy=205, r=66), dict(id='next', cx=880, cy=118, r=50), dict(id='app', cx=985, cy=235, r=36), dict(id='close', cx=995, cy=95, r=30, hue=(0.75, .12, .12))]
build('strip', W, H, m, 44, [(rr(SW, SH, *[lcd[k] for k in 'xywhr']), 'lcd', 12, 16), (rr(SW, SH, *[info[k] for k in 'xywhr']), 'lcd', 9, 12)], btn, dict(lcd=lcd, info=info), dome=0.3)
print('ok')
