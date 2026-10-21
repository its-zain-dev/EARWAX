"""EARWAX bust: head + neck + chest as ONE ray-marched chrome sculpture (one light rig, one environment),
plus the two speaker "ear" pods rendered as separate layers so the app can make them vibrate.

Proportions follow the classic Loomis / rule-of-thirds construction (see ARCHITECTURE.md, "Head construction"):
  * hairline -> brow -> nose base -> chin are three equal steps; the forehead screen (visor) covers the first two
<<<<<<< HEAD
  * ears (pods) sit in the middle third, tucked against the side of the head on a sculpted root (no floating tube)
  * mouth line sits 1/3 of the way from the nose base to the chin, mouth width ~ 1.5 x nose width,
    lower lip ~ 1.45 x the height of the upper lip
  * bizygomatic (cheekbone) width is the widest part of the face; the jaw angle is ~0.62 of it
3.0: four devil horns (the shuffle / repeat / sleep / popup buttons) grow out of the crown; the speaker cones are separate
sprites (cone.png) so the app can pump each one with its own frequency band.
=======
  * ears (pods) sit in the middle third; the hinge is at ear height
  * mouth line sits 1/3 of the way from the nose base to the chin, mouth width ~ 1.5 x nose width,
    lower lip ~ 1.45 x the height of the upper lip
  * bizygomatic (cheekbone) width is the widest part of the face; the jaw angle is ~0.62 of it
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
Usage:  python3 bust.py --w 1080 --out ../app/src/main/assets/skins
        python3 bust.py --w 360 --out /tmp/t [--side]      (fast preview; --side renders the profile)
"""
import numpy as np, json, os, argparse, time
from PIL import Image
from art import *

ap = argparse.ArgumentParser()
ap.add_argument('--w', type=int, default=1080)
ap.add_argument('--out', default='.')
ap.add_argument('--side', action='store_true', help='render the profile (debug)')
ap.add_argument('--part', default='all', help='all | bust | ears')
A = ap.parse_args()
<<<<<<< HEAD
W = A.w; H = W * 2100 // 1080; S = W / 3.4; S0 = 1080 / 3.4; YC = -3.30 + 1610.0 / S0; K = 1080.0 / W
K0 = (1080 / 3.3) / S0                         # the forehead screen assembly keeps its old pixel size
=======
W = A.w; H = W * 2100 // 1080; S = W / 3.3; S0 = 1080 / 3.3; YC = 1.62; K = 1080.0 / W
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
SIDE = A.side

# ---------------------------------------------------------------- SDF toolbox
def smin(a, b, k):
    h = np.clip(0.5 + 0.5 * (b - a) / k, 0, 1); return b * (1 - h) + a * h - k * h * (1 - h)
def smax(a, b, k): return -smin(-a, -b, k)
def ell(x, y, z, c, r):
    qx, qy, qz = (x - c[0]) / r[0], (y - c[1]) / r[1], (z - c[2]) / r[2]
    k0 = np.sqrt(qx * qx + qy * qy + qz * qz); k1 = np.sqrt((qx / r[0]) ** 2 + (qy / r[1]) ** 2 + (qz / r[2]) ** 2) + 1e-9
    return k0 * (k0 - 1) / k1
def sph(x, y, z, c, r): return np.sqrt((x - c[0]) ** 2 + (y - c[1]) ** 2 + (z - c[2]) ** 2) - r
def cap(x, y, z, a, b, r):
    px, py, pz = x - a[0], y - a[1], z - a[2]; bx, by, bz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
    h = np.clip((px * bx + py * by + pz * bz) / (bx * bx + by * by + bz * bz), 0, 1)
    return np.sqrt((px - bx * h) ** 2 + (py - by * h) ** 2 + (pz - bz * h) ** 2) - r
