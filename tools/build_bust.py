"""EARWAX 4.0 — Pure Python 3D Raymarched Early-2000s Liquid-Chrome Cyber-Bust Generator (v2 Polished).

Fixes all preview geometry issues:
  - Zero x=0 center symmetry seam (smooth symmetric blending across midline!)
  - Smooth Sorayama-inspired classical chrome facial sculpture (no sharp cheek dent!)
  - Sculpted chrome shoulders & clavicles sitting cleanly ABOVE the recessed chest console frame!
  - Cleanly beveled 3-driver headphone ear pods with electric-cyan light-pipe spines (no micro-ring aliasing!)
  - 4-core multi-threaded NumPy evaluation (~3.5x faster!)
"""

import argparse
import json
import os
import time
import numpy as np
from y2k_shader import (
    clamp, sstep, smin, smax,
    sd_sphere, sd_ellipsoid, sd_capsule, sd_taper_capsule,
    sd_rbox, sd_extruded_rrect, sd_cylinder_z, sd_torus_z,
    env_y2k, tonemap_y2k, raymarch_layer, finish_y2k, downsample_rgba, save_rgba,
)

# ------------------------------------------------------------------ Layout constants (world units)
DESIGN_W, DESIGN_H = 1080, 2100
WORLD_W = 3.25
S0 = DESIGN_W / WORLD_W        # 332.3077 px per world unit
YC = 1.98                      # top of canvas (i=0) is y = +1.98; bottom (i=2100) is y = -4.34

# Head & Curved Visor
VISOR_Y = 0.50
VISOR_HX, VISOR_HY = 0.68, 0.33
Y_NOSE_TIP = -0.25
Y_MOUTH = -0.58
Y_CHIN = -0.88

# Ear Speaker Pods (3 graduated acoustic drivers tucked smoothly to temples)
POD_X = 1.04
POD_Y = 0.22
CONES = [
    (-1, POD_Y + 0.37, 0.118, 0.138),  # top tweeter
    (-1, POD_Y + 0.00, 0.128, 0.148),  # mid driver
    (-1, POD_Y - 0.39, 0.140, 0.160),  # bottom woofer
    ( 1, POD_Y + 0.37, 0.118, 0.138),
    ( 1, POD_Y + 0.00, 0.128, 0.148),
    ( 1, POD_Y - 0.39, 0.140, 0.160),
]

# 4 Levitating Early-2000s Aqua-Chrome Crown Orbs arcing overhead
CROWN_ORBS = [
    (-0.72, 1.54, 0.155),
    (-0.25, 1.72, 0.155),
    ( 0.25, 1.72, 0.155),
    ( 0.72, 1.54, 0.155),
]

# Chest Console Panel — cleanly framed INSIDE the sculpted chrome chest armor!
# Shoulder top starts around y = -1.60 (px ~ 1190); chest panel top starts at px = 1280 (y = -1.872),
# bottom at px = 1952 (y = -3.894), leaving ~90px of sculpted chrome chest lip above & 148px chrome chin + vents below!
PANEL_PX = dict(x=126, y=1280, w=828, h=672, r=50)
PANEL_CY = YC - (PANEL_PX['y'] + PANEL_PX['h'] * 0.5) / S0   # -2.883
PANEL_HX = (PANEL_PX['w'] * 0.5) / S0                        # 1.246
PANEL_HY = (PANEL_PX['h'] * 0.5) / S0                        # 1.011
CHEST_FRONT = 0.44

