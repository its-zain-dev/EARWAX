"""EARWAX 4.0 — Pure Python Early-2000s Y2K Backdrop, 3D Chrome Wordmark, Head Mark & Launcher Icons.

Run AFTER build_bust.py (composites the sculpted head + ear pods + cones + crown orbs for mark & icons).
Uses only NumPy + Pillow + system TrueType fonts (zero SciPy / matplotlib dependency!).
"""

import json
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from y2k_shader import env_y2k, tonemap_y2k, finish_y2k, downsample_rgba, sstep

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(HERE, '..', 'app', 'src', 'main')
SK = os.path.join(MAIN, 'assets', 'skins')
RES = os.path.join(MAIN, 'res')
BR = os.path.join(HERE, '..', 'branding')

FONT_CANDIDATES = [
    '/system/fonts/DroidSans-Bold.ttf',
    '/system/fonts/Roboto-Bold.ttf',
    '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',
]
FONT_REG_CANDIDATES = [
    '/system/fonts/DroidSans.ttf',
    '/system/fonts/Roboto-Regular.ttf',
    '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
]

def pick_font(paths, size):
    for p in paths:
        if os.path.exists(p):
            return ImageFont.truetype(p, int(size))
    return ImageFont.load_default()

def build_backdrop(path, W=540, H=1170):
    """Atmospheric Early-2000s Y2K Cyber-Studio Backdrop (deep sapphire/cyan volumetric beams + subtle aurora)."""
    Y, X = np.mgrid[0:H, 0:W].astype(np.float32)
    u, v = X / W, Y / H

    # Deep liquid-obsidian-navy base gradient
    top = np.array([11.0, 22.0, 42.0], np.float32)
    mid = np.array([6.0, 13.0, 26.0], np.float32)
    bot = np.array([3.0, 6.0, 14.0], np.float32)
    t = v[..., None]
    img = np.where(t < 0.5, top * (1.0 - t * 2.0) + mid * (t * 2.0),
                   mid * (1.0 - (t - 0.5) * 2.0) + bot * ((t - 0.5) * 2.0))

    def glow(cx, cy, rx, ry, col, k):
        g = np.exp(-(((u - cx) / rx) ** 2 + ((v - cy) / ry) ** 2))
        return g[..., None] * np.array(col, np.float32) * k

    # Volumetric early-2000s studio cyan + cobalt/violet rim beams
    img += glow(0.22, 0.22, 0.52, 0.24, (28, 128, 185), 0.72)
    img += glow(0.82, 0.28, 0.46, 0.26, (62, 54, 162), 0.62)
    img += glow(0.50, 0.16, 0.40, 0.18, (40, 160, 210), 0.35)
    img += glow(0.48, 0.86, 0.65, 0.22, (18, 62, 118), 0.42)

    # Subtle diagonal Y2K studio glass light rays
    ray1 = np.clip(np.sin((u * 1.4 - v * 0.85) * 6.5) * 0.5 + 0.5, 0.0, 1.0) ** 4.0
    ray2 = np.clip(np.sin((u * 0.9 + v * 1.1) * 5.0 + 1.2) * 0.5 + 0.5, 0.0, 1.0) ** 5.0
    img += (ray1[..., None] * np.array([22, 74, 118], np.float32) * 0.18
            + ray2[..., None] * np.array([42, 34, 96], np.float32) * 0.12)

    # Anti-banding studio film grain
    rng = np.random.default_rng(2001)
    img += rng.normal(0.0, 1.4, img.shape).astype(np.float32)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB').save(path, quality=92)
    print(f"[brand] Wrote {path} ({W}x{H})")