<<<<<<< HEAD
def tcap(x, y, z, a, b, r1, r2):
    """Capsule whose radius goes from r1 (at a) to r2 (at b): one link of a horn."""
    px, py, pz = x - a[0], y - a[1], z - a[2]; bx, by, bz = b[0] - a[0], b[1] - a[1], b[2] - a[2]
    h = np.clip((px * bx + py * by + pz * bz) / (bx * bx + by * by + bz * bz), 0, 1)
    return np.sqrt((px - bx * h) ** 2 + (py - by * h) ** 2 + (pz - bz * h) ** 2) - (r1 + (r2 - r1) * h)
def cyl(x, y, z, c, r, hz):
    dx = np.sqrt((x - c[0]) ** 2 + (y - c[1]) ** 2) - r; dz = np.abs(z - c[2]) - hz
    return np.minimum(np.maximum(dx, dz), 0) + np.sqrt(np.maximum(dx, 0) ** 2 + np.maximum(dz, 0) ** 2)
=======
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
def rbox(x, y, z, c, b, r):
    qx, qy, qz = np.abs(x - c[0]) - b[0] + r, np.abs(y - c[1]) - b[1] + r, np.abs(z - c[2]) - b[2] + r
    return np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2 + np.maximum(qz, 0) ** 2) + np.minimum(np.maximum(qx, np.maximum(qy, qz)), 0) - r

# ---------------------------------------------------------------- layout constants (world units, 1 unit = 327 design px)
SC_Y = 0.52                                     # forehead screen centre
Y_BROW, Y_NOSE, Y_CHIN = 0.06, -0.66, -1.36     # the thirds of the face (0.72 each)
Y_MOUTH = Y_NOSE + (Y_CHIN - Y_NOSE) / 3.0 - 0.02   # mouth line: 1/3 from nose base to chin
<<<<<<< HEAD
POD_X, POD_Y = 1.14, -0.26
POD_HX, POD_HY = .23, .54                        # pod half width / half height
CONE_R, SOCK_R, SOCK_Z = .125, .15, .20          # speaker cone radius, socket radius, socket floor depth
CONES = [(sx, POD_Y + dy) for sx in (-1, 1) for dy in (.34, 0.0, -.34)]
PEARLS = [(-.58, 1.20), (-.20, 1.32), (.20, 1.32), (.58, 1.20)]   # horn roots: outer pair (big, curved), inner pair (small)
=======
POD_X, POD_Y = 1.31, -0.12
CONES = [(sx, cy) for sx in (-1, 1) for cy in (0.30, -0.12, -0.54)]
PEARLS = [(-.58, 1.20), (-.20, 1.32), (.20, 1.32), (.58, 1.20)]
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
PANEL = dict(x=115, y=1290, w=850, h=640, r=64)                      # design px
PCY = YC - (PANEL['y'] + PANEL['h'] / 2) / S0; PHX = PANEL['w'] / 2 / S0; PHY = PANEL['h'] / 2 / S0
FRONT = 0.36                                    # chest front plane

def skull_z(px, py): return -.02 + .92 * np.sqrt(max(0.0, 1 - ((py - .45) / 1.08) ** 2 - (px / .96) ** 2))
def skull_n(px, py):
    z = skull_z(px, py); n = np.array([px / .96 ** 2, (py - .45) / 1.08 ** 2, (z + .02) / .92 ** 2]); return n / np.linalg.norm(n)
PEARL_P = [(np.array([px, py, skull_z(px, py)]), skull_n(px, py)) for px, py in PEARLS]

