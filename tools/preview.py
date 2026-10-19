"""Composes phone / popup / notification previews from the SAME PNG + JSON the app loads (no emulator needed)."""
import json, os, math, random
from PIL import Image, ImageDraw, ImageFont
import matplotlib
A = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'skins')
FP = os.path.join(matplotlib.get_data_path(), 'fonts', 'ttf', 'DejaVuSansMono-Bold.ttf')
F = lambda n: ImageFont.truetype(FP, int(n))
LED, DIM, INK = (155, 255, 168), (63, 138, 82), (23, 56, 31)
def load(n): return Image.open(f'{A}/{n}.png').convert('RGBA'), json.load(open(f'{A}/{n}.json'))
def glyph(d, id, cx, cy, r, col):
    k = r * .42
    if id == 'prev': d.rectangle([cx - k, cy - k, cx - k * .55, cy + k], fill=col); d.polygon([(cx + k, cy - k), (cx + k, cy + k), (cx - k * .55, cy)], fill=col)
    elif id == 'next': d.rectangle([cx + k * .55, cy - k, cx + k, cy + k], fill=col); d.polygon([(cx - k, cy - k), (cx - k, cy + k), (cx + k * .55, cy)], fill=col)
    elif id == 'play': d.polygon([(cx - k * .7, cy - k * 1.1), (cx - k * .7, cy + k * 1.1), (cx + k * 1.1, cy)], fill=col)
    elif id == 'close': d.line([cx - k, cy - k, cx + k, cy + k], fill=col, width=int(r * .14)); d.line([cx + k, cy - k, cx - k, cy + k], fill=col, width=int(r * .14))
    elif id == 'tool': [d.line([cx - k, cy + i * k * .8, cx + k, cy + i * k * .8], fill=col, width=int(r * .12)) for i in (-1, 0, 1)]
    elif id == 'mode': d.arc([cx - k, cy - k, cx + k, cy + k], 40, 320, fill=col, width=int(r * .14))
    elif id == 'app': d.rectangle([cx - k, cy - k * .8, cx + k * .6, cy + k], outline=col, width=int(r * .12))
    else:
        t = dict(list='LST', eq='EQ', fx='FX', shuffle='S', repeat='R', sleep='Z', popup='P').get(id, '')
        d.text((cx, cy), t, font=F(r * (.62 if len(t) > 1 else .9)), fill=col, anchor='mm')
def hud(img, js, strip=False, title='NIGHT DRIVE - SYNTHWAVE', artist='EARWAX DEMO'):
    d = ImageDraw.Draw(img); lcd = js.get('lcd'); info = js.get('info'); spec = js.get('spec')
    if lcd:
        if strip: d.text((lcd['x'] + 20, lcd['y'] + lcd['h'] / 2), 'NIGHT DRIVE', font=F(lcd['h'] * .34), fill=INK, anchor='lm')
        elif lcd['h'] > lcd['w'] * .7:
            cx, cy = lcd['x'] + lcd['w'] / 2, lcd['y'] + lcd['h'] / 2; ts = lcd['h'] * .24
            d.text((cx, cy - ts * .8), 'PLAY', font=F(ts * .32), fill=INK, anchor='mm'); d.text((cx, cy + ts * .25), '01:23', font=F(ts), fill=INK, anchor='mm'); d.text((cx, cy + ts * .8), 'VOL 80%', font=F(ts * .34), fill=INK, anchor='mm')
        else:
            ts = lcd['h'] * .5; d.text((lcd['x'] + 20, lcd['y'] + lcd['h'] / 2), '01:23', font=F(ts), fill=INK, anchor='lm')
            d.text((lcd['x'] + lcd['w'] - 16, lcd['y'] + lcd['h'] * .3), 'VOL 80%', font=F(lcd['h'] * .2), fill=INK, anchor='rm'); d.text((lcd['x'] + lcd['w'] - 16, lcd['y'] + lcd['h'] * .8), '> PLAY', font=F(lcd['h'] * .2), fill=INK, anchor='rm')
    if info:
        sz = info['h'] * .6
        if not strip: d.rectangle([info['x'], info['y'], info['x'] + info['w'] * .38, info['y'] + info['h']], fill=(70, 150, 90))
        d.text((info['x'] + 12, info['y'] + info['h'] / 2), (artist if strip else title)[:26], font=F(sz), fill=INK, anchor='lm')
    if spec and not strip:
        random.seed(3); n = 32; bw = (spec['w'] - 20) / n
        for i in range(n):
            v = max(.08, math.sin(i / 5 + 1) * .4 + .5) * random.uniform(.7, 1); h_ = v * (spec['h'] - 12)
            col = tuple(int(c * 255) for c in __import__('colorsys').hsv_to_rgb((125 - 120 * v) / 360, .9, 1))
            d.rectangle([spec['x'] + 10 + i * bw + 1.5, spec['y'] + spec['h'] - 6 - h_, spec['x'] + 10 + (i + 1) * bw - 1.5, spec['y'] + spec['h'] - 6], fill=col)
    for b in js['buttons']:
        if b['id'] == 'knob':
            a = math.radians(-135 + 270 * .8); d.line([b['cx'] + math.sin(a) * b['r'] * .28, b['cy'] - math.cos(a) * b['r'] * .28, b['cx'] + math.sin(a) * b['r'] * .78, b['cy'] - math.cos(a) * b['r'] * .78], fill='white', width=int(b['r'] * .12))
        else: glyph(d, b['id'], b['cx'], b['cy'], b['r'], 'white')