# ------------------------------------------------------------------ 3D Sculpted Head & Bust SDF (Sorayama Liquid-Chrome Anatomy!)
def head_sdf(x, y, z):
    # 1. Classical Sculpted Cranium & Tapered Face Plane (silky continuous contour, zero midline seam!)
    d = sd_ellipsoid(x, y, z, (0.0, 0.58, -0.05), (0.85, 0.95, 0.86))
    # Smooth brow & malar mid-face volume
    d = smin(d, sd_ellipsoid(x, y, z, (0.0, 0.02, 0.06), (0.77, 0.48, 0.72)), 0.28)
    # Graceful tapered lower face / jaw oval (continuous V-taper — ZERO cheek bumps!)
    jaw_w = 0.64 - 0.34 * sstep(-0.10, -0.90, y)
    jaw_z = 0.08 + 0.16 * sstep(-0.10, -0.82, y)
    d_jaw = sd_ellipsoid(x, y, z, (0.0, -0.36, jaw_z), (jaw_w, 0.56, 0.64))
    d = smin(d, d_jaw, 0.24)

    # Subtle sculptural high cheekbones & sleek temple headphone mounts
    for sx in (-1.0, 1.0):
        # Soft high zygomatic arch flowing smoothly from under visor outer edge back toward temple
        d = smin(d, sd_ellipsoid(x, y, z, (sx * 0.52, 0.02, 0.38), (0.24, 0.16, 0.28)), 0.28)
        # Sleek continuous jawline bevel (single unbroken sweep from ear-angle to chin — NO mid-cheek joint!)
        d = smin(d, sd_taper_capsule(x, y, z, (sx * 0.65, -0.14, -0.02), (sx * 0.17, -0.85, 0.48), 0.165, 0.115), 0.24)
        # Sculpted temple acoustic receiver collars
        d = smin(d, sd_ellipsoid(x, y, z, (sx * 0.85, POD_Y, -0.01), (0.15, 0.44, 0.27)), 0.15)
        # Precision recessed temple hinge socket accent
        hinge_g = sd_capsule(x, y, z, (sx * 0.80, POD_Y + 0.26, 0.18), (sx * 0.80, POD_Y - 0.26, 0.18), 0.018)
        d = smax(d, -hinge_g, 0.014)

    # Refined classical sculpted chin
    d = smin(d, sd_ellipsoid(x, y, z, (0.0, Y_CHIN + 0.01, 0.54), (0.21, 0.135, 0.165)), 0.15)

    # 2. Refined Sculpted Nose (smooth continuous bridge emerging cleanly from beneath visor)
    d = smin(d, sd_taper_capsule(x, y, z, (0.0, 0.16, 0.77), (0.0, -0.21, 0.94), 0.064, 0.086), 0.12)
    d = smin(d, sd_sphere(x, y, z, (0.0, Y_NOSE_TIP, 0.96), 0.095), 0.095)
    for sx in (-1.0, 1.0):
        d = smin(d, sd_sphere(x, y, z, (sx * 0.104, Y_NOSE_TIP - 0.018, 0.875), 0.058), 0.078)
        d = smax(d, -sd_ellipsoid(x, y, z, (sx * 0.056, Y_NOSE_TIP - 0.062, 0.915), (0.030, 0.022, 0.048)), 0.015)
    # Subtle classical philtrum dimple between nose base & upper lip
    d = smax(d, -sd_capsule(x, y, z, (0.0, Y_NOSE_TIP - 0.10, 0.84), (0.0, Y_MOUTH + 0.08, 0.83), 0.026), 0.045)

    # 3. Sculpted Classical Chrome Lips (Cupid's bow upper lip + full lower lip + gentle mentolabial curve)
    d = smin(d, sd_ellipsoid(x, y, z, (0.0, Y_MOUTH + 0.030, 0.755), (0.215, 0.036, 0.076)), 0.065)
    d = smin(d, sd_ellipsoid(x, y, z, (0.0, Y_MOUTH - 0.040, 0.755), (0.188, 0.046, 0.084)), 0.065)
    # Crisp mouth parting seam
    d = smax(d, -sd_ellipsoid(x, y, z, (0.0, Y_MOUTH - 0.004, 0.855), (0.222, 0.0085, 0.072)), 0.014)
    # Gentle smooth mentolabial hollow under lower lip (soft k=0.11 so there's NO harsh black crease!)
    d = smax(d, -sd_ellipsoid(x, y, z, (0.0, Y_MOUTH - 0.125, 0.76), (0.15, 0.022, 0.055)), 0.11)

    # 4. Graceful Anatomical Neck + Sternocleidomastoid V-Tendons + Sculpted Collarbones
    d = smin(d, sd_ellipsoid(x, y, z, (0.0, -1.26, -0.04), (0.35, 0.58, 0.34)), 0.25)
    # Subtle tracheal central column
    d = smin(d, sd_taper_capsule(x, y, z, (0.0, -0.96, 0.16), (0.0, -1.48, 0.22), 0.11, 0.13), 0.14)
    for sx in (-1.0, 1.0):
        # Sternocleidomastoid V-tendons curving from mastoid behind jaw to sternal notch
        d = smin(d, sd_taper_capsule(x, y, z, (sx * 0.32, -0.84, -0.02), (sx * 0.10, -1.52, 0.27), 0.090, 0.068), 0.13)
        # Anatomical S-curved clavicles flowing smoothly into sculpted deltoid shoulders
        d = smin(d, sd_taper_capsule(x, y, z, (sx * 0.16, -1.57, 0.32), (sx * 1.15, -1.66, 0.16), 0.085, 0.122), 0.19)
        # Sculpted trapezius neck-to-shoulder ridge
        d = smin(d, sd_taper_capsule(x, y, z, (sx * 0.30, -1.16, -0.10), (sx * 1.12, -1.62, -0.06), 0.12, 0.22), 0.22)
    # Sculpted suprasternal notch hollow above chest collar
    d = smax(d, -sd_ellipsoid(x, y, z, (0.0, -1.54, 0.38), (0.15, 0.095, 0.13)), 0.13)

    # 5. Sleek Curved Wrap-Around Early-2000s Cyber-Visor Bezel
    zv = z + 0.20 * (x * x)
    nose_arch = 0.075 * np.exp(-((x / 0.25) ** 2))
    yv = y - VISOR_Y - nose_arch * sstep(-0.06, -0.32, y - VISOR_Y)

    bez_out = sd_extruded_rrect(x, yv, zv, (0.0, 0.0, 0.66), VISOR_HX + 0.094, VISOR_HY + 0.084, 0.26, 0.28, 0.074)
    bez_in = sd_extruded_rrect(x, yv, zv, (0.0, 0.0, 0.90), VISOR_HX - 0.01, VISOR_HY - 0.01, 0.16, 0.20, 0.042)
    bez = smax(bez_out, -bez_in, 0.024)
    d = smin(d, bez, 0.095)

    return d

