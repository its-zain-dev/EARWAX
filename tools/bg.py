"""Soft blurred backdrop for the main screen (steel-blue haze, cyan glow behind the head, light grain)."""
import numpy as np, sys
from PIL import Image
from scipy import ndimage as ndi
W, H = 540, 1170
Y, X = np.mgrid[0:H, 0:W].astype(np.float32); u, v = X / W, Y / H
top, mid, bot = np.array([26, 42, 64]), np.array([15, 24, 37]), np.array([8, 12, 19])
t = v[..., None]; img = np.where(t < .5, top * (1 - t * 2) + mid * (t * 2), mid * (1 - (t - .5) * 2) + bot * ((t - .5) * 2)).astype(np.float32)
def glow(cx, cy, rx, ry, col, k): g = np.exp(-(((u - cx) / rx) ** 2 + ((v - cy) / ry) ** 2)); return g[..., None] * np.array(col) * k
img += glow(.5, .20, .55, .20, (70, 140, 190), .55) + glow(.15, .85, .6, .18, (40, 80, 140), .45) + glow(.9, .55, .4, .25, (70, 110, 150), .25)
ray = np.clip(np.sin((u * 1.2 - v * .9) * 6) * .5 + .5, 0, 1) ** 3; img += ray[..., None] * np.array([40, 70, 100]) * .12
img = ndi.gaussian_filter(img, (6, 6, 0)); img += np.random.default_rng(1).normal(0, 1.6, img.shape)
Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(sys.argv[1] if len(sys.argv) > 1 else 'bg.jpg', quality=88)
