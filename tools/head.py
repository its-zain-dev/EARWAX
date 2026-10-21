"""Ray-marched (SDF) chrome head: ortho camera, 4 materials. Writes head.png + head.json."""
import numpy as np, json, sys, time
from PIL import Image
from art import *
W, H = (1080, 1130) if len(sys.argv) < 2 else (int(sys.argv[1]), int(sys.argv[1]) * 1130 // 1080)
S = W / 3.3; YC = 1.62

def smin(a, b, k):
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0, 1); return b * (1 - h) + a * h - k * h * (1 - h)
def ell(x, y, z, c, r):
    qx, qy, qz = (x - c[0]) / r[0], (y - c[1]) / r[1], (z - c[2]) / r[2]
    k0 = np.sqrt(qx * qx + qy * qy + qz * qz); k1 = np.sqrt((qx / r[0]) ** 2 + (qy / r[1]) ** 2 + (qz / r[2]) ** 2) + 1e-9
    return k0 * (k0 - 1) / k1
def sph(x, y, z, c, r): return np.sqrt((x - c[0]) ** 2 + (y - c[1]) ** 2 + (z - c[2]) ** 2) - r
def cap(x, y, z, a, b, r):
    px, py, pz_ = x - a[0], y - a[1], z - a[2]; bx, by, bz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
    h = np.clip((px * bx + py * by + pz_ * bz) / (bx * bx + by * by + bz * bz), 0, 1)
    return np.sqrt((px - bx * h) ** 2 + (py - by * h) ** 2 + (pz_ - bz * h) ** 2) - r
def rbox(x, y, z, c, b, r):
    qx, qy, qz = np.abs(x - c[0]) - b[0] + r, np.abs(y - c[1]) - b[1] + r, np.abs(z - c[2]) - b[2] + r
    return np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2 + np.maximum(qz, 0) ** 2) + np.minimum(np.maximum(qx, np.maximum(qy, qz)), 0) - r

SC_Y = 0.52                                   # screen centre height
CONES = [(sx, cy) for sx in (-1, 1) for cy in (0.45, 0.05, -0.35)]
POD_X = 1.30
PEARLS = [(-0.60, 1.20), (-0.21, 1.31), (0.21, 1.31), (0.60, 1.20)]
def crown_z(px, py): return 0.92 * np.sqrt(max(0.0, 1 - ((py - 0.42) / 1.10) ** 2 - (px / 0.98) ** 2))

def scene(x, y, z):
    d = smin(ell(x, y, z, (0, .42, 0), (.98, 1.10, .92)), ell(x, y, z, (0, -.55, .12), (.76, .88, .72)), .40)
    d = smin(d, ell(x, y, z, (0, -1.33, .48), (.30, .20, .26)), .22)
    d = smin(d, ell(x, y, z, (0, -1.62, 0.0), (.36, .50, .34)), .30)                        # neck
    for sx in (-1, 1):
        d = smin(d, sph(x, y, z, (sx * .52, -.80, .40), .34), .30)                          # jaw / cheek mass
        d = smin(d, sph(x, y, z, (sx * .17, -.70, .93), .105), .12)                         # nostril wings
        d = smin(d, cap(x, y, z, (sx * .80, .05, .05), (sx * 1.08, .05, .05), .21), .14)    # hinge to pod
        d = smin(d, rbox(x, y, z, (sx * POD_X, .05, .10), (.27, .64, .34), .26), .12)       # speaker pod
    d = smin(d, ell(x, y, z, (0, -.10, .74), (.58, .10, .16)), .26)                          # brow shelf under the screen
    d = smin(d, cap(x, y, z, (0, -.14, .80), (0, -.56, 1.00), .13), .16)                     # nose bridge
    d = smin(d, sph(x, y, z, (0, -.66, 1.05), .155), .14)                                    # nose tip
    d = smin(d, ell(x, y, z, (0, -.98, .84), (.30, .078, .12)), .12)                         # upper lip
    d = smin(d, ell(x, y, z, (0, -1.11, .83), (.26, .088, .12)), .12)                        # lower lip
    d = np.maximum(d, -ell(x, y, z, (0, -1.045, .96), (.30, .012, .08)))                     # mouth line
    for sx in (-1, 1): d = np.maximum(d, -ell(x, y, z, (sx * .085, -.77, 1.0), (.05, .03, .06)))   # nostrils
    bez = rbox(x, y, z, (0, SC_Y, .74), (.70, .54, .30), .26)                                # CRT bezel block on the forehead
    bez = np.maximum(bez, -rbox(x, y, z, (0, SC_Y, 1.12), (.60, .46, .14), .13))
    d = smin(d, bez, .10)
    for sx, cy in CONES:                                                                     # speaker grooves
        d = np.maximum(d, -np.maximum(np.sqrt((x - sx * POD_X) ** 2 + (y - cy) ** 2) - .225, .34 - z + 0.0))
    m = np.zeros_like(d); best = d
    def put(dd, mat):
        nonlocal best, m
        sel = dd < best; best = np.where(sel, dd, best); m = np.where(sel, mat, m)
    put(rbox(x, y, z, (0, SC_Y, .97), (.585, .445, .03), .11), 1)
    for sx, cy in CONES: put(ell(x, y, z, (sx * POD_X, cy, .36), (.175, .175, .125)), 2)
    for px, py in PEARLS: put(sph(x, y, z, (px, py, crown_z(px, py) + .03), .125), 3)
    return best, m