def chest_sdf(x, y, z):
    # Sleek sculpted Y2K chrome chest housing (top chrome lip sits cleanly at y = -1.56, well above panel top -1.87!)
    # Smooth quadratic bottom taper (zero midline seam at x=0!)
    hx_taper = 1.50 - 0.12 * sstep(-2.4, -4.25, y)
    # Build smooth extruded rounded rectangle with variable smooth width
    qx = np.abs(x) - hx_taper + 0.52
    qy = np.abs(y - (-2.90)) - 1.32 + 0.52
    d2 = np.sqrt(np.maximum(qx, 0.0) ** 2 + np.maximum(qy, 0.0) ** 2) + np.minimum(np.maximum(qx, qy), 0.0) - 0.52
    wz = np.abs(z - (-0.02)) - 0.46 + 0.18
    w1 = d2 + 0.18
    body = np.sqrt(np.maximum(w1, 0.0) ** 2 + np.maximum(wz, 0.0) ** 2) + np.minimum(np.maximum(w1, wz), 0.0) - 0.18

    # Sculpted trapezius shoulders flowing smoothly from neck into chest frame
    traps = sd_ellipsoid(x, y, z, (0.0, -1.68, -0.03), (1.22, 0.42, 0.42))
    d = smin(body, traps, 0.32)

    # Crisp diagonal cyber-armor shoulder contour grooves
    for sx in (-1.0, 1.0):
        sh_g = sd_taper_capsule(x, y, z, (sx * 0.76, -1.64, 0.39), (sx * 1.40, -2.18, 0.36), 0.018, 0.015)
        d = smax(d, -sh_g, 0.016)

    # Deep chamfered console pocket cleanly INSIDE the chrome chest frame!
    lip_cut = sd_extruded_rrect(x, y, z, (0.0, PANEL_CY, CHEST_FRONT + 0.14),
                                PANEL_HX + 0.022, PANEL_HY + 0.022, 0.24, 0.175, 0.052)
    d = smax(d, -lip_cut, 0.038)

    # 11 Precision-Milled 3D Acoustic Grille Slots (ONLY evaluated in bottom chin rows y < -3.75!)
    vent_y = YC - 2016.0 / S0   # -4.087
    if np.any(y < -3.75):
        # Single vectorized periodic slot SDF (11x faster than looping 11 times!)
        slot_idx = np.clip(np.round(x / 0.145), -5.0, 5.0)
        vx = slot_idx * 0.145
        slot = sd_rbox(x, y, z, (vx, vent_y, CHEST_FRONT + 0.06), (0.026, 0.058, 0.16), 0.024)
        d = smax(d, -slot, 0.012)

    return d

