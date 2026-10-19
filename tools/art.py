"""Shared shading helpers for the EARWAX skin generator (numpy + scipy + PIL only)."""
import numpy as np
from scipy import ndimage as ndi

def sstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t)

def env(r):
    """Chrome studio environment: r = (...,3) unit reflection vectors (x right, y up, z to viewer) -> RGB 0..1."""
    x, y, z = r[..., 0], r[..., 1], r[..., 2]
    sky = 0.30 + 0.55 * sstep(-0.05, 0.95, y)
    floor = 0.06 + 0.22 * sstep(-1.0, 0.0, y)
    lum = np.where(y > 0, sky, floor)
    lum = lum + 0.55 * np.exp(-(y / 0.035) ** 2)                      # horizon line
    def box(x0, x1, y0, y1, v, s=0.07):
        return v * sstep(x0 - s, x0 + s, x) * (1 - sstep(x1 - s, x1 + s, x)) * sstep(y0 - s, y0 + s, y) * (1 - sstep(y1 - s, y1 + s, y)) * (z > 0.05)
    lum = lum + box(-0.95, -0.35, 0.25, 0.95, 0.95) + box(0.45, 0.85, -0.25, 0.55, 0.55) + box(-0.3, 0.3, 0.8, 1.1, 0.5)
    warm = np.array([1.0, 0.97, 0.93]); cool = np.array([0.90, 0.95, 1.06])
    t = sstep(-0.3, 0.6, y)[..., None]
    return np.clip(lum[..., None] * (warm * (1 - t) + cool * t), 0, 1.4)

def tone(c):
    return np.clip(1 - np.exp(-c * 1.35), 0, 1) ** (1 / 1.05)

def shade_normals(n, base=(0.93, 0.95, 0.99), spec=1.0, ao=1.0):
    n = n / np.linalg.norm(n, axis=-1, keepdims=True)
    r = np.stack([2 * n[..., 2] * n[..., 0], 2 * n[..., 2] * n[..., 1], 2 * n[..., 2] ** 2 - 1], -1)
    c = env(r) * np.array(base)
    L = np.array([-0.45, 0.65, 0.62]); L /= np.linalg.norm(L)
    H = L + np.array([0, 0, 1.0]); H /= np.linalg.norm(H)
    sp = np.clip((n * H).sum(-1), 0, 1) ** 110 * 1.3 * spec
    dif = np.clip((n * L).sum(-1), 0, 1)[..., None] * 0.12
    return tone((c + dif) * (0.55 + 0.45 * np.asarray(ao))[..., None] + sp[..., None])

def height_to_normals(h, k=1.0):
    gy, gx = np.gradient(h)
    return np.dstack([-gx * k, gy * k, np.ones_like(h)])

def edge_profile(d, R):
    t = np.clip(d / R, 0, 1); return R * np.sqrt(1 - (1 - t) ** 2)

def pillow_height(mask, R, dome=0.35):
    d = ndi.distance_transform_edt(mask)
    big = ndi.gaussian_filter(np.minimum(d, R * 6), R * 1.6)
    return edge_profile(d, R) + dome * big, d

def carve(h, hole, depth, edge):
    d = ndi.distance_transform_edt(hole)
    return h - depth * edge_profile(d, edge) / edge, d

def gel_button(size, hue=(0.12, 0.30, 0.86), glow=1.0):
    """Glossy 3D gel sphere RGBA (size x size)."""
    s = size; Y, X = np.mgrid[0:s, 0:s]; x = (X - s / 2 + 0.5) / (s / 2); y = -(Y - s / 2 + 0.5) / (s / 2)
    rr = x * x + y * y; inside = rr <= 1.0
    z = np.sqrt(np.clip(1 - rr, 0, 1)); n = np.stack([x, y, z], -1)
    L = np.array([-0.4, 0.6, 0.7]); L /= np.linalg.norm(L)
    dif = np.clip((n * L).sum(-1), 0, 1); rim = (1 - z) ** 2
    base = np.array(hue)
    col = base[None, None, :] * (0.35 + 0.9 * dif[..., None]) + np.array([0.2, 0.4, 1.0]) * 0.35 * rim[..., None] * glow
    hl = np.exp(-(((x + 0.28) / 0.38) ** 2 + ((y - 0.42) / 0.20) ** 2) * 2.2)
    col = col + hl[..., None] * 0.85 + np.clip((n * ((L + [0, 0, 1]) / np.linalg.norm(L + [0, 0, 1]))).sum(-1), 0, 1)[..., None] ** 60 * 0.6
    a = np.clip((1 - np.sqrt(rr)) * s / 2, 0, 1)
    return np.dstack([np.clip(col, 0, 1), a])

def lcd_floor(shape, d_in, edge, top=(0.70, 0.82, 0.66), bot=(0.58, 0.72, 0.56)):
    H, W = shape; yy = np.linspace(0, 1, H)[:, None, None]
    c = np.array(top) * (1 - yy) + np.array(bot) * yy
    c = np.broadcast_to(c, (H, W, 3)).copy()
    ao = (0.62 + 0.38 * sstep(0, edge * 1.2, d_in))[..., None]
    return c * ao

def nblur(rgb, a, sigma):
    """Alpha-aware gaussian blur (no dark fringes at silhouette edges)."""
    num = ndi.gaussian_filter(rgb * a[..., None], (sigma, sigma, 0)); den = ndi.gaussian_filter(a, sigma)[..., None]
    return num / np.maximum(den, 1e-4)

def finish(rgba, bloom=0.30, haze=0.10):
    """Old-UI softness: bloom on highlights + a touch of soft focus, applied inside the silhouette only."""
    rgb, a = rgba[..., :3].astype(np.float32), rgba[..., 3].astype(np.float32)
    lum = rgb.mean(-1); hi = np.clip((lum - 0.74) / 0.26, 0, 1)[..., None] * rgb
    glow = nblur(hi, a, 7) * 0.6 + nblur(hi, a, 18) * 0.4
    soft = nblur(rgb, a, 2.2)
    out = np.clip(rgb * (1 - haze) + soft * haze + glow * bloom, 0, 1)
    return np.dstack([out * (a[..., None] > 0), a])
