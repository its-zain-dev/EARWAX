"""Organic 3D-bevel skins (plate / whale / orb / strip). Height-field + chrome environment shading."""
import numpy as np, json, sys, os
from PIL import Image, ImageDraw
from scipy import ndimage as ndi
from art import *
OUT = sys.argv[1] if len(sys.argv) > 1 else "."
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

def smooth_field(mask, sigma):
    """Signed distance of the silhouette, blurred. The blur rounds off the creases a plain distance transform has along the
    medial axis of narrow parts (they showed up as scratchy streaks on the whale's left / right edges) and irons out lumps in
    the outline. Returns the field in supersampled pixels (>0 inside)."""
    sd = ndi.distance_transform_edt(mask) - ndi.distance_transform_edt(~mask)
    return ndi.gaussian_filter(sd.astype(np.float32), sigma)

def build(name, W, H, mask, R, holes, buttons, extra, dome=0.35, nk=1.0, emboss=(), smooth=0):
    SW, SH = W * SC, H * SC
    alpha = mask.astype(np.float32)
    if smooth:
        sd = smooth_field(mask, smooth * SC); mask = sd > 0
        alpha = np.clip(sd + 0.5, 0, 1).astype(np.float32)                    # soft (anti-aliased) edge, not a stair-stepped one
        d = np.maximum(sd, 0); big = ndi.gaussian_filter(np.minimum(d, R * SC * 6), R * SC * 1.6)
        h = edge_profile(d, R * SC) + dome * big
    else:
        h, _ = pillow_height(mask, R * SC, dome)
    for em in emboss: h = h + 5 * SC * ndi.gaussian_filter(em.astype(np.float32), 1.2 * SC)
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
        gl = np.clip(1 - (np.arange(SW)[None, :] + np.arange(SH)[:, None] * 1.3) / (SW + SH * 1.3) * 2.4, 0, 1) ** 1.5 * (0.13 if kind == 'dark' else 0.08)
        rgb = np.clip(rgb + (gl * a[..., 0])[..., None], 0, 1)
    out = Image.fromarray((np.dstack([rgb, np.ones((SH, SW))]) * 255).astype(np.uint8), 'RGBA')
    for b in buttons:
        s = int(2 * b['r'] * SC); g = gel_button(s, b.get('hue', (0.12, 0.30, 0.86)))
        out.alpha_composite(Image.fromarray((g * 255).astype(np.uint8), 'RGBA'), (int(b['cx'] * SC - s / 2), int(b['cy'] * SC - s / 2)))
    arr = np.array(out).astype(np.float32) / 255; arr[..., 3] = alpha
    pm = arr[..., :3] * arr[..., 3:4]; pm = np.dstack([pm, arr[..., 3]])
    pm = np.array(Image.fromarray((pm * 255).astype(np.uint8), 'RGBA').resize((W, H), Image.LANCZOS)).astype(np.float32) / 255
    a = pm[..., 3:4]; rgbo = np.where(a > 0.002, pm[..., :3] / np.maximum(a, 0.002), 0)
    fin = finish(np.dstack([np.clip(rgbo, 0, 1), a[..., 0]]))
    Image.fromarray((fin * 255).astype(np.uint8), 'RGBA').save(f'{OUT}/{name}.png')
    meta = dict(w=W, h=H, buttons=[{k: v for k, v in b.items() if k != 'hue'} for b in buttons]); meta.update(extra)
    json.dump(meta, open(f'{OUT}/{name}.json', 'w'))

def R_(x, y, w, h, r=24): return dict(x=x, y=y, w=w, h=h, r=r)


def shift(items, dx, dy): return [(a + dx, b + dy) + tuple(rest) for (a, b, *rest) in items]
def Rr(r, dx, dy): return dict(r, x=r['x'] + dx, y=r['y'] + dy)
def Bt(bs, dx, dy): return [dict(b, cx=round(b['cx'] + dx, 1), cy=round(b['cy'] + dy, 1)) for b in bs]

def text_mask(W, H, txt, x, y, size, anchor='la'):
    from PIL import ImageFont
    import matplotlib
    fp = os.path.join(matplotlib.get_data_path(), 'fonts', 'ttf', 'DejaVuSans-Bold.ttf')
    im = Image.new('L', (W, H), 0); ImageDraw.Draw(im).text((x * SC, y * SC), txt, font=ImageFont.truetype(fp, int(size * SC)), fill=255, anchor=anchor)
    return np.array(im) > 127