def phone():
    W, H = 1080, 2200; im = Image.open(f'{A}/bg.jpg').convert('RGBA').resize((W, H), Image.BICUBIC); oy = 50
    from PIL import ImageFilter
    fog = Image.new('RGBA', (W, H), (0, 0, 0, 0)); fd = ImageDraw.Draw(fog)
    for i, (cx, cy, r) in enumerate([(380, 500, 700), (760, 1150, 760), (420, 1800, 820)]): fd.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(153, 184, 216, 36))
    im.alpha_composite(fog.filter(ImageFilter.GaussianBlur(160)))
    head, hj = load('head'); plate, pj = load('plate')
    im.alpha_composite(plate, (0, 1050 + oy)); im.alpha_composite(head, (0, oy)); d = ImageDraw.Draw(im)
    r = hj['screen']; x0, y0 = r['x'], r['y'] + oy
    d.rounded_rectangle([x0, y0, x0 + r['w'], y0 + r['h']], 30, fill=(4, 14, 12))
    random.seed(7); n = 32; bw = (r['w'] - 56) / n; top = y0 + 26; bot = y0 + r['h'] * .46; seg = (bot - top) / 16
    for i in range(n):
        lit = int(max(.1, math.sin(i / 4.5 + .5) * .38 + .5) * random.uniform(.75, 1) * 16)
        for k in range(16):
            c = (14, 34, 24) if k >= lit else (59, 227, 122) if k < 9 else (232, 224, 74) if k < 13 else (255, 74, 58)
            d.rectangle([x0 + 28 + i * bw + 2, bot - (k + 1) * seg + 2, x0 + 28 + (i + 1) * bw - 2, bot - k * seg], fill=c)
    d.text((x0 + 30, y0 + r['h'] * .60), 'NIGHT DRIVE - SYNTHWAVE', font=F(34), fill=LED, anchor='lm'); d.text((x0 + 30, y0 + r['h'] * .70), 'EARWAX DEMO', font=F(26), fill=DIM, anchor='lm')
    d.text((x0 + 30, y0 + r['h'] * .83), '01:23', font=F(30), fill=LED, anchor='lm'); d.text((x0 + r['w'] - 30, y0 + r['h'] * .83), '03:45', font=F(30), fill=DIM, anchor='rm')
    gy = y0 + r['h'] - 22; d.line([x0 + 30, gy, x0 + r['w'] - 30, gy], fill=(23, 64, 42), width=10); d.line([x0 + 30, gy, x0 + 30 + (r['w'] - 60) * .37, gy], fill=LED, width=10)
    p = pj['panel']; px0, py0 = p['x'], p['y'] + 1050 + oy
    d.text((px0 + 30, py0 + 50), 'LIBRARY  248 TRACKS', font=F(34), fill=DIM, anchor='lm')
    names = ['Aurora - Northern Lights', 'Daft Mind - Midnight Drive', 'Nova - Gravity', 'Echo Park - Slow Burn', 'The Waves - Blue Hour', 'Orbit - Satellite', 'Lumen - Afterglow', 'Kite - Paper Planes', 'Vega - Cold Light']
    for i, nm in enumerate(names):
        y = py0 + 72 + i * 64
        if i == 2: d.rounded_rectangle([px0 + 16, y + 4, px0 + p['w'] - 16, y + 60], 14, fill=(30, 90, 60))
        d.text((px0 + 30, y + 32), '%03d' % (i + 1), font=F(28), fill=DIM, anchor='lm'); d.text((px0 + 118, y + 32), nm, font=F(32), fill=(255, 255, 255) if i == 2 else LED, anchor='lm'); d.text((px0 + p['w'] - 30, y + 32), '03:%02d' % (10 + i * 4), font=F(28), fill=DIM, anchor='rm')
    for b in hj['buttons']:
        cone = b['id'] in ('prev', 'play', 'next', 'list', 'eq', 'fx'); glyph(d, b['id'], b['cx'], b['cy'] + oy, b['r'] * (1.0 if cone else .62), LED if cone else (26, 42, 85))
        if b['id'] in ('list', 'shuffle'): rr_ = b['r'] * (.9 if cone else .78); d.ellipse([b['cx'] - rr_, b['cy'] + oy - rr_, b['cx'] + rr_, b['cy'] + oy + rr_], outline=(127, 230, 255), width=6)
    from PIL import ImageChops
    bl = im.convert('RGB').filter(ImageFilter.GaussianBlur(9)); im = Image.blend(im.convert('RGB'), ImageChops.screen(im.convert('RGB'), bl), .35)
    mist = Image.new('RGBA', (W, H), (0, 0, 0, 0)); md = ImageDraw.Draw(mist)
    for y in range(int(H * .6), H): md.line([0, y, W, y], fill=(168, 196, 224, int(48 * (y - H * .6) / (H * .4))))
    im = Image.alpha_composite(im.convert('RGBA'), mist)
    return im.convert('RGB')
