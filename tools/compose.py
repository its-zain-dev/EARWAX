"""Debug helper: composite bust + ears on a dark backdrop (ears behind the bust, like the app draws them)."""
import json, sys
from PIL import Image
d = sys.argv[1]; out = sys.argv[2]
b = Image.open(f'{d}/bust.png').convert('RGBA'); j = json.load(open(f'{d}/bust.json')); k = b.width / 1080.0
bg = Image.new('RGBA', b.size, (24, 30, 44, 255))
for n in ('ear_l', 'ear_r'):
    e = Image.open(f'{d}/{n}.png').convert('RGBA'); m = j[n]; bg.alpha_composite(e, (int(round(m['x'] * k)), int(round(m['y'] * k))))
bg.alpha_composite(b); bg.convert('RGB').save(out)