def shadow_pad(name, pad):
    """Adds a soft drop shadow + room around the shape (so the popup never clips its own edge) and shifts the JSON by `pad`."""
    im = Image.open(f'{OUT}/{name}.png').convert('RGBA'); w, h = im.size
    a = np.array(im)[..., 3].astype(np.float32) / 255
    big = Image.new('RGBA', (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    sh = np.zeros((h + 2 * pad, w + 2 * pad), np.float32); sh[pad + 8:pad + 8 + h, pad:pad + w] = a
    sh = ndi.gaussian_filter(sh, pad * 0.33) * 0.55
    rim = np.zeros_like(sh); rim[pad:pad + h, pad:pad + w] = a; rim = ndi.gaussian_filter(rim, pad * 0.12) * 0.25      # tight contact shadow
    sa = np.clip(sh + rim, 0, 0.8)
    big = Image.fromarray(np.dstack([np.zeros((h + 2 * pad, w + 2 * pad, 3), np.uint8) + np.array([6, 10, 18], np.uint8), (sa * 255).astype(np.uint8)]), 'RGBA')
    big.alpha_composite(im, (pad, pad)); big.save(f'{OUT}/{name}.png')
    j = json.load(open(f'{OUT}/{name}.json')); j['w'] += 2 * pad; j['h'] += 2 * pad; j['pad'] = pad
    for b in j['buttons']: b['cx'] = round(b['cx'] + pad, 1); b['cy'] = round(b['cy'] + pad, 1)
    for k in ('lcd', 'info', 'spec'):
        if k in j: j[k]['x'] += pad; j[k]['y'] += pad
    if 'knob' in j: j['knob']['cx'] += pad; j['knob']['cy'] += pad
    if 'dial' in j: j['dial']['cx'] += pad; j['dial']['cy'] += pad
    json.dump(j, open(f'{OUT}/{name}.json', 'w'))

# ---------- WHALE popup (organic body, LCD, gel buttons, blue rotary knob, EQ strip) ----------
DX, DY = 36, 20
W, H = 1000 + 2 * DX, 600 + 2 * DY; SW, SH = W * SC, H * SC
items = shift([(300, 190, 300, 135, -.03), (520, 225, 215, 120, 0), (740, 205, 205, 95, -.14), (890, 115, 90, 60, -.6), (235, 395, 190, 120, .1), (205, 430, 150, 110, 0), (400, 335, 200, 110, 0), (590, 450, 230, 60, -.05), (800, 425, 150, 50, -.1), (745, 305, 170, 80, -.1)], DX, DY)
m = blobs(SW, SH, items)
lcd = Rr(R_(95, 95, 380, 125, 30), DX, DY); info = Rr(R_(95, 242, 470, 52, 24), DX, DY); spec = Rr(R_(335, 425, 510, 50, 20), DX, DY)
btn = Bt([dict(id='prev', cx=605, cy=225, r=40), dict(id='play', cx=700, cy=185, r=52), dict(id='next', cx=800, cy=160, r=40), dict(id='tool', cx=665, cy=315, r=34),
          dict(id='mode', cx=760, cy=290, r=34), dict(id='app', cx=850, cy=262, r=30), dict(id='close', cx=905, cy=120, r=28, hue=(0.75, 0.12, 0.12))], DX, DY)
knob = dict(id='knob', cx=190 + DX, cy=432 + DY, r=74, hue=(0.10, 0.25, 0.9))
logo = text_mask(SW, SH, 'EARWAX', 360 + DX, 322 + DY, 30, 'la')
build('whale', W, H, m, 46, [(rr(SW, SH, *[lcd[k] for k in 'xywhr']), 'lcd', 14, 18), (rr(SW, SH, *[info[k] for k in 'xywhr']), 'lcd', 10, 14), (rr(SW, SH, *[spec[k] for k in 'xywhr']), 'dark', 10, 14)],
      btn + [knob], dict(lcd=lcd, info=info, spec=spec, knob=knob), emboss=[logo], smooth=30)
shadow_pad('whale', 36)

# ---------- ORB popup: LCD disc, rotary dial ring (270 deg, gap at the bottom), 3 transport buttons, 3 small ones ----------
W, H = 720, 720; SW, SH = W * SC, H * SC
m = circ(SW, SH, 360, 360, 352)
Yg, Xg = np.mgrid[0:SH, 0:SW].astype(np.float32); rad = np.hypot(Xg / SC - 360, Yg / SC - 360); ang = np.degrees(np.arctan2(Xg / SC - 360, -(Yg / SC - 360)))   # 0 = up, clockwise +
track = (rad > 292) & (rad < 338) & (np.abs(ang) <= 135)
lcd = R_(218, 158, 284, 284, 142); info = R_(268, 312, 184, 30, 14); spec = R_(285, 380, 150, 36, 12)
btn = [dict(id='prev', cx=262, cy=520, r=38), dict(id='play', cx=360, cy=540, r=52), dict(id='next', cx=458, cy=520, r=38),
       dict(id='tool', cx=186, cy=372, r=30), dict(id='app', cx=534, cy=372, r=30), dict(id='close', cx=360, cy=118, r=24, hue=(0.75, .12, .12))]
dial = dict(cx=360, cy=360, r0=292, r1=338, a0=-135, a1=135)
build('orb', W, H, m, 50, [(circ(SW, SH, 360, 300, 142) & (Yg < 100000), 'lcd', 16, 22), (track, 'dark', 9, 12)], btn, dict(lcd=lcd, info=info, spec=spec, dial=dial), dome=0.42)
shadow_pad('orb', 34)
print('ok')