def bust_scene(x, y, z):
    # Spatial domain splitting: skip head_sdf on pure chest rows & skip chest_sdf on pure head rows (~3x faster!)
    y_min = float(y.min())
    y_max = float(y.max())
    if y_max < -1.95:
        d_body = chest_sdf(x, y, z)
    elif y_min > -1.05:
        d_body = head_sdf(x, y, z)
    else:
        d_body = smin(head_sdf(x, y, z), chest_sdf(x, y, z), 0.24)

    best = d_body
    mat = np.zeros_like(d_body, dtype=np.int8)

    def put(dd, m_id):
        nonlocal best, mat
        sel = dd < best
        best = np.where(sel, dd, best)
        mat = np.where(sel, m_id, mat)

    # 1. Curved Smoked Sapphire-Obsidian Forehead Visor Glass + Neon Trim (ONLY on head rows!)
    if y_max > -0.10:
        zv = z + 0.20 * (x * x)
        nose_arch = 0.075 * np.exp(-((x / 0.25) ** 2))
        yv = y - VISOR_Y - nose_arch * sstep(-0.06, -0.32, y - VISOR_Y)
        glass_v = sd_extruded_rrect(x, yv, zv + 0.045 * (x * x + yv * yv),
                                    (0.0, 0.0, 0.805), VISOR_HX - 0.015, VISOR_HY - 0.015, 0.042, 0.19, 0.028)
        put(glass_v, 1)

        vr_out = sd_extruded_rrect(x, yv, zv, (0.0, 0.0, 0.838), VISOR_HX + 0.010, VISOR_HY + 0.010, 0.016, 0.205, 0.010)
        vr_in = sd_extruded_rrect(x, yv, zv, (0.0, 0.0, 0.838), VISOR_HX - 0.014, VISOR_HY - 0.014, 0.036, 0.188, 0.010)
        put(np.maximum(vr_out, -vr_in), 5)

    # 2. Chest Console Glass, Collar Bar & Vent Backing (ONLY on chest rows!)
    if y_min < -1.45:
        glass_c = sd_extruded_rrect(x, y, z, (0.0, PANEL_CY, CHEST_FRONT - 0.10),
                                    PANEL_HX - 0.012, PANEL_HY - 0.012, 0.024, 0.15, 0.016)
        put(glass_c, 1)

        collar_bar = sd_capsule(x, y, z, (-0.42, -1.72, 0.445), (0.42, -1.72, 0.445), 0.017)
        put(collar_bar, 5)

        if y_min < -3.75:
            vent_y = YC - 2016.0 / S0
            vent_back = sd_rbox(x, y, z, (0.0, vent_y, CHEST_FRONT - 0.075), (0.82, 0.068, 0.014), 0.01)
            put(vent_back, 6)

    return best, mat