def popup(n, bg):
    s, j = load(n); hud(s, j); return s
def montage():
    ph = phone(); ph.save('/tmp/phone.png')
    whale, orb = popup('whale', 0), popup('orb', 0); st, sj = load('strip'); hud(st, sj, True)
    W = 2400; im = Image.new('RGB', (W, 1500), (30, 36, 52))
    d = ImageDraw.Draw(im); ref1 = Image.open('/mnt/user-data/uploads/IMG_20261017_201950_212.jpg').convert('RGB'); ref2 = Image.open('/mnt/user-data/uploads/IMG_20261017_201950_110.jpg').convert('RGB')
    # row 1: ref head | ours (phone)
    r1 = ref1.copy(); r1.thumbnail((620, 900)); im.paste(r1, (20, 60)); d.text((20, 20), 'REFERENCE: green head', font=F(26), fill='white')
    p2 = ph.copy(); p2.thumbnail((620, 1300)); im.paste(p2, (680, 60)); d.text((680, 20), 'OURS: silver head (phone screen)', font=F(26), fill='white')
    r2 = ref2.copy(); r2.thumbnail((620, 780)); im.paste(r2, (1330, 60)); d.text((1330, 20), 'REFERENCE: shaped popups', font=F(26), fill='white')
    wh = whale.resize((520, 312)); im.alpha_composite = None
    tmp = im.convert('RGBA'); tmp.alpha_composite(wh, (1960, 60)); tmp.alpha_composite(orb.resize((400, 400)), (2000, 400)); tmp.alpha_composite(st.resize((690, 230)), (1330, 880)); im = tmp.convert('RGB'); d = ImageDraw.Draw(im)
    d.text((1960, 20), 'OURS: whale popup', font=F(26), fill='white'); d.text((2000, 380), 'OURS: orb popup', font=F(22), fill='white'); d.text((1330, 850), 'OURS: notification strip (collapsed 64dp / expanded 120dp)', font=F(22), fill='white')
    im.save('/mnt/user-data/outputs/earwax-preview.png'); print('saved')
if __name__ == '__main__': montage()