# ---------------------------------------------------------------- the head
def head(x, y, z):
    d = ell(x, y, z, (0, .45, -.02), (.96, 1.08, .92))                                         # cranium
    d = smin(d, ell(x, y, z, (0, -.30, .04), (.86, .50, .72)), .30)                            # mid-face block (cheekbones = widest part)
    d = smin(d, ell(x, y, z, (0, -.86, .20), (.56, .54, .62)), .26)                            # lower face, narrower than the cheekbones
    for sx in (-1, 1):
        d = smin(d, cap(x, y, z, (sx * .46, -.84, .04), (sx * .22, -1.25, .56), .16), .12)    # mandible: jaw angle -> chin
        d = smin(d, cap(x, y, z, (sx * .50, -.12, .46), (sx * .80, -.06, .16), .12), .12)      # zygomatic arch (cheekbone ridge)
        d = smin(d, cap(x, y, z, (sx * .44, -.30, .10), (sx * .44, -.78, .08), .12), .14)      # masseter (jaw muscle), very light
        d = smax(d, -ell(x, y, z, (sx * .52, -.56, .64), (.20, .26, .18)), .30)                # very soft hollow under the cheekbone
    d = smin(d, ell(x, y, z, (0, -1.20, .64), (.27, .15, .17)), .12)                           # chin (a little forward)
    d = smin(d, ell(x, y, z, (0, -1.80, -.02), (.40, .64, .34)), .26)                          # neck
    # nose: bridge, tip, wings (small, tidy)
    d = smin(d, cap(x, y, z, (0, -.10, .82), (0, -.36, .93), .085), .12)                           # bridge (narrow)
    d = smin(d, cap(x, y, z, (0, -.36, .93), (0, -.55, 1.02), .125), .12)                          # nose body (wider)
    d = smin(d, sph(x, y, z, (0, -.58, 1.04), .13), .12)
    for sx in (-1, 1):
        d = smin(d, sph(x, y, z, (sx * .145, -.585, .93), .085), .09)
        d = smax(d, -ell(x, y, z, (sx * .072, -.655, .985), (.042, .03, .06)), .02)             # nostrils
    # lips: small, flush with the face (blended, not stuck on)
    d = smin(d, ell(x, y, z, (0, Y_MOUTH + .035, .81), (.255, .038, .085)), .07)                 # upper lip
    d = smin(d, ell(x, y, z, (0, Y_MOUTH - .045, .81), (.225, .054, .095)), .07)                 # lower lip (~1.45 x upper)
    d = smax(d, -ell(x, y, z, (0, Y_MOUTH, .92), (.27, .009, .10)), .014)                        # mouth line
    d = smax(d, -ell(x, y, z, (0, Y_MOUTH - .14, .82), (.16, .02, .08)), .10)                    # faint groove under the lower lip
    # CRT bezel (visor) over brow and eyes
<<<<<<< HEAD
    bez = rbox(x, y, z, (0, SC_Y, .74), (.70 * K0, .54 * K0, .30), .26 * K0)
    bez = np.maximum(bez, -rbox(x, y, z, (0, SC_Y, 1.12), (.60 * K0, .46 * K0, .14), .13 * K0))
    d = smin(d, bez, .10)
    # ear roots: the speaker pods are attached to the head the way ears are, on a soft bump of the skull (no tube, no gap)
    for sx in (-1, 1):
        d = smin(d, ell(x, y, z, (sx * .85, POD_Y + .03, -.02), (.10, .33, .22)), .20)
=======
    bez = rbox(x, y, z, (0, SC_Y, .74), (.70, .54, .30), .26)
    bez = np.maximum(bez, -rbox(x, y, z, (0, SC_Y, 1.12), (.60, .46, .14), .13))
    d = smin(d, bez, .10)
    # collars for the four crown buttons: a small boss that grows out of the skull, so the dome is seated, not half buried
    for P, N in PEARL_P:
        d = smin(d, cap(x, y, z, tuple(P - N * .07), tuple(P + N * .035), .155), .06)
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
    return d

def rrect_ext(x, y, z, c, hx, hy, hz, r, rz):
    """Extruded rounded rectangle: big corner radius `r` in the xy-plane, small edge radius `rz` on the faces (keeps the front flat)."""
    qx, qy = np.abs(x - c[0]) - hx + r, np.abs(y - c[1]) - hy + r
    d2 = np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2) + np.minimum(np.maximum(qx, qy), 0) - r
    wz = np.abs(z - c[2]) - hz + rz; w1 = d2 + rz
    return np.sqrt(np.maximum(w1, 0) ** 2 + np.maximum(wz, 0) ** 2) + np.minimum(np.maximum(w1, wz), 0) - rz