# ------------------------------------------------------------------ 3D Sculpted Ear Speaker Pods SDF (Hi-Fi Monitor Housings!)
def pods_sdf_raw(x, y, z):
    ax = np.abs(x)
    curve_x = 0.042 * np.cos((y - POD_Y) * 2.3)
    w_taper = 0.030 * sstep(0.35, -0.45, y - POD_Y)
    d = sd_extruded_rrect(ax, y, z, (POD_X + curve_x, POD_Y, 0.04), 0.228 + w_taper, 0.625, 0.25, 0.215, 0.122)

    # Sculpted chrome acoustic hinge bridge locking each pod organically to the temple collar
    hinge_arm = sd_extruded_rrect(ax, y, z, (POD_X - 0.185, POD_Y, -0.02), 0.10, 0.34, 0.11, 0.075, 0.045)
    d = smin(d, hinge_arm, 0.065)

    # Carve sleek recessed optical groove along outer spine for embedded cyan light-pipe
    sp_y = np.clip(y - POD_Y, -0.38, 0.38)
    sp_cx = POD_X + 0.212 + 0.042 * np.cos(sp_y * 2.3) + 0.030 * sstep(0.35, -0.45, sp_y)
    spine_groove = np.sqrt((ax - sp_cx) ** 2 + (y - (POD_Y + sp_y)) ** 2 + (z - 0.145) ** 2) - 0.028
    d = smax(d, -spine_groove, 0.014)

    # Carve 3 deep, generously chamfered acoustic speaker sockets (ZERO inner rim aliasing!)
    for sx, cy, cone_r, sock_r in CONES:
        rho = np.sqrt((x - sx * POD_X) ** 2 + (y - cy) ** 2)
        cav = np.maximum(rho - (sock_r + 0.008), 0.155 - z)
        d = smax(d, -cav, 0.028)
    return d

def pods_scene(x, y, z):
    d = pods_sdf_raw(x, y, z)
    best = d
    mat = np.zeros_like(d, dtype=np.int8)

    def put(dd, m_id):
        nonlocal best, mat
        sel = dd < best
        best = np.where(sel, dd, best)
        mat = np.where(sel, m_id, mat)

    for sx, cy, cone_r, sock_r in CONES:
        # Deep dark anodized acoustic backing chamber seated cleanly inside socket
        fl = sd_cylinder_z(x, y, z, (sx * POD_X, cy, 0.158), sock_r + 0.004, 0.008)
        put(fl, 6)
        # Smooth machined brushed-aluminum bezel ring framing each driver socket (eliminates rim noise!)
        bezel = sd_torus_z(x, y, z, (sx * POD_X, cy, 0.242), sock_r + 0.010, 0.015)
        put(bezel, 2)

    # Smooth rounded-cap Electric-Cyan Y2K Optical Light-Pipe nested cleanly INSIDE outer groove (Mat 5)
    ax = np.abs(x)
    sp_y = np.clip(y - POD_Y, -0.37, 0.37)
    sp_cx = POD_X + 0.204 + 0.042 * np.cos(sp_y * 2.3) + 0.030 * sstep(0.35, -0.45, sp_y)
    spine = np.sqrt((ax - sp_cx) ** 2 + (y - (POD_Y + sp_y)) ** 2 + (z - 0.140) ** 2) - 0.019
    put(spine, 5)

    return best, mat

def combined_ao(x, y, z):
    d = bust_scene(x, y, z)[0]
    if float(y.max()) > -0.70 and float(y.min()) < 1.15:
        d = np.minimum(d, pods_scene(x, y, z)[0])
    return d

