"""EARWAX visual identity: chrome wordmark, head mark, launcher icons (legacy + adaptive), feature graphic, notification vectors.
Run AFTER bust.py (it crops the head out of bust.png / ear_*.png).  python3 brand.py"""
import numpy as np, json, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from scipy import ndimage as ndi
import matplotlib
from art import *

HERE = os.path.dirname(os.path.abspath(__file__)); MAIN = HERE + '/../app/src/main'; SK = MAIN + '/assets/skins'; RES = MAIN + '/res'
BR = HERE + '/../branding'
for d in (RES + '/drawable', RES + '/drawable-nodpi', RES + '/mipmap-xxxhdpi', RES + '/mipmap-xxhdpi', RES + '/mipmap-xhdpi', RES + '/mipmap-hdpi', RES + '/mipmap-mdpi',
          RES + '/mipmap-anydpi-v26', RES + '/values', RES + '/values-ar', BR):
    os.makedirs(d, exist_ok=True)
FONT = os.path.join(matplotlib.get_data_path(), 'fonts', 'ttf', 'DejaVuSans-Bold.ttf')
SC = 2

# ---------------------------------------------------------------- chrome wordmark
def wordmark(txt, size, track=0.10, pad=40):
    f = ImageFont.truetype(FONT, size * SC); adv = [f.getlength(c) + size * SC * track for c in txt]
    w = int(sum(adv) + 2 * pad * SC); h = int(size * SC * 1.5 + 2 * pad * SC)
    im = Image.new('L', (w, h), 0); d = ImageDraw.Draw(im); x = pad * SC
    for c, a in zip(txt, adv): d.text((x, h / 2), c, font=f, fill=255, anchor='lm'); x += a
    mask = np.array(im) > 127
    hh, _ = pillow_height(mask, size * SC * 0.16, 0.5)
    rgb = shade_normals(height_to_normals(hh, 1.4))
    a = ndi.gaussian_filter(mask.astype(np.float32), 0.8)
    pm = np.dstack([rgb * a[..., None], a]); out = Image.fromarray((np.clip(pm, 0, 1) * 255).astype(np.uint8), 'RGBA').resize((w // SC, h // SC), Image.LANCZOS)
    arr = np.array(out).astype(np.float32) / 255; al = arr[..., 3:4]; col = np.where(al > .002, arr[..., :3] / np.maximum(al, .002), 0)
    fin = finish(np.dstack([np.clip(col, 0, 1), al[..., 0]]), bloom=.25, haze=.05)
    return Image.fromarray((fin * 255).astype(np.uint8), 'RGBA')

logo = wordmark('EARWAX', 96); bb = logo.getbbox(); logo = logo.crop((bb[0] - 12, bb[1] - 12, bb[2] + 12, bb[3] + 12)); logo.save(SK + '/logo.png'); print('logo', logo.size)

# ---------------------------------------------------------------- head mark (ears + head, no chest)
bust = Image.open(SK + '/bust.png').convert('RGBA'); J = json.load(open(SK + '/bust.json'))
comp = Image.new('RGBA', bust.size, (0, 0, 0, 0))
for n in ('ear_l', 'ear_r'):
    e = Image.open(f'{SK}/{n}.png').convert('RGBA'); comp.alpha_composite(e, (int(round(J[n]['x'])), int(round(J[n]['y']))))
cone = Image.open(f'{SK}/cone.png').convert('RGBA'); cs = int(round(J['cone']['w'])); cone = cone.resize((cs, cs), Image.LANCZOS)
for b in J['buttons']:
    if b['id'] in ('prev', 'play', 'next', 'list', 'eq', 'fx'): comp.alpha_composite(cone, (int(round(b['cx'] - cs / 2)), int(round(b['cy'] - cs / 2))))
head_only = bust.copy(); head_only.paste((0, 0, 0, 0), (0, 1150, bust.width, bust.height))        # drop the chest
comp.alpha_composite(head_only)
bb = comp.getbbox(); mark = comp.crop((bb[0] - 10, bb[1] - 10, bb[2] + 10, bb[3] + 10)); print('mark bbox', mark.size)

def fit(img, box_w, box_h):
    k = min(box_w / img.width, box_h / img.height); return img.resize((max(1, int(img.width * k)), max(1, int(img.height * k))), Image.LANCZOS)
def backdrop(w, h, radius=0):
    Y, X = np.mgrid[0:h, 0:w].astype(np.float32); u, v = X / w, Y / h
    c = np.array([14, 20, 32]) * (1 - v[..., None]) + np.array([30, 52, 84]) * v[..., None]
    g = np.exp(-(((u - .5) / .55) ** 2 + ((v - .38) / .45) ** 2))[..., None] * np.array([60, 120, 170]) * .55
    im = Image.fromarray(np.clip(c + g, 0, 255).astype(np.uint8), 'RGB').convert('RGBA')
    if radius:
        m = Image.new('L', (w, h), 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], radius, fill=255); im.putalpha(m)
    return im

sq = fit(mark, 360, 360); sq_img = Image.new('RGBA', (384, 384), (0, 0, 0, 0)); sq_img.alpha_composite(sq, ((384 - sq.width) // 2, (384 - sq.height) // 2)); sq_img.save(SK + '/mark.png')

# adaptive launcher icon: foreground inside the 66dp safe zone (background colour comes from res/values/ic_launcher_bg.xml)
fg = Image.new('RGBA', (432, 432), (0, 0, 0, 0)); m2 = fit(mark, 262, 262); fg.alpha_composite(m2, ((432 - m2.width) // 2, (432 - m2.height) // 2 + 4)); fg.save(RES + '/drawable-nodpi/ic_launcher_fg.png')
open(RES + '/values/ic_launcher_bg.xml', 'w').write('<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="ic_launcher_bg">#14202F</color></resources>\n')
adaptive = '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/ic_launcher_bg"/>\n    <foreground android:drawable="@drawable/ic_launcher_fg"/>\n</adaptive-icon>\n'
open(RES + '/mipmap-anydpi-v26/ic_launcher.xml', 'w').write(adaptive); open(RES + '/mipmap-anydpi-v26/ic_launcher_round.xml', 'w').write(adaptive)
for dpi, px in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):                  # legacy (API < 26) icons
    b = backdrop(192, 192, 40); mm = fit(mark, 150, 150); b.alpha_composite(mm, ((192 - mm.width) // 2, (192 - mm.height) // 2 + 2)); b.resize((px, px), Image.LANCZOS).save(f'{RES}/mipmap-{dpi}/ic_launcher.png')
b = backdrop(512, 512, 110); mm = fit(mark, 400, 400); b.alpha_composite(mm, ((512 - mm.width) // 2, (512 - mm.height) // 2 + 4)); b.save(BR + '/icon_512.png')

# feature graphic 1024x500 (store banner / README header)
fgx = backdrop(1024, 500); mm = fit(mark, 420, 440); fgx.alpha_composite(mm, (60, (500 - mm.height) // 2))
lg = fit(logo, 480, 150); fgx.alpha_composite(lg, (520, 150))
tag = ImageDraw.Draw(fgx); tf = ImageFont.truetype(FONT.replace('-Bold', ''), 30)
tag.text((528, 316), 'Chrome 3-D music player', font=tf, fill=(190, 214, 240, 255)); tag.text((528, 360), 'local library  -  internet radio  -  real DSP', font=ImageFont.truetype(FONT.replace('-Bold', ''), 22), fill=(120, 150, 185, 255))
fgx.convert('RGB').save(BR + '/feature_graphic.png')

# ---------------------------------------------------------------- vector icons (Material icon paths, Apache-2.0)
def vec(name, path, w=24):
    open(f'{RES}/drawable/{name}.xml', 'w').write(f'<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{w}dp" android:height="{w}dp" android:viewportWidth="24" android:viewportHeight="24"><path android:fillColor="#FFFFFFFF" android:pathData="{path}"/></vector>\n')
vec('ic_stat', 'M12,3a9,9 0 0 0 -9,9v7c0,1.1 0.9,2 2,2h4v-8H5v-1a7,7 0 0 1 14,0v1h-4v8h4c1.1,0 2,-0.9 2,-2v-7a9,9 0 0 0 -9,-9z')
vec('ic_prev', 'M6,6h2v12H6zM9.5,12l8.5,6V6z'); vec('ic_next', 'M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z')
vec('ic_play', 'M8,5v14l11,-7z'); vec('ic_pause', 'M6,19h4V5H6v14zM14,5v14h4V5h-4z')
vec('ic_popup', 'M19,11h-8v6h8v-6zM23,19V4.98C23,3.88 22.1,3 21,3H3C1.9,3 1,3.88 1,4.98V19c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2zM21,19.02H3V4.97h18v14.05z')
vec('ic_close', 'M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z')
open(RES + '/drawable/splash_bg.xml', 'w').write('<?xml version="1.0" encoding="utf-8"?>\n<layer-list xmlns:android="http://schemas.android.com/apk/res/android">\n    <item><shape><solid android:color="#10141C"/></shape></item>\n    <item android:gravity="center" android:width="150dp" android:height="150dp"><bitmap android:src="@drawable/ic_launcher_fg" android:gravity="fill"/></item>\n</layer-list>\n')
print('brand ok')