def chest(x, y, z):
    d = rrect_ext(x, y, z, (0, -3.35, -.05), 1.52, 1.40, .41, .62, .16)
    d = smin(d, ell(x, y, z, (0, -2.16, -.02), (1.12, .50, .40)), .38)                          # trapezius slope into the neck
    cut = rbox(x, y, z, (0, PCY, FRONT + .20), (PHX, PHY, .30), .18)                           # panel recess (floor at FRONT-.10)
    d = smax(d, -cut, .09)                                                                      # rounded lip around the panel
    for i in range(9):                                                                         # speaker slots (below the panel, on the flat front)
        sx = (300 + i * 56 + 13 - 540) / S0
        d = smax(d, -rbox(x, y, z, (sx, YC - 2008 / S0, FRONT + .12), (.038, .075, .2), .038), .02)
    return d

<<<<<<< HEAD
# ---------------------------------------------------------------- the horns (right side; the left side is mirrored)
def bez3(p, n):
    t = np.linspace(0, 1, n + 1)[:, None]; p = np.array(p, float)
    return (1 - t) ** 3 * p[0] + 3 * (1 - t) ** 2 * t * p[1] + 3 * (1 - t) * t ** 2 * p[2] + t ** 3 * p[3]
HORN_BIG = (bez3([(.555, 1.165, .33), (.86, 1.17, .50), (1.06, 1.36, .36), (.90, 1.61, .14)], 16), .162, .010)    # outer pair: crescent, tip curls inwards
HORN_SMALL = (bez3([(.200, 1.295, .49), (.25, 1.40, .50), (.36, 1.50, .40), (.27, 1.64, .22)], 13), .130, .010)     # inner pair: short, straighter
def horns_d(x, y, z):
    out = np.full_like(x, 9.0); sel = (y > .90) & (np.abs(x) < 1.25)
    if not sel.any(): return out
    xs, ys, zs = x[sel], y[sel], z[sel]; dd = np.full_like(xs, 9.0)
    for (pts, r0, r1) in (HORN_BIG, HORN_SMALL):
        n = len(pts) - 1
        for sx in (-1, 1):
            for i in range(n):
                t0, t1 = i / n, (i + 1) / n
                ra, rb = r0 * (1 - t0) ** 1.15 + r1, r0 * (1 - t1) ** 1.15 + r1
                dd = smin(dd, tcap(xs, ys, zs, (sx * pts[i][0], pts[i][1], pts[i][2]), (sx * pts[i + 1][0], pts[i + 1][1], pts[i + 1][2]), ra, rb), .025)
    out[sel] = dd
    return out

=======
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
def bust(x, y, z):
    d = smin(head(x, y, z), chest(x, y, z), .30)
    m = np.zeros_like(d); best = d
    def put(dd, mat):
        nonlocal best, m
        sel = dd < best; best = np.where(sel, dd, best); m = np.where(sel, mat, m)
<<<<<<< HEAD
    put(rbox(x, y, z, (0, SC_Y, .97), (.585 * K0, .445 * K0, .03), .11 * K0), 1)                      # forehead glass
    put(rbox(x, y, z, (0, PCY, FRONT - .10 + .014), (PHX - .05, PHY - .05, .014), .12), 1)           # chest panel glass
    put(horns_d(x, y, z), 4)                                                                           # the four devil horns (= buttons)
=======
    put(rbox(x, y, z, (0, SC_Y, .97), (.585, .445, .03), .11), 1)                                     # forehead glass
    put(rbox(x, y, z, (0, PCY, FRONT - .10 + .014), (PHX - .05, PHY - .05, .014), .12), 1)           # chest panel glass
    for P, N in PEARL_P: put(sph(x, y, z, tuple(P + N * .045), .118), 3)                               # crown buttons
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
    return best, m