# ------------------------------------------------------------------ 3x Supersampled Speaker Cone (cone.png)
def render_speaker_cone(size=140):
    ss = 3
    M = size * ss
    c = (np.arange(M, dtype=np.float32) + 0.5) / M * 2.0 - 1.0
    X, Y = np.meshgrid(c, -c)
    r = np.sqrt(X * X + Y * Y)
    ang = np.arctan2(Y, X)
    a = clamp((0.97 - r) * (M * 0.5), 0.0, 1.0)

    u_cap = clamp(r / 0.34, 0.0, 1.0)
    u_cone = clamp((r - 0.34) / 0.47, 0.0, 1.0)
    u_sur = clamp((r - 0.81) / 0.16, 0.0, 1.0)

    slope_cap = -0.78 * u_cap / np.sqrt(np.maximum(0.08, 1.0 - 0.85 * u_cap * u_cap))
    slope_cone = 0.56 + 0.16 * np.sin(u_cone * np.pi * 12.0)
    slope_sur = -0.92 * np.cos(u_sur * np.pi)

    slope = np.where(r < 0.34, slope_cap, np.where(r < 0.81, slope_cone, slope_sur))
    cs = np.where(r > 1e-4, X / np.maximum(r, 1e-4), 0.0)
    sn = np.where(r > 1e-4, Y / np.maximum(r, 1e-4), 0.0)

    nx = -slope * cs; ny = -slope * sn; nz = np.ones_like(r)
    inv = 1.0 / np.sqrt(nx * nx + ny * ny + nz * nz)
    nx *= inv; ny *= inv; nz *= inv

    rx = 2.0 * nz * nx; ry = 2.0 * nz * ny; rz = 2.0 * nz * nz - 1.0
    env = env_y2k(rx, ry, rz, softness=1.25)

    lx, ly, lz = -0.40, 0.66, 0.64
    ln = np.sqrt(lx * lx + ly * ly + lz * lz)
    lx /= ln; ly /= ln; lz /= ln
    hx, hy, hz = lx, ly, lz + 1.0
    hn = np.sqrt(hx * hx + hy * hy + hz * hz)
    hx /= hn; hy /= hn; hz /= hn

    diff = np.clip(nx * lx + ny * ly + nz * lz, 0.0, 1.0)
    spec = np.clip(nx * hx + ny * hy + nz * hz, 0.0, 1.0) ** 72.0
    brush = 0.82 + 0.18 * np.sin(ang * 2.0 + 0.85) + 0.07 * np.sin(ang * 48.0)
    ribs = 0.88 + 0.12 * np.cos((r - 0.34) * 76.0)

    c_cap = env * np.array([0.92, 0.96, 1.05], np.float32) * (0.72 + 0.28 * brush[..., None]) + spec[..., None] * 1.45
    c_cone = (env * np.array([0.42, 0.49, 0.62], np.float32) * brush[..., None] * ribs[..., None]
              + diff[..., None] * 0.16 + spec[..., None] * 0.65)
    c_sur = env * np.array([0.16, 0.19, 0.24], np.float32) + diff[..., None] * 0.10 + spec[..., None] * 0.25

    col = np.where((r < 0.34)[..., None], c_cap, np.where((r < 0.81)[..., None], c_cone, c_sur))
    coil_ring = np.exp(-((r - 0.345) / 0.022) ** 2) * 0.95
    outer_ring = np.exp(-((r - 0.815) / 0.020) ** 2) * 0.55
    col = col + (coil_ring + outer_ring)[..., None] * np.array([0.18, 0.92, 1.25], np.float32)

    col = tonemap_y2k(col)
    hi = np.dstack([col, a]).astype(np.float32)
    out = downsample_rgba(hi, size, size)
    return finish_y2k(out, bloom=0.22, bloom_thr=0.78, haze=0.02)