def build_chrome_wordmark(txt='EARWAX', size=96, track=0.11, pad=36):
    """Pure Python 3D Beveled Early-2000s Liquid-Chrome Wordmark with Aqua Specular Rim."""
    SC = 2
    f = pick_font(FONT_CANDIDATES, size * SC)
    adv = [f.getlength(c) + size * SC * track for c in txt]
    w = int(sum(adv) + 2 * pad * SC)
    h = int(size * SC * 1.5 + 2 * pad * SC)

    im = Image.new('L', (w, h), 0)
    dr = ImageDraw.Draw(im)
    x = pad * SC
    for c, a_len in zip(txt, adv):
        dr.text((x, h * 0.5), c, font=f, fill=255, anchor='lm')
        x += a_len

    # Multi-radius Pillow blur builds a silky smooth 3D pillowed heightfield (zero SciPy needed!)
    m_f = np.asarray(im, dtype=np.float32) / 255.0
    h1 = np.asarray(im.filter(ImageFilter.GaussianBlur(radius=3.5 * SC)), dtype=np.float32) / 255.0
    h2 = np.asarray(im.filter(ImageFilter.GaussianBlur(radius=9.0 * SC)), dtype=np.float32) / 255.0
    h3 = np.asarray(im.filter(ImageFilter.GaussianBlur(radius=18.0 * SC)), dtype=np.float32) / 255.0
    hh = (h1 * 0.45 + h2 * 0.35 + h3 * 0.20) * (12.0 * SC)

    gy, gx = np.gradient(hh)
    nx, ny, nz = -gx * 1.35, gy * 1.35, np.ones_like(hh)
    inv = 1.0 / np.sqrt(nx * nx + ny * ny + nz * nz)
    nx *= inv; ny *= inv; nz *= inv

    rx = 2.0 * nz * nx; ry = 2.0 * nz * ny; rz = 2.0 * nz * nz - 1.0
    env = env_y2k(rx, ry, rz, softness=0.95)
    fresnel = (1.0 - np.clip(nz, 0.0, 1.0)) ** 2.4
    lx, ly, lz = -0.42, 0.66, 0.62
    ln = np.sqrt(lx * lx + ly * ly + lz * lz)
    hx, hy, hz = lx / ln, ly / ln, lz / ln + 1.0
    hn = np.sqrt(hx * hx + hy * hy + hz * hz)
    spec = np.clip(nx * (hx / hn) + ny * (hy / hn) + nz * (hz / hn), 0.0, 1.0) ** 90.0

    col = env * np.array([0.96, 0.99, 1.06], np.float32) + spec[..., None] * 1.45
    col += fresnel[..., None] * np.array([0.22, 0.85, 1.15], np.float32) * 0.48
    col = tonemap_y2k(col)

    rgba = np.dstack([col, m_f]).astype(np.float32)
    lo = downsample_rgba(rgba, w // SC, h // SC)
    lo = finish_y2k(lo, bloom=0.30, bloom_thr=0.76, haze=0.02)
    out = Image.fromarray((np.clip(lo, 0, 1) * 255).astype(np.uint8), 'RGBA')
    bb = out.getbbox()
    if bb:
        out = out.crop((max(0, bb[0] - 12), max(0, bb[1] - 12), min(out.width, bb[2] + 12), min(out.height, bb[3] + 12)))
    return out

def fit_img(img, box_w, box_h):
    k = min(box_w / img.width, box_h / img.height)
    return img.resize((max(1, int(img.width * k)), max(1, int(img.height * k))), Image.LANCZOS)

def icon_backdrop(w, h, radius=0):
    Y, X = np.mgrid[0:h, 0:w].astype(np.float32)
    u, v = X / w, Y / h
    c = np.array([8, 16, 32], np.float32) * (1.0 - v[..., None]) + np.array([18, 42, 78], np.float32) * v[..., None]
    g = np.exp(-(((u - 0.35) / 0.55) ** 2 + ((v - 0.30) / 0.45) ** 2))[..., None] * np.array([38, 145, 205], np.float32) * 0.65
    g2 = np.exp(-(((u - 0.78) / 0.48) ** 2 + ((v - 0.65) / 0.48) ** 2))[..., None] * np.array([88, 66, 195], np.float32) * 0.45
    im = Image.fromarray(np.clip(c + g + g2, 0, 255).astype(np.uint8), 'RGB').convert('RGBA')
    if radius:
        m = Image.new('L', (w, h), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], radius, fill=255)
        im.putalpha(m)
    return im