# ---------------------------------------------------------------- the ears (rendered as their own layers)
def pods_d(x, y, z):
    d = np.full_like(x, 9.0)
    for sx in (-1, 1):
<<<<<<< HEAD
        d = np.minimum(d, rbox(x, y, z, (sx * POD_X, POD_Y, .02), (POD_HX, POD_HY, .26), .20))     # pod body, tucked against the head
    for sx, cy in CONES:
        rho = np.sqrt((x - sx * POD_X) ** 2 + (y - cy) ** 2)
        d = smax(d, -np.maximum(rho - SOCK_R, SOCK_Z - z), .012)                                    # socket for the cone sprite
        d = smax(d, -np.maximum(np.abs(rho - .187) - .010, .272 - z), .008)                         # thin ring groove around it
=======
        d = np.minimum(d, cap(x, y, z, (sx * .70, -.30, .02), (sx * 1.12, -.30, .02), .165))        # hinge arm
        d = np.minimum(d, rbox(x, y, z, (sx * POD_X, POD_Y, .02), (.24, .66, .30), .20))            # pod
    for sx, cy in CONES:                                                                       # thin ring groove around every cone
        rho = np.sqrt((x - sx * POD_X) ** 2 + (y - cy) ** 2)
        d = smax(d, -np.maximum(np.abs(rho - .19) - .014, .275 - z), .01)
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
    return d

def pods(x, y, z):
    d = pods_d(x, y, z); m = np.zeros_like(d); best = d
<<<<<<< HEAD
    for sx, cy in CONES:                                                                            # dark glass floor of every socket
        dd = cyl(x, y, z, (sx * POD_X, cy, SOCK_Z + .004), SOCK_R - .004, .004); sel = dd < best; best = np.where(sel, dd, best); m = np.where(sel, 1, m)
=======
    for sx, cy in CONES:
        dd = ell(x, y, z, (sx * POD_X, cy, .30), (.165, .165, .165)); sel = dd < best; best = np.where(sel, dd, best); m = np.where(sel, 2, m)
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
    return best, m

def both(x, y, z): return np.minimum(bust(x, y, z)[0], pods(x, y, z)[0])

def wrap(f):
    if not SIDE: return f
    return lambda x, y, z: f(-z, y, x)

# ---------------------------------------------------------------- renderer
def march(f, x0, y0):
    N = x0.size; t = np.zeros(N); z0 = 3.0; alive = np.arange(N); hit = np.zeros(N, bool); mind = np.full(N, 9.0); T = np.zeros(N)
    for it in range(110):
        if alive.size == 0: break
        zz = z0 - t[alive]; d = f(x0[alive], y0[alive], zz)[0]
        mind[alive] = np.minimum(mind[alive], d)
        h = d < 0.0015; hit[alive[h]] = True; T[alive[h]] = t[alive[h]]
        t[alive] += d * 0.92
        keep = (~h) & (t[alive] < 6.0); alive = alive[keep]
    return hit, T, mind

def layer(part, ao_scene, X, Y, sel):
    """Render `part` for the pixels in `sel` (bool mask); AO comes from `ao_scene` so the two layers share contact shadows."""
    n = X.size; rgb = np.zeros((n, 3)); alpha = np.zeros(n)
    idx = np.where(sel)[0]
    hit, T, mind = march(part, X[idx], Y[idx])
    alpha[idx] = np.clip(1 - mind / (1.1 / S), 0, 1) * (~hit); alpha[idx[hit]] = 1
    gi = idx[hit]; Z = 3.0 - T[hit]; e = 0.004
    for s0 in range(0, gi.size, 150000):
        ii = gi[s0:s0 + 150000]; x, y = X[ii], Y[ii]; z = Z[s0:s0 + 150000]
        mat = part(x, y, z)[1]
        nx = part(x + e, y, z)[0] - part(x - e, y, z)[0]; ny = part(x, y + e, z)[0] - part(x, y - e, z)[0]; nz = part(x, y, z + e)[0] - part(x, y, z - e)[0]
        nn = np.stack([nx, ny, nz], -1); nn /= np.linalg.norm(nn, axis=-1, keepdims=True) + 1e-9
        ao = np.ones(ii.size); sca = 1.0
        for h in (.04, .12, .26):
            dd = ao_scene(x + nn[:, 0] * h, y + nn[:, 1] * h, z + nn[:, 2] * h); ao -= np.clip(h - dd, 0, 1) * sca * 2.0; sca *= .65
        ao = np.clip(ao, 0.25, 1)
        col = shade_normals(nn, ao=ao)
        gl = shade_normals(nn, base=(.55, .6, .7), spec=1.4) * 0.30 + 0.015                      # black glass
