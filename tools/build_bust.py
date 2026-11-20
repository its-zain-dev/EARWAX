"""EARWAX 4.6 — Pure Python Unified 3D Raymarched Early-2000s Liquid-Chrome Cyber-Bust Generator.

Unified single-pass 3D sculpture:
  - ONE continuous analytical 3D SDF fuses Head, Curved Visor, Sculpted Temple Yokes,
    3-Driver Ear Speaker Pods, Anatomical Neck, Collarbones, Shoulders & Chest Armor
    with smooth metallic fillets, unified HDRI chrome reflections, true 3D shadows & AO.
  - Zero pasted/separate ear PNG layers (no mismatched dimensions, seams, or floating stickers!).
  - Zero neck-to-head or neck-to-chest discontinuities (wide continuous transition domain).
  - 4-core multi-process NumPy raymarching with supersampling for razor-sharp chrome edges.
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

# Ear Speaker Pods (organically sculpted & smoothly fused into the temples in 3D!)
POD_X = 0.99
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
PANEL_PX = dict(x=126, y=1280, w=828, h=672, r=50)
PANEL_CY = YC - (PANEL_PX['y'] + PANEL_PX['h'] * 0.5) / S0   # -2.883
PANEL_HX = (PANEL_PX['w'] * 0.5) / S0                        # 1.246
PANEL_HY = (PANEL_PX['h'] * 0.5) / S0                        # 1.011
CHEST_FRONT = 0.44


# ------------------------------------------------------------------ 3D Ear Pods Sub-SDF (welded directly into head_sdf!)
def pods_shell_sdf(x, y, z):
    ax = np.abs(x)
    curve_x = 0.036 * np.cos((y - POD_Y) * 2.3)
    w_taper = 0.028 * sstep(0.35, -0.45, y - POD_Y)
    d = sd_extruded_rrect(ax, y, z, (POD_X + curve_x, POD_Y, 0.04), 0.222 + w_taper, 0.620, 0.25, 0.212, 0.122)

    # Organic sculpted upper, middle & lower chrome temple yoke bridges flowing seamlessly into cranium
    bridge_main = sd_ellipsoid(ax, y, z, (POD_X - 0.16, POD_Y, 0.01), (0.20, 0.48, 0.24))
    bridge_top = sd_taper_capsule(ax, y, z, (0.68, POD_Y + 0.48, 0.02), (POD_X - 0.02, POD_Y + 0.40, 0.06), 0.15, 0.14)
    bridge_bot = sd_taper_capsule(ax, y, z, (0.64, POD_Y - 0.38, 0.02), (POD_X - 0.02, POD_Y - 0.40, 0.06), 0.15, 0.14)
    d = smin(d, bridge_main, 0.12)
    d = smin(d, bridge_top, 0.10)
    d = smin(d, bridge_bot, 0.10)
    return d


def carve_pod_details(d, x, y, z):
    ax = np.abs(x)
    # Recessed optical groove along outer spine for embedded cyan light-pipe
    sp_y = np.clip(y - POD_Y, -0.38, 0.38)
    sp_cx = POD_X + 0.206 + 0.036 * np.cos(sp_y * 2.3) + 0.028 * sstep(0.35, -0.45, sp_y)
    spine_groove = np.sqrt((ax - sp_cx) ** 2 + (y - (POD_Y + sp_y)) ** 2 + (z - 0.145) ** 2) - 0.028
    d = smax(d, -spine_groove, 0.014)

    # 3 deep, generously chamfered acoustic speaker sockets
    for sx, cy, cone_r, sock_r in CONES:
        rho = np.sqrt((x - sx * POD_X) ** 2 + (y - cy) ** 2)
        cav = np.maximum(rho - (sock_r + 0.008), 0.155 - z)
        d = smax(d, -cav, 0.028)
    return d


# ------------------------------------------------------------------ 3D Sculpted Head, Fused Ears & Continuous Neck SDF
def head_sdf(x, y, z):
    # 1. Classical Sculpted Cranium & Tapered Face Plane (zero midline seam!)
    d = sd_ellipsoid(x, y, z, (0.0, 0.58, -0.04), (0.86, 0.95, 0.86))
    d = smin(d, sd_ellipsoid(x, y, z, (0.0, 0.02, 0.06), (0.78, 0.48, 0.72)), 0.28)
    jaw_w = 0.65 - 0.32 * sstep(-0.10, -0.90, y)
    jaw_z = 0.08 + 0.16 * sstep(-0.10, -0.82, y)
    d_jaw = sd_ellipsoid(x, y, z, (0.0, -0.36, jaw_z), (jaw_w, 0.56, 0.64))
    d = smin(d, d_jaw, 0.25)

    # Subtle high cheekbones & sleek unbroken jawline sweep
    for sx in (-1.0, 1.0):
        d = smin(d, sd_ellipsoid(x, y, z, (sx * 0.52, 0.02, 0.38), (0.24, 0.16, 0.28)), 0.28)
        d = smin(d, sd_taper_capsule(x, y, z, (sx * 0.66, -0.14, -0.01), (sx * 0.17, -0.85, 0.48), 0.17, 0.12), 0.24)
        # Organic temple receiver flare blending directly into the ear pod yoke
        d = smin(d, sd_ellipsoid(x, y, z, (sx * 0.82, POD_Y, 0.01), (0.20, 0.50, 0.32)), 0.20)

    # Refined classical sculpted chin
    d = smin(d, sd_ellipsoid(x, y, z, (0.0, Y_CHIN + 0.01, 0.54), (0.215, 0.138, 0.168)), 0.16)

    # Seamlessly weld the 3D Ear Speaker Pods directly into the head sculpture!
    if float(y.max()) > -0.75 and float(y.min()) < 1.20:
        d = smin(d, pods_shell_sdf(x, y, z), 0.14)
        d = carve_pod_details(d, x, y, z)

    # 2. Refined Sculpted Nose (smooth continuous bridge emerging cleanly from beneath visor)
    d = smin(d, sd_taper_capsule(x, y, z, (0.0, 0.16, 0.77), (0.0, -0.21, 0.94), 0.064, 0.086), 0.12)
    d = smin(d, sd_sphere(x, y, z, (0.0, Y_NOSE_TIP, 0.96), 0.095), 0.095)
    for sx in (-1.0, 1.0):
        d = smin(d, sd_sphere(x, y, z, (sx * 0.104, Y_NOSE_TIP - 0.018, 0.875), 0.058), 0.078)
        d = smax(d, -sd_ellipsoid(x, y, z, (sx * 0.056, Y_NOSE_TIP - 0.062, 0.915), (0.030, 0.022, 0.048)), 0.015)
    d = smax(d, -sd_capsule(x, y, z, (0.0, Y_NOSE_TIP - 0.10, 0.84), (0.0, Y_MOUTH + 0.08, 0.83), 0.026), 0.045)

    # 3. Sculpted Classical Chrome Lips
    d = smin(d, sd_ellipsoid(x, y, z, (0.0, Y_MOUTH + 0.030, 0.755), (0.215, 0.036, 0.076)), 0.065)
    d = smin(d, sd_ellipsoid(x, y, z, (0.0, Y_MOUTH - 0.040, 0.755), (0.188, 0.046, 0.084)), 0.065)
    d = smax(d, -sd_ellipsoid(x, y, z, (0.0, Y_MOUTH - 0.004, 0.855), (0.222, 0.0085, 0.072)), 0.014)
    d = smax(d, -sd_ellipsoid(x, y, z, (0.0, Y_MOUTH - 0.125, 0.76), (0.15, 0.022, 0.055)), 0.11)

    # 4. Continuous Sculpted Anatomical Neck — organically bridges Chin/Jaw to Collarbones & Shoulders!
    # Main cylindrical-oval neck core (brought forward & wider so the head sits solidly on it in 3D)
    d_neck_core = sd_ellipsoid(x, y, z, (0.0, -1.24, 0.05), (0.43, 0.66, 0.43))
    d = smin(d, d_neck_core, 0.30)
    # Smooth submental throat-under-chin bridge (eliminates floating-head shadow disconnect!)
    d_throat = sd_taper_capsule(x, y, z, (0.0, -0.78, 0.30), (0.0, -1.48, 0.25), 0.25, 0.30)
    d = smin(d, d_throat, 0.24)
    # Broad sculpted neck base flare flowing seamlessly into chest top & shoulders
    d_neck_base = sd_ellipsoid(x, y, z, (0.0, -1.58, 0.08), (0.78, 0.36, 0.44))
    d = smin(d, d_neck_base, 0.28)
    for sx in (-1.0, 1.0):
        # Continuous sternocleidomastoid V-muscles from behind jaw down into sternum
        d = smin(d, sd_taper_capsule(x, y, z, (sx * 0.40, -0.72, 0.04), (sx * 0.14, -1.58, 0.30), 0.135, 0.115), 0.20)
        # Sculpted trapezius sweeping smoothly from mid-neck out to broad deltoid shoulders
        d = smin(d, sd_taper_capsule(x, y, z, (sx * 0.34, -1.04, -0.05), (sx * 1.20, -1.68, -0.02), 0.19, 0.30), 0.28)
        # Anatomical S-curved clavicles flowing cleanly into shoulder cap
        d = smin(d, sd_taper_capsule(x, y, z, (sx * 0.16, -1.58, 0.34), (sx * 1.18, -1.68, 0.18), 0.10, 0.14), 0.22)
    # Gentle suprasternal notch dimple
    d = smax(d, -sd_ellipsoid(x, y, z, (0.0, -1.56, 0.43), (0.14, 0.085, 0.11)), 0.14)

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
    hx_taper = 1.50 - 0.12 * sstep(-2.4, -4.25, y)
    qx = np.abs(x) - hx_taper + 0.52
    qy = np.abs(y - (-2.90)) - 1.32 + 0.52
    d2 = np.sqrt(np.maximum(qx, 0.0) ** 2 + np.maximum(qy, 0.0) ** 2) + np.minimum(np.maximum(qx, qy), 0.0) - 0.52
    wz = np.abs(z - (-0.02)) - 0.46 + 0.18
    w1 = d2 + 0.18
    body = np.sqrt(np.maximum(w1, 0.0) ** 2 + np.maximum(wz, 0.0) ** 2) + np.minimum(np.maximum(w1, wz), 0.0) - 0.18

    # Sculpted upper chest & deltoid shoulder yoke flowing seamlessly up into neck
    traps = sd_ellipsoid(x, y, z, (0.0, -1.66, 0.00), (1.30, 0.46, 0.46))
    d = smin(body, traps, 0.34)

    # Crisp diagonal cyber-armor shoulder contour grooves
    for sx in (-1.0, 1.0):
        sh_g = sd_taper_capsule(x, y, z, (sx * 0.78, -1.68, 0.40), (sx * 1.40, -2.18, 0.36), 0.017, 0.014)
        d = smax(d, -sh_g, 0.016)

    # 11 Precision-Milled 3D Acoustic Grille Slots
    vent_y = YC - 2016.0 / S0   # -4.087
    if np.any(y < -3.75):
        slot_idx = np.clip(np.round(x / 0.145), -5.0, 5.0)
        vx = slot_idx * 0.145
        slot = sd_rbox(x, y, z, (vx, vent_y, CHEST_FRONT + 0.06), (0.026, 0.058, 0.16), 0.024)
        d = smax(d, -slot, 0.012)

    return d


def full_bust_scene(x, y, z):
    # Wide overlap margin (-2.45 .. -0.45) guarantees zero seam on surface, normals, AO & shadow rays!
    y_min = float(y.min())
    y_max = float(y.max())
    if y_max < -2.45:
        d_body = chest_sdf(x, y, z)
    elif y_min > -0.45:
        d_body = head_sdf(x, y, z)
    else:
        d_body = smin(head_sdf(x, y, z), chest_sdf(x, y, z), 0.32)

    # Carve chamfered chest console pocket AFTER head+chest fusion so the top bezel is 100% crisp & straight!
    if y_min < -1.55:
        lip_cut = sd_extruded_rrect(x, y, z, (0.0, PANEL_CY, CHEST_FRONT + 0.14),
                                    PANEL_HX + 0.022, PANEL_HY + 0.022, 0.28, 0.175, 0.052)
        d_body = smax(d_body, -lip_cut, 0.038)

    best = d_body
    mat = np.zeros_like(d_body, dtype=np.int8)

    def put(dd, m_id):
        nonlocal best, mat
        sel = dd < best
        best = np.where(sel, dd, best)
        mat = np.where(sel, m_id, mat)

    # 1. Forehead Visor Glass + Neon Trim
    if y_max > -0.15:
        zv = z + 0.20 * (x * x)
        nose_arch = 0.075 * np.exp(-((x / 0.25) ** 2))
        yv = y - VISOR_Y - nose_arch * sstep(-0.06, -0.32, y - VISOR_Y)
        glass_v = sd_extruded_rrect(x, yv, zv + 0.045 * (x * x + yv * yv),
                                    (0.0, 0.0, 0.805), VISOR_HX - 0.015, VISOR_HY - 0.015, 0.042, 0.19, 0.028)
        put(glass_v, 1)

        vr_out = sd_extruded_rrect(x, yv, zv, (0.0, 0.0, 0.838), VISOR_HX + 0.010, VISOR_HY + 0.010, 0.016, 0.205, 0.010)
        vr_in = sd_extruded_rrect(x, yv, zv, (0.0, 0.0, 0.838), VISOR_HX - 0.014, VISOR_HY - 0.014, 0.036, 0.188, 0.010)
        put(np.maximum(vr_out, -vr_in), 5)

    # 2. Integrated Ear Speaker Socket Chambers, Brushed Aluminum Bezels & Cyan Light-Pipes
    if y_max > -0.65 and y_min < 1.15:
        for sx, cy, cone_r, sock_r in CONES:
            fl = sd_cylinder_z(x, y, z, (sx * POD_X, cy, 0.158), sock_r + 0.004, 0.008)
            put(fl, 6)
            bezel = sd_torus_z(x, y, z, (sx * POD_X, cy, 0.242), sock_r + 0.010, 0.015)
            put(bezel, 2)

        ax = np.abs(x)
        sp_y = np.clip(y - POD_Y, -0.37, 0.37)
        sp_cx = POD_X + 0.198 + 0.036 * np.cos(sp_y * 2.3) + 0.028 * sstep(0.35, -0.45, sp_y)
        spine = np.sqrt((ax - sp_cx) ** 2 + (y - (POD_Y + sp_y)) ** 2 + (z - 0.140) ** 2) - 0.019
        put(spine, 5)

    # 3. Chest Console Glass, Collar Bar & Vent Backing
    if y_min < -1.35:
        glass_c = sd_extruded_rrect(x, y, z, (0.0, PANEL_CY, CHEST_FRONT - 0.10),
                                    PANEL_HX - 0.012, PANEL_HY - 0.012, 0.024, 0.15, 0.016)
        put(glass_c, 1)

        collar_bar = sd_capsule(x, y, z, (-0.42, -1.72, 0.455), (0.42, -1.72, 0.455), 0.016)
        put(collar_bar, 5)

        if y_min < -3.75:
            vent_y = YC - 2016.0 / S0
            vent_back = sd_rbox(x, y, z, (0.0, vent_y, CHEST_FRONT - 0.075), (0.82, 0.068, 0.014), 0.01)
            put(vent_back, 6)

    return best, mat


def full_bust_ao(x, y, z):
    return full_bust_scene(x, y, z)[0]


# ------------------------------------------------------------------ 3x Supersampled Speaker Cone (cone.png)
def render_speaker_cone(size=256):
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
def render_crown_orb(size=256):
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
    ap.add_argument('--ss', type=float, default=1.35, help='Supersample factor for razor-sharp 3D anti-aliasing')
    ap.add_argument('--out', default='app/src/main/assets/skins')
    ap.add_argument('--part', default='all', choices=['all', 'bust', 'sprites'])
    A = ap.parse_args()

    os.makedirs(A.out, exist_ok=True)
    OUT_W = A.w
    OUT_H = int(round(OUT_W * DESIGN_H / DESIGN_W))
    W = int(round(OUT_W * A.ss))
    H = int(round(OUT_H * A.ss))
    S = W / WORLD_W
    px_world = 1.0 / S

    x_from_j = lambda j: (j + 0.5 - W * 0.5) / S
    y_from_i = lambda i: (YC * S - (i + 0.5)) / S

    def to_design_px(wx, wy):
        return [round(wx * S0 + DESIGN_W * 0.5, 1), round(YC * S0 - wy * S0, 1)]

    t0 = time.time()

    if A.part in ('all', 'bust'):
        print(f"[bust] Raymarching UNIFIED 3D Y2K Liquid-Chrome Bust + Ears + Neck ({W}x{H} -> {OUT_W}x{OUT_H})...", flush=True)
        bust_mask = lambda X, Y: (Y < 1.66) & (np.abs(X) < 1.62)
        img = raymarch_layer(full_bust_scene, full_bust_ao, W, H, x_from_j, y_from_i, px_world,
                             z_start=2.4, max_dist=4.6, max_steps=78, eps=0.0008, norm_e=0.0014,
                             chunk_rows=40, mask_fn=bust_mask, do_shadow=True, workers=4)
        if A.ss > 1.01:
            img = downsample_rgba(img, OUT_W, OUT_H)
        img = finish_y2k(img, bloom=0.20, bloom_thr=0.80, haze=0.005)
        save_rgba(img, os.path.join(A.out, 'bust.png'))
        print(f"[bust] Saved unified bust.png ({OUT_W}x{OUT_H}) in {time.time() - t0:.1f}s", flush=True)

    if A.part in ('all', 'sprites'):
        t2 = time.time()
        save_rgba(render_speaker_cone(256), os.path.join(A.out, 'cone.png'))
        save_rgba(render_crown_orb(256), os.path.join(A.out, 'pearl.png'))
        print(f"[sprites] Saved 256x256 cone.png & pearl.png in {time.time() - t2:.1f}s", flush=True)

    if A.part in ('all', 'bust'):
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

        # Notice: NO separate ear_l / ear_r overlay entries — ears are natively fused into unified bust.png!
        meta = dict(
            w=DESIGN_W, h=DESIGN_H,
            cone=dict(w=92, h=92),
            screen=screen,
            panel=PANEL_PX,
            buttons=buttons,
        )
        with open(os.path.join(A.out, 'bust.json'), 'w') as f:
            json.dump(meta, f, indent=2)
        print(f"[bust] Wrote unified bust.json (screen={screen['w']}x{screen['h']}, panel={PANEL_PX['w']}x{PANEL_PX['h']})")
        print(f"[bust] ALL DONE in {time.time() - t0:.1f}s")


if __name__ == '__main__':
    main()