def main():
    for d in (
        RES + '/drawable', RES + '/drawable-nodpi',
        RES + '/mipmap-xxxhdpi', RES + '/mipmap-xxhdpi', RES + '/mipmap-xhdpi',
        RES + '/mipmap-hdpi', RES + '/mipmap-mdpi', RES + '/mipmap-anydpi-v26',
        RES + '/values', RES + '/values-ar', BR,
    ):
        os.makedirs(d, exist_ok=True)

    # 1. Backdrop
    build_backdrop(os.path.join(SK, 'bg.jpg'))

    # 2. 3D Chrome Wordmark
    logo = build_chrome_wordmark('EARWAX', 96)
    logo.save(os.path.join(SK, 'logo.png'), optimize=True)
    print(f"[brand] Wrote logo.png {logo.size}")

    # 3. Sculpted Head Mark (bust head + ear pods + cones + floating crown orbs)
    bust = Image.open(os.path.join(SK, 'bust.png')).convert('RGBA')
    with open(os.path.join(SK, 'bust.json')) as f:
        J = json.load(f)
    comp = Image.new('RGBA', bust.size, (0, 0, 0, 0))
    k = bust.width / float(J['w'])

    head_arr = np.asarray(bust, dtype=np.float32).copy()
    cut_y = int(round(1060 * k))
    fade_h = max(16, int(round(92 * k)))
    y0_f = max(0, cut_y - fade_h)
    if cut_y < head_arr.shape[0]:
        head_arr[cut_y:, :, 3] = 0.0
    yy = np.arange(y0_f, min(cut_y, head_arr.shape[0]), dtype=np.float32)
    t_f = np.clip((cut_y - yy) / float(max(1, fade_h)), 0.0, 1.0)
    t_f = t_f * t_f * (3.0 - 2.0 * t_f)
    head_arr[y0_f:min(cut_y, head_arr.shape[0]), :, 3] *= t_f[:, None]
    head_only = Image.fromarray(np.clip(head_arr, 0, 255).astype(np.uint8), 'RGBA')
    comp.alpha_composite(head_only)

    for en in ('ear_l', 'ear_r'):
        e = Image.open(os.path.join(SK, f'{en}.png')).convert('RGBA')
        em = J[en]
        e = e.resize((max(1, int(round(em['w'] * k))), max(1, int(round(em['h'] * k)))), Image.LANCZOS)
        comp.alpha_composite(e, (int(round(em['x'] * k)), int(round(em['y'] * k))))

    cone = Image.open(os.path.join(SK, 'cone.png')).convert('RGBA')
    pearl = Image.open(os.path.join(SK, 'pearl.png')).convert('RGBA')
    for b in J['buttons']:
        bx, by, br = b['cx'] * k, b['cy'] * k, b['r'] * k
        if b['id'] in ('prev', 'play', 'next', 'list', 'eq', 'fx'):
            cs = max(4, int(round(br * 1.78)))
            cc = cone.resize((cs, cs), Image.LANCZOS)
            comp.alpha_composite(cc, (int(round(bx - cs * 0.5)), int(round(by - cs * 0.5))))
        elif b['id'] in ('shuffle', 'repeat', 'sleep', 'popup'):
            ps = max(4, int(round(br * 1.85)))
            pp = pearl.resize((ps, ps), Image.LANCZOS)
            comp.alpha_composite(pp, (int(round(bx - ps * 0.5)), int(round(by - ps * 0.5))))

    bb = comp.getbbox()
    mark = comp.crop((max(0, bb[0] - 12), max(0, bb[1] - 12), min(comp.width, bb[2] + 12), min(comp.height, bb[3] + 12)))
    print(f"[brand] Head mark bbox {mark.size}")

    sq = fit_img(mark, 356, 356)
    sq_img = Image.new('RGBA', (384, 384), (0, 0, 0, 0))
    sq_img.alpha_composite(sq, ((384 - sq.width) // 2, (384 - sq.height) // 2))
    sq_img.save(os.path.join(SK, 'mark.png'), optimize=True)

    # 4. Adaptive & Legacy Launcher Icons
    fg = Image.new('RGBA', (432, 432), (0, 0, 0, 0))
    m2 = fit_img(mark, 268, 268)
    fg.alpha_composite(m2, ((432 - m2.width) // 2, (432 - m2.height) // 2 + 4))
    fg.save(os.path.join(RES, 'drawable-nodpi', 'ic_launcher_fg.png'), optimize=True)

    with open(os.path.join(RES, 'values', 'ic_launcher_bg.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="ic_launcher_bg">#0C1728</color></resources>\n')

    for dpi, px in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
        b = icon_backdrop(192, 192, 42)
        mm = fit_img(mark, 152, 152)
        b.alpha_composite(mm, ((192 - mm.width) // 2, (192 - mm.height) // 2 + 2))
        b.resize((px, px), Image.LANCZOS).save(os.path.join(RES, f'mipmap-{dpi}', 'ic_launcher.png'), optimize=True)

    b512 = icon_backdrop(512, 512, 112)
    mm = fit_img(mark, 404, 404)
    b512.alpha_composite(mm, ((512 - mm.width) // 2, (512 - mm.height) // 2 + 4))
    b512.save(os.path.join(BR, 'icon_512.png'), optimize=True)

    # 5. Store Feature Graphic (1024x500)
    fgx = icon_backdrop(1024, 500)
    mm = fit_img(mark, 430, 440)
    fgx.alpha_composite(mm, (58, (500 - mm.height) // 2))
    lg = fit_img(logo, 470, 145)
    fgx.alpha_composite(lg, (510, 148))
    dr = ImageDraw.Draw(fgx)
    dr.text((518, 314), 'Y2K 3D Liquid-Chrome Music Engine', font=pick_font(FONT_CANDIDATES, 26), fill=(185, 228, 255, 255))
    dr.text((518, 356), 'YouTube Music  •  Local Library  •  Synced Lyrics  •  Pro DSP', font=pick_font(FONT_REG_CANDIDATES, 18), fill=(125, 168, 208, 255))
    fgx.convert('RGB').save(os.path.join(BR, 'feature_graphic.png'), quality=94)
    print("[brand] All branding & icons built!")

if __name__ == '__main__':
    main()