<<<<<<< HEAD
        horn = shade_normals(nn, base=(1.0, .24, .20), spec=2.4, ao=ao) * np.array([1.0, .50, .46])                 # candy-red chrome
        out = np.where((mat == 1)[:, None], gl, np.where((mat == 4)[:, None], horn, col))
=======
        cone = shade_normals(nn, base=(.50, .52, .58), spec=.9, ao=ao * .85) * 0.70
        ang = np.arctan2(y - np.array([c[1] for c in CONES])[np.argmin(np.abs(y[:, None] - np.array([c[1] for c in CONES])[None, :]), 1)], x - np.sign(x) * POD_X)
        cone *= (0.82 + 0.18 * np.sin(ang * 36))[:, None]
        pearl = shade_normals(nn, base=(.80, .86, 1.0), spec=2.0, ao=ao) * np.array([.78, .88, 1.1])
        out = np.where((mat == 1)[:, None], gl, np.where((mat == 2)[:, None], cone, np.where((mat == 3)[:, None], pearl, col)))
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
        rgb[ii] = out
    img = np.dstack([rgb.reshape(H, W, 3), alpha.reshape(H, W)]); img[..., :3] *= (img[..., 3:4] > 0)
    return finish(img)

def save(img, path, box=None):
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGBA')
    if box: im = im.crop(box)
    im.save(path)

<<<<<<< HEAD
def render_cone():
    """One speaker cone (dome + dust cap + a few ripples) as a sprite. The app draws it six times and pumps each with its own band."""
    half = CONE_R + .014; n = int(round(2 * half * S)); ss = 3; m = n * ss
    c = (np.arange(m) + .5) / m * 2 * half - half; X2, Y2 = np.meshgrid(c, -c); rho = np.sqrt(X2 ** 2 + Y2 ** 2)
    u = np.clip(rho / CONE_R, 0, 1)
    h = .105 * np.sqrt(np.clip(1 - u ** 2, 0, 1)) + .030 * np.sqrt(np.clip(1 - (rho / .042) ** 2, 0, 1)) + .0042 * np.cos(u * np.pi * 7) * (1 - u) * (rho > .05)
    gy, gx = np.gradient(h, 2 * half / m); nn = np.dstack([-gx, -gy, np.ones_like(h)]); nn /= np.linalg.norm(nn, axis=-1, keepdims=True)
    ang = np.arctan2(Y2, X2); ao = np.clip(.55 + .45 * np.clip(1 - u ** 3, 0, 1), 0, 1)
    col = shade_normals(nn.reshape(-1, 3), base=(.62, .64, .70), spec=1.0, ao=ao.ravel()).reshape(m, m, 3) * .80
    col *= (0.84 + 0.16 * np.sin(ang * 36))[..., None] * np.where(rho < .042, 1.25, 1.0)[..., None]
    a = np.clip((CONE_R - rho) * S * ss + .5, 0, 1)
    img = np.dstack([np.clip(col, 0, 1), a]); img[..., :3] *= a[..., None]
    im = Image.fromarray((img * 255).astype(np.uint8), 'RGBA').resize((n, n), Image.LANCZOS)
    arr = np.array(im).astype(np.float32) / 255; al = arr[..., 3:4]; rgb = np.where(al > .002, arr[..., :3] / np.maximum(al, .002), 0)
    fin = finish(np.dstack([np.clip(rgb, 0, 1), al[..., 0]]), bloom=.18, haze=.05)
    Image.fromarray((fin * 255).astype(np.uint8), 'RGBA').save(f'{A.out}/cone.png')
    return round(n * K, 1)