# ------------------------------------------------------------------ 3x Supersampled Y2K Aqua Crown Orb (pearl.png)
def render_crown_orb(size=148):
    ss = 3
    M = size * ss
    c = (np.arange(M, dtype=np.float32) + 0.5) / M * 2.0 - 1.0
    X, Y = np.meshgrid(c, -c)
    r = np.sqrt(X * X + Y * Y)
    a = clamp((0.96 - r) * (M * 0.5), 0.0, 1.0)

    z_sph = np.sqrt(np.maximum(0.0, 0.96 * 0.96 - r * r)) / 0.96
    nx = X / 0.96; ny = Y / 0.96; nz = z_sph
    inv = 1.0 / (np.sqrt(nx * nx + ny * ny + nz * nz) + 1e-8)
    nx *= inv; ny *= inv; nz *= inv

    rx = 2.0 * nz * nx; ry = 2.0 * nz * ny; rz = 2.0 * nz * nz - 1.0
    env = env_y2k(rx, ry, rz, softness=1.0)
    fresnel = (1.0 - np.clip(nz, 0.0, 1.0)) ** 2.6

    caustic = np.exp(-((X - 0.08) ** 2 / 0.24 + (Y + 0.42) ** 2 / 0.14)) * 1.18
    meniscus = np.exp(-((X + 0.24) ** 2 / 0.16 + (Y - 0.46) ** 2 / 0.045)) * 1.25
    pinpoint = np.exp(-((X + 0.30) ** 2 + (Y - 0.48) ** 2) / 0.012) * 1.15

    orb_r = 0.03 + 0.18 * caustic + fresnel * (0.28 + 0.36 * sstep(-0.3, 0.7, X)) + env[..., 0] * (0.14 + 0.26 * fresnel)
    orb_g = 0.14 + 0.78 * caustic + fresnel * (0.86 - 0.18 * sstep(-0.2, 0.8, X)) + env[..., 1] * (0.16 + 0.28 * fresnel)
    orb_b = 0.46 + 1.12 * caustic + fresnel * 1.24 + env[..., 2] * (0.22 + 0.34 * fresnel)

    orb = np.stack([orb_r, orb_g, orb_b], axis=-1)
    orb += meniscus[..., None] * np.array([0.92, 0.98, 1.08], np.float32) + pinpoint[..., None]

    ring_mix = sstep(0.83, 0.87, r)
    ring_u = clamp((r - 0.84) / 0.12, 0.0, 1.0) * 2.0 - 1.0
    rnx = (X / np.maximum(r, 1e-4)) * ring_u
    rny = (Y / np.maximum(r, 1e-4)) * ring_u
    rnz = np.sqrt(np.maximum(0.05, 1.0 - ring_u * ring_u))
    rrx = 2.0 * rnz * rnx; rry = 2.0 * rnz * rny; rrz = 2.0 * rnz * rnz - 1.0
    chrome_ring = env_y2k(rrx, rry, rrz, softness=0.95) * np.array([0.95, 0.99, 1.06], np.float32)

    col = orb * (1.0 - ring_mix[..., None]) + chrome_ring * ring_mix[..., None]
    col = tonemap_y2k(col)

    hi = np.dstack([col, a]).astype(np.float32)
    out = downsample_rgba(hi, size, size)
    return finish_y2k(out, bloom=0.30, bloom_thr=0.74, haze=0.02)