def march(x0, y0):
    N = x0.size; t = np.full(N, 0.0); z0 = 3.0; alive = np.arange(N); hit = np.zeros(N, bool); mind = np.full(N, 9.0); T = np.zeros(N)
    for it in range(90):
        if alive.size == 0: break
        zz = z0 - t[alive]; d, _ = scene(x0[alive], y0[alive], zz)
        mind[alive] = np.minimum(mind[alive], d)
        h = d < 0.0015; hit[alive[h]] = True; T[alive[h]] = t[alive[h]]
        t[alive] += d * 0.92
        keep = (~h) & (t[alive] < 5.6); alive = alive[keep]
    return hit, T, mind

t0 = time.time()
J, I = np.meshgrid(np.arange(W), np.arange(H)); X = ((J + .5 - W / 2) / S).ravel(); Y = ((YC * S - (I + .5)) / S).ravel()
hit, T, mind = march(X, Y)
print('march', time.time() - t0)
Z = 3.0 - T; idx = np.where(hit)[0]
rgb = np.zeros((W * H, 3)); alpha = np.clip(1 - mind / (1.1 / S), 0, 1) * (~hit); alpha[hit] = 1
e = 0.004
for s0 in range(0, idx.size, 200000):
    ii = idx[s0:s0 + 200000]; x, y, z = X[ii], Y[ii], Z[ii]
    d0, mat = scene(x, y, z)
    nx = scene(x + e, y, z)[0] - scene(x - e, y, z)[0]; ny = scene(x, y + e, z)[0] - scene(x, y - e, z)[0]; nz = scene(x, y, z + e)[0] - scene(x, y, z - e)[0]
    n = np.stack([nx, ny, nz], -1); n /= np.linalg.norm(n, axis=-1, keepdims=True) + 1e-9
    ao = np.ones(ii.size); sca = 1.0
    for h in (.04, .12, .26):
        dd = scene(x + n[:, 0] * h, y + n[:, 1] * h, z + n[:, 2] * h)[0]; ao -= np.clip(h - dd, 0, 1) * sca * 2.0; sca *= .65
    ao = np.clip(ao, 0.25, 1)
    col = shade_normals(n, ao=ao)
    gl = shade_normals(n, base=(.55, .6, .7), spec=1.4) * 0.30 + 0.015       # black glass
    cone = shade_normals(n, base=(.30, .31, .34), spec=.6, ao=ao * .8) * 0.55; ang = np.arctan2(y - np.clip(np.round((y - .05) / .40) * .40 + .05, -.35, .45), x - np.sign(x) * POD_X)
    cone *= (0.82 + 0.18 * np.sin(ang * 36))[:, None]
    pearl = shade_normals(n, base=(.80, .86, 1.0), spec=2.0, ao=ao); pearl = pearl * np.array([.78, .88, 1.1])
    out = np.where((mat == 1)[:, None], gl, np.where((mat == 2)[:, None], cone, np.where((mat == 3)[:, None], pearl, col)))
    rgb[ii] = out
img = np.dstack([rgb.reshape(H, W, 3), alpha.reshape(H, W)]); img[..., :3] *= (img[..., 3:4] > 0)
img = finish(img)
Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGBA').save(sys.argv[2] if len(sys.argv) > 2 else 'head.png')
def px(wx, wy): return [round(wx * S + W / 2, 1), round(YC * S - wy * S, 1)]
buttons = []
for i, (sx, cy) in enumerate(CONES):
    cx, cyy = px(sx * POD_X, cy); buttons.append(dict(id=['prev', 'play', 'next', 'list', 'eq', 'fx'][i], cx=cx, cy=cyy, r=round(.20 * S, 1)))
for (pxx, pyy), bid in zip(PEARLS, ['shuffle', 'repeat', 'sleep', 'popup']):
    cx, cyy = px(pxx, pyy); buttons.append(dict(id=bid, cx=cx, cy=cyy, r=round(.17 * S, 1)))
sc = px(-.57, SC_Y + .41)
json.dump(dict(w=W, h=H, screen=dict(x=sc[0], y=sc[1], w=round(1.14 * S, 1), h=round(.82 * S, 1), r=round(.08 * S, 1)), buttons=buttons), open(sys.argv[3] if len(sys.argv) > 3 else 'head.json', 'w'))
print('done', time.time() - t0)