=======
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
t0 = time.time(); os.makedirs(A.out, exist_ok=True)
J, I = np.meshgrid(np.arange(W), np.arange(H)); X = ((J + .5 - W / 2) / S).ravel(); Y = ((YC * S - (I + .5)) / S).ravel()
bust_f, pods_f, both_f = wrap(bust), wrap(pods), wrap(both)
meta = {}
if A.part in ('all', 'bust'):
    img = layer(bust_f, both_f, X, Y, np.ones(X.size, bool)); save(img, f'{A.out}/bust.png'); print('bust', round(time.time() - t0, 1), 's')
if A.part in ('all', 'ears') and not SIDE:
    for side, name in ((-1, 'ear_l'), (1, 'ear_r')):
        sel = (X * side > .62) & (np.abs(Y - POD_Y) < .9)
        img = layer(pods_f, both_f, X, Y, sel)
        ys, xs = np.where(img[..., 3] > 0.004); pad = 6
        box = (max(0, xs.min() - pad), max(0, ys.min() - pad), min(W, xs.max() + pad + 1), min(H, ys.max() + pad + 1)); save(img, f'{A.out}/{name}.png', box)
        meta[name] = dict(x=round(box[0] * K, 1), y=round(box[1] * K, 1), w=round((box[2] - box[0]) * K, 1), h=round((box[3] - box[1]) * K, 1))
<<<<<<< HEAD
    cw = render_cone(); meta['cone'] = dict(w=cw, h=cw); print('ears', round(time.time() - t0, 1), 's')
=======
    print('ears', round(time.time() - t0, 1), 's')
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10

def px(wx, wy): return [round((wx * S + W / 2) * K, 1), round((YC * S - wy * S) * K, 1)]
if A.part == 'all' and not SIDE:
    buttons = []
    ids = ['prev', 'play', 'next', 'list', 'eq', 'fx']
    for i, (sx, cy) in enumerate(CONES):
<<<<<<< HEAD
        cx, cyy = px(sx * POD_X, cy); buttons.append(dict(id=ids[i], cx=cx, cy=cyy, r=round(.17 * S0, 1)))
    for (P, N), bid in zip(PEARL_P, ['shuffle', 'repeat', 'sleep', 'popup']):
        cx, cyy = px(P[0] + N[0] * .06, P[1] + N[1] * .06); buttons.append(dict(id=bid, cx=cx, cy=cyy, r=round(.16 * S0, 1)))
    sc = px(-.57 * K0, SC_Y + .41 * K0)
    meta.update(w=1080, h=2100, screen=dict(x=sc[0], y=sc[1], w=round(1.14 * K0 * S0, 1), h=round(.82 * K0 * S0, 1), r=round(.08 * K0 * S0, 1)), panel=PANEL, buttons=buttons)
=======
        cx, cyy = px(sx * POD_X, cy); buttons.append(dict(id=ids[i], cx=cx, cy=cyy, r=round(.20 * S0, 1)))
    for (P, N), bid in zip(PEARL_P, ['shuffle', 'repeat', 'sleep', 'popup']):
        cx, cyy = px(P[0] + N[0] * .045, P[1] + N[1] * .045); buttons.append(dict(id=bid, cx=cx, cy=cyy, r=round(.15 * S0, 1)))
    sc = px(-.57, SC_Y + .41)
    meta.update(w=1080, h=2100, screen=dict(x=sc[0], y=sc[1], w=round(1.14 * S0, 1), h=round(.82 * S0, 1), r=round(.08 * S0, 1)), panel=PANEL, buttons=buttons)
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
    json.dump(meta, open(f'{A.out}/bust.json', 'w'))
print('done', round(time.time() - t0, 1), 's')