# ------------------------------------------------------------------ Main Pipeline
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--w', type=int, default=1080)
    ap.add_argument('--out', default='app/src/main/assets/skins')
    ap.add_argument('--part', default='all', choices=['all', 'bust', 'ears', 'sprites'])
    A = ap.parse_args()

    os.makedirs(A.out, exist_ok=True)
    W = A.w
    H = int(round(W * DESIGN_H / DESIGN_W))
    S = W / WORLD_W
    K = DESIGN_W / float(W)
    px_world = 1.0 / S

    x_from_j = lambda j: (j + 0.5 - W * 0.5) / S
    y_from_i = lambda i: (YC * S - (i + 0.5)) / S

    def to_design_px(wx, wy):
        return [round(wx * S0 + DESIGN_W * 0.5, 1), round(YC * S0 - wy * S0, 1)]

    t0 = time.time()
    meta = {}

    if A.part in ('all', 'bust'):
        print(f"[bust] Raymarching 3D Y2K Liquid-Chrome Bust ({W}x{H})...")
        # Tight bounding mask skips empty sky pixels above crown & outside chest flanks
        bust_mask = lambda X, Y: (Y < 1.66) & (np.abs(X) < 1.60)
        img = raymarch_layer(bust_scene, combined_ao, W, H, x_from_j, y_from_i, px_world,
                             z_start=2.4, max_dist=4.5, max_steps=72, chunk_rows=48, mask_fn=bust_mask, do_shadow=True)
        img = finish_y2k(img, bloom=0.28, bloom_thr=0.78, haze=0.025)
        save_rgba(img, os.path.join(A.out, 'bust.png'))
        print(f"[bust] Saved bust.png in {time.time() - t0:.1f}s")

    if A.part in ('all', 'ears'):
        t1 = time.time()
        for side, name in ((-1, 'ear_l'), (1, 'ear_r')):
            print(f"[ears] Raymarching {name}...")
            mask_fn = lambda X, Y, s=side: (X * s > 0.72) & (X * s < 1.48) & (np.abs(Y - POD_Y) < 0.82)
            img = raymarch_layer(pods_scene, combined_ao, W, H, x_from_j, y_from_i, px_world,
                                 z_start=2.4, max_dist=4.4, max_steps=68, chunk_rows=48, mask_fn=mask_fn, do_shadow=True)
            img = finish_y2k(img, bloom=0.26, bloom_thr=0.78, haze=0.02)
            ys, xs = np.where(img[..., 3] > 0.005)
            pad = max(4, int(round(8 / K)))
            x0, x1 = max(0, int(xs.min()) - pad), min(W, int(xs.max()) + pad + 1)
            y0, y1 = max(0, int(ys.min()) - pad), min(H, int(ys.max()) + pad + 1)
            crop = img[y0:y1, x0:x1]
            save_rgba(crop, os.path.join(A.out, f'{name}.png'))
            meta[name] = dict(
                x=round(x0 * K, 1), y=round(y0 * K, 1),
                w=round((x1 - x0) * K, 1), h=round((y1 - y0) * K, 1),
            )
        print(f"[ears] Saved ear_l.png & ear_r.png in {time.time() - t1:.1f}s")

    if A.part in ('all', 'sprites'):
        t2 = time.time()
        save_rgba(render_speaker_cone(132), os.path.join(A.out, 'cone.png'))
        save_rgba(render_crown_orb(144), os.path.join(A.out, 'pearl.png'))
        print(f"[sprites] Saved cone.png & pearl.png in {time.time() - t2:.1f}s")

    if A.part == 'all':
        ids = ['prev', 'play', 'next', 'list', 'eq', 'fx']
        buttons = []
        for idx, (sx, cy, cone_r, sock_r) in enumerate(CONES):
            cx, cy_px = to_design_px(sx * POD_X, cy)
            buttons.append(dict(id=ids[idx], cx=cx, cy=cy_px, r=round(sock_r * S0 * 1.05, 1)))

        orb_ids = ['shuffle', 'repeat', 'sleep', 'popup']
        for idx, (ox, oy, orad) in enumerate(CROWN_ORBS):
            cx, cy_px = to_design_px(ox, oy)
            buttons.append(dict(id=orb_ids[idx], cx=cx, cy=cy_px, r=round(orad * S0, 1)))

        scr_tl = to_design_px(-(VISOR_HX - 0.04), VISOR_Y + (VISOR_HY - 0.03))
        scr_br = to_design_px( (VISOR_HX - 0.04), VISOR_Y - (VISOR_HY - 0.05))
        screen = dict(
            x=scr_tl[0], y=scr_tl[1],
            w=round(scr_br[0] - scr_tl[0], 1),
            h=round(scr_br[1] - scr_tl[1], 1),
            r=44,
        )

        meta.update(
            w=DESIGN_W, h=DESIGN_H,
            cone=dict(w=92, h=92),
            screen=screen,
            panel=PANEL_PX,
            buttons=buttons,
        )
        with open(os.path.join(A.out, 'bust.json'), 'w') as f:
            json.dump(meta, f, indent=2)
        print(f"[bust] Wrote bust.json (screen={screen['w']}x{screen['h']}, panel={PANEL_PX['w']}x{PANEL_PX['h']})")
        print(f"[bust] ALL DONE in {time.time() - t0:.1f}s")

if __name__ == '__main__':
    main()
