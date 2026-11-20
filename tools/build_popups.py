"""EARWAX 4.5 — Pure Python High-Resolution 3D Raymarched Early-2000s Y2K Floating Popup & 3D Wing Drawer Skins.

Renders every floating hardware popup & twin 3D sliding wing drawer at true Retina HD resolution
with analytical supersampling (SSAA -> LANCZOS downsample) for razor-sharp chrome bevels:
  1. whale.png   + whale.json   — Y2K Liquid Chrome Cyber Deck + Top 3D Drawers Crest Button
  2. orb.png     + orb.json     — Y2K Chrome Sonic Orb + Top 3D Drawers Crest Button
  3. pill.png    + pill.json    — Y2K Liquid Chrome Mini-Pill + Top 3D Drawers Crest Button
  4. drawer_l.png / drawer_r.png + drawer.json — Twin 3D Sculpted Liquid-Chrome Wing Drawers
     that unfold to the left & right in 3D when pressing the top button!
"""

import argparse
import json
import os
import time
import numpy as np
from PIL import Image
from y2k_shader import (
    clamp, sstep, smin, smax,
    sd_sphere, sd_ellipsoid, sd_capsule, sd_rbox, sd_extruded_rrect,
    sd_cylinder_z, sd_torus_z, sd_2d_capsule, extrude_2d,
    raymarch_layer, finish_y2k, downsample_rgba, add_studio_shadow,
)


def _save_hd_popup(img, out_w, out_h, pad_px, out_path, y_off=12, strength=0.62):
    """Downsamples supersampled float32 RGBA cleanly to crisp HD output + studio drop shadow."""
    if img.shape[1] != out_w or img.shape[0] != out_h:
        img = downsample_rgba(img, out_w, out_h)
    img = finish_y2k(img, bloom=0.20, bloom_thr=0.80, haze=0.005)
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGBA')
    final = add_studio_shadow(im, pad_px, y_off=y_off, strength=strength)
    final.save(out_path, optimize=True)
    return final


# ------------------------------------------------------------------ 1. Y2K LIQUID CHROME CYBER DECK ("whale")
def build_whale(out_dir, scale=1.50, ss=1.35):
    t0 = time.time()
    # Design coordinate space stays 1080x610 (+ 36 pad = 1152x682); actual PNG is rendered at scale * ss -> scale!
    W0, H0 = 1080, 610
    PAD0 = 36
    OUT_W = int(round(W0 * scale))
    OUT_H = int(round(H0 * scale))
    PAD_OUT = int(round(PAD0 * scale))
    W = int(round(OUT_W * ss))
    H = int(round(OUT_H * ss))

    S0 = 300.0
    S = S0 * scale * ss
    px_world = 1.0 / S

    x_from_j = lambda j: (j + 0.5 - W * 0.5) / S
    y_from_i = lambda i: (H * 0.5 - (i + 0.5)) / S
    to_px = lambda wx, wy: (round(wx * S0 + W0 * 0.5 + PAD0, 1), round(H0 * 0.5 - wy * S0 + PAD0, 1))

    LCD_CX, LCD_CY, LCD_HX, LCD_HY = -0.58, 0.08, 0.90, 0.36
    KNOB_CX, KNOB_CY, KNOB_R = 0.78, 0.08, 0.255
    MET_CX, MET_CY, MET_HX, MET_HY = 1.30, 0.08, 0.055, 0.34
    DOCK_CX, DOCK_CY, DOCK_HX, DOCK_HY = 0.00, -0.58, 1.32, 0.155

    # Top-center 3D Drawer Toggle Crest Button!
    DRAWER_BTN = ('drawers', 0.00, 0.74, 0.095, 7)
    CLOSE_BTN  = ('close',   1.48, 0.51, 0.084, 4)

    DOCK_BTNS = [
        ('prev',    -1.15, DOCK_CY, 0.088, 3),
        ('play',    -0.91, DOCK_CY, 0.106, 3),
        ('next',    -0.67, DOCK_CY, 0.088, 3),
        ('t_vol',   -0.31, DOCK_CY, 0.082, 3),
        ('t_pitch',  0.03, DOCK_CY, 0.082, 3),
        ('t_speed',  0.37, DOCK_CY, 0.082, 3),
        ('tool',     0.69, DOCK_CY, 0.080, 3),
        ('eq',       0.92, DOCK_CY, 0.080, 3),
        ('skin',     1.15, DOCK_CY, 0.080, 7),
    ]

    def whale_body_2d(x, y):
        d_main = sd_2d_capsule(x, y, -1.06, 0.07, 1.00, 0.10, 0.62)
        d_wing = sd_2d_capsule(x, y, 0.90, 0.24, 1.46, 0.48, 0.22)
        d_dock = sd_2d_capsule(x, y, -1.08, -0.55, 1.08, -0.55, 0.30)
        d_belly = sd_2d_capsule(x, y, -0.78, -0.24, 0.85, -0.28, 0.48)
        # Sleek sculpted top crest housing the 3D Drawers toggle button!
        d_crest = sd_2d_capsule(x, y, -0.28, 0.69, 0.28, 0.69, 0.20)
        d = smin(d_main, d_dock, 0.22)
        d = smin(d, d_belly, 0.18)
        d = smin(d, d_wing, 0.22)
        d = smin(d, d_crest, 0.16)
        return d

    def scene(x, y, z):
        d2 = whale_body_2d(x, y)
        dome = 0.065 * np.exp(-(x * x * 0.28 + y * y * 0.78))
        d = extrude_2d(d2, z - dome, 0.0, 0.22, 0.125)

        plate_cut = sd_extruded_rrect(x, y, z, (-0.12, 0.08, 0.255), 1.46, 0.44, 0.08, 0.36, 0.03)
        d = smax(d, -plate_cut, 0.018)

        lcd_cut = sd_extruded_rrect(x, y, z, (LCD_CX, LCD_CY, 0.24), LCD_HX + 0.015, LCD_HY + 0.015, 0.12, 0.22, 0.035)
        d = smax(d, -lcd_cut, 0.022)

        knob_well = sd_cylinder_z(x, y, z, (KNOB_CX, KNOB_CY, 0.24), KNOB_R + 0.068, 0.11)
        d = smax(d, -knob_well, 0.020)

        met_cut = sd_extruded_rrect(x, y, z, (MET_CX, MET_CY, 0.24), MET_HX + 0.014, MET_HY + 0.014, 0.10, 0.055, 0.020)
        d = smax(d, -met_cut, 0.012)

        dock_cut = sd_extruded_rrect(x, y, z, (DOCK_CX, DOCK_CY, 0.24), DOCK_HX + 0.04, DOCK_HY + 0.02, 0.095, 0.145, 0.032)
        d = smax(d, -dock_cut, 0.018)

        # Carve recessed pill socket at top-center crest for the 3D Drawers button
        dr_well = sd_extruded_rrect(x, y, z, (DRAWER_BTN[1], DRAWER_BTN[2], 0.235), 0.27, 0.105, 0.08, 0.095, 0.020)
        d = smax(d, -dr_well, 0.014)

        c_well = sd_cylinder_z(x, y, z, (CLOSE_BTN[1], CLOSE_BTN[2], 0.23), CLOSE_BTN[3] + 0.024, 0.08)
        d = smax(d, -c_well, 0.014)

        best = d
        mat = np.zeros_like(d, dtype=np.int8)

        def put(dd, m_id):
            nonlocal best, mat
            sel = dd < best
            best = np.where(sel, dd, best)
            mat = np.where(sel, m_id, mat)

        plate = sd_extruded_rrect(x, y, z, (-0.12, 0.08, 0.172), 1.44, 0.42, 0.022, 0.34, 0.016)
        plate = smax(plate, -lcd_cut, 0.015)
        plate = smax(plate, -knob_well, 0.015)
        plate = smax(plate, -met_cut, 0.010)
        put(plate, 2)

        dx_l = (x - LCD_CX) / LCD_HX
        dy_l = (y - LCD_CY) / LCD_HY
        glass_bulge = 0.018 * (1.0 - np.clip(dx_l * dx_l + dy_l * dy_l, 0.0, 1.0))
        lcd_glass = sd_extruded_rrect(x, y, z - glass_bulge, (LCD_CX, LCD_CY, 0.125),
                                      LCD_HX - 0.008, LCD_HY - 0.008, 0.024, 0.20, 0.015)
        put(lcd_glass, 1)

        knob_halo = sd_torus_z(x, y, z, (KNOB_CX, KNOB_CY, 0.170), KNOB_R + 0.036, 0.016)
        put(knob_halo, 5)

        krho = np.sqrt((x - KNOB_CX) ** 2 + (y - KNOB_CY) ** 2)
        dish = -0.016 * (1.0 - np.clip((krho / (KNOB_R * 0.85)) ** 2, 0.0, 1.0))
        knob_cap = sd_cylinder_z(x, y, z - dish, (KNOB_CX, KNOB_CY, 0.212), KNOB_R, 0.068)
        knob_bevel = sd_torus_z(x, y, z, (KNOB_CX, KNOB_CY, 0.262), KNOB_R - 0.022, 0.022)
        put(smin(knob_cap, knob_bevel, 0.016), 2)
        put(sd_torus_z(x, y, z, (KNOB_CX, KNOB_CY, 0.268), KNOB_R - 0.026, 0.011), 0)

        met_glass = sd_extruded_rrect(x, y, z, (MET_CX, MET_CY, 0.145), MET_HX - 0.004, MET_HY - 0.004, 0.020, 0.046, 0.012)
        put(met_glass, 1)

        dock_floor = sd_extruded_rrect(x, y, z, (DOCK_CX, DOCK_CY, 0.135), DOCK_HX + 0.01, DOCK_HY - 0.005, 0.020, 0.13, 0.012)
        put(dock_floor, 1)

        for bid, bx, by, br, mid in DOCK_BTNS:
            if bid.startswith('t_'):
                chip = sd_extruded_rrect(x, y, z, (bx, by, 0.172), br * 1.48, br * 0.72, 0.036, br * 0.68, 0.024)
                put(chip, 6)
            else:
                put(sd_torus_z(x, y, z, (bx, by, 0.158), br + 0.010, 0.010), 0)
                put(sd_ellipsoid(x, y, z, (bx, by, 0.165), (br, br, br * 0.62)), mid)

        # Top-center 3D Aqua-Cyan Glowing Pill Button for 3D Drawers!
        dr_pill = sd_extruded_rrect(x, y, z, (DRAWER_BTN[1], DRAWER_BTN[2], 0.190), 0.245, 0.082, 0.048, 0.078, 0.034)
        put(dr_pill, 3)
        dr_ring = sd_extruded_rrect(x, y, z, (DRAWER_BTN[1], DRAWER_BTN[2], 0.176), 0.262, 0.095, 0.012, 0.090, 0.008)
        put(np.maximum(dr_ring, -(dr_pill - 0.004)), 5)

        _, cx, cy, cr, cmid = CLOSE_BTN
        put(sd_torus_z(x, y, z, (cx, cy, 0.190), cr + 0.014, 0.012), 0)
        put(sd_ellipsoid(x, y, z, (cx, cy, 0.195), (cr, cr, cr * 0.65)), cmid)

        d_rim = np.sqrt((d2 + 0.058) ** 2 + (z - 0.215) ** 2) - 0.011
        pipe_l = smax(d_rim, smax(np.abs(y - 0.10) - 0.28, x + 1.44, 0.03), 0.018)
        pipe_r = smax(d_rim, smax(np.abs(y - (-0.04)) - 0.24, 1.42 - x, 0.03), 0.018)
        put(np.minimum(pipe_l, pipe_r), 5)

        return best, mat

    print(f"[popup:whale] Raymarching HD Cyber Deck ({W}x{H} -> {OUT_W}x{OUT_H})...", flush=True)
    img = raymarch_layer(scene, lambda x, y, z: scene(x, y, z)[0], W, H, x_from_j, y_from_i, px_world,
                         z_start=1.6, max_dist=3.0, max_steps=72, eps=0.0009, norm_e=0.0015,
                         chunk_rows=40, do_shadow=True, workers=4)
    final = _save_hd_popup(img, OUT_W, OUT_H, PAD_OUT, os.path.join(out_dir, 'whale.png'),
                           y_off=int(round(11 * scale)), strength=0.62)

    lx0, ly0 = to_px(LCD_CX - LCD_HX, LCD_CY + LCD_HY)
    lx1, ly1 = to_px(LCD_CX + LCD_HX, LCD_CY - LCD_HY)
    mx0, my0 = to_px(MET_CX - MET_HX, MET_CY + MET_HY)
    mx1, my1 = to_px(MET_CX + MET_HX, MET_CY - MET_HY)
    dx0, dy0 = to_px(DOCK_CX - DOCK_HX, DOCK_CY + DOCK_HY)
    dx1, dy1 = to_px(DOCK_CX + DOCK_HX, DOCK_CY - DOCK_HY)
    kcx, kcy = to_px(KNOB_CX, KNOB_CY)
    kr_px = round(KNOB_R * S0, 1)

    btns = [dict(id='knob', cx=kcx, cy=kcy, r=kr_px)]
    for bid, bx, by, br, _ in DOCK_BTNS + [CLOSE_BTN, DRAWER_BTN]:
        px_, py_ = to_px(bx, by)
        btns.append(dict(id=bid, cx=px_, cy=py_, r=round(br * S0, 1)))

    meta = dict(
        w=W0 + 2 * PAD0, h=H0 + 2 * PAD0, pad=PAD0,
        lcd=dict(x=lx0, y=ly0, w=round(lx1 - lx0, 1), h=round(ly1 - ly0, 1), r=52),
        info=dict(x=round(lx0 + 185, 1), y=round(ly1 - 62, 1), w=round(lx1 - lx0 - 205, 1), h=56, r=14),
        meter=dict(x=mx0, y=my0, w=round(mx1 - mx0, 1), h=round(my1 - my0, 1), r=14),
        dock=dict(x=dx0, y=dy0, w=round(dx1 - dx0, 1), h=round(dy1 - dy0, 1), r=38),
        knob=dict(id='knob', cx=kcx, cy=kcy, r=kr_px),
        buttons=btns,
    )
    with open(os.path.join(out_dir, 'whale.json'), 'w') as f:
        json.dump(meta, f, indent=2)
    print(f"[popup:whale] Done in {time.time() - t0:.1f}s ({final.size})", flush=True)


# ------------------------------------------------------------------ 2. Y2K CHROME SONIC ORB ("orb")
def build_orb(out_dir, scale=1.50, ss=1.35):
    t0 = time.time()
    S0_PX = 740
    PAD0 = 36
    OUT_S = int(round(S0_PX * scale))
    PAD_OUT = int(round(PAD0 * scale))
    W = int(round(OUT_S * ss))
    H = W
    S0 = S0_PX / 2.10
    S = S0 * scale * ss
    px_world = 1.0 / S

    x_from_j = lambda j: (j + 0.5 - W * 0.5) / S
    y_from_i = lambda i: (H * 0.5 - (i + 0.5)) / S
    to_px = lambda wx, wy: (round(wx * S0 + S0_PX * 0.5 + PAD0, 1), round(S0_PX * 0.5 - wy * S0 + PAD0, 1))

    LCD_R = 0.455
    DIAL_R0, DIAL_R1 = 0.785, 0.905
    # Top-center button is now the 3D Drawers toggle! Ruby close sits cleanly on upper-right!
    BTNS = [
        ('prev',    -0.31, -0.585, 0.095, 3),
        ('play',     0.00, -0.640, 0.122, 3),
        ('next',     0.31, -0.585, 0.095, 3),
        ('tool',    -0.61, -0.145, 0.088, 3),
        ('skin',     0.61, -0.145, 0.088, 7),
        ('drawers',  0.00,  0.635, 0.088, 3),
        ('close',    0.49,  0.430, 0.074, 4),
    ]

    def scene(x, y, z):
        rho = np.sqrt(x * x + y * y)
        ang = np.degrees(np.arctan2(x, y))

        dome = 0.15 * np.sqrt(np.maximum(0.0, 1.0 - np.clip(rho / 1.04, 0.0, 1.0) ** 2))
        d = extrude_2d(rho - 1.00, z - dome, 0.0, 0.19, 0.145)

        step_ring = np.maximum(np.abs(rho - 0.725) - 0.012, 0.255 - z)
        d = smax(d, -step_ring, 0.008)

        mid_r = 0.5 * (DIAL_R0 + DIAL_R1)
        half_w = 0.5 * (DIAL_R1 - DIAL_R0)
        ang_rad = np.radians(np.clip(ang, -132.0, 132.0))
        arc_x = np.sin(ang_rad) * mid_r
        arc_y = np.cos(ang_rad) * mid_r
        d_arc2d = np.sqrt((x - arc_x) ** 2 + (y - arc_y) ** 2) - half_w
        track_cut = np.maximum(d_arc2d, (0.205 + dome * 0.55) - z)
        d = smax(d, -track_cut, 0.015)

        lcd_cut = np.maximum(rho - (LCD_R + 0.024), 0.23 - z)
        d = smax(d, -lcd_cut, 0.020)

        for bid, bx, by, br, _ in BTNS:
            b_dome = 0.15 * np.sqrt(max(0.0, 1.0 - (np.hypot(bx, by) / 1.04) ** 2))
            well = sd_cylinder_z(x, y, z, (bx, by, 0.20 + b_dome), br + 0.022, 0.095)
            d = smax(d, -well, 0.014)

        best = d
        mat = np.zeros_like(d, dtype=np.int8)

        def put(dd, m_id):
            nonlocal best, mat
            sel = dd < best
            best = np.where(sel, dd, best)
            mat = np.where(sel, m_id, mat)

        mid_face = np.maximum(np.abs(rho - 0.60) - 0.115, np.abs(z - (0.18 + dome * 0.92)) - 0.015)
        mid_face = np.maximum(mid_face, d - 0.002)
        put(mid_face, 2)

        track_fl = np.maximum(d_arc2d + 0.006, np.abs(z - (0.202 + dome * 0.55)) - 0.012)
        put(track_fl, 6)

        visor_ring = sd_torus_z(x, y, z, (0.0, 0.0, 0.302), LCD_R + 0.014, 0.011)
        put(visor_ring, 5)

        glass_bulge = 0.036 * (1.0 - np.clip((rho / LCD_R) ** 2, 0.0, 1.0))
        lcd_glass = sd_cylinder_z(x, y, z - glass_bulge, (0.0, 0.0, 0.258), LCD_R - 0.004, 0.022)
        put(lcd_glass, 1)

        for bid, bx, by, br, mid in BTNS:
            b_dome = 0.15 * np.sqrt(max(0.0, 1.0 - (np.hypot(bx, by) / 1.04) ** 2))
            zb = 0.175 + b_dome
            put(sd_torus_z(x, y, z, (bx, by, zb + 0.012), br + 0.010, 0.010), 5 if bid == 'drawers' else 0)
            put(sd_ellipsoid(x, y, z, (bx, by, zb + 0.016), (br, br, br * 0.64)), mid)

        return best, mat

    print(f"[popup:orb] Raymarching HD Sonic Orb ({W}x{H} -> {OUT_S}x{OUT_S})...", flush=True)
    img = raymarch_layer(scene, lambda x, y, z: scene(x, y, z)[0], W, H, x_from_j, y_from_i, px_world,
                         z_start=1.6, max_dist=3.0, max_steps=72, eps=0.0009, norm_e=0.0015,
                         chunk_rows=40, do_shadow=True, workers=4)
    final = _save_hd_popup(img, OUT_S, OUT_S, PAD_OUT, os.path.join(out_dir, 'orb.png'),
                           y_off=int(round(10 * scale)), strength=0.62)

    cx, cy = to_px(0.0, 0.0)
    lr = round(LCD_R * S0, 1)
    btns = []
    for bid, bx, by, br, _ in BTNS:
        px_, py_ = to_px(bx, by)
        btns.append(dict(id=bid, cx=px_, cy=py_, r=round(br * S0, 1)))

    meta = dict(
        w=S0_PX + 2 * PAD0, h=S0_PX + 2 * PAD0, pad=PAD0,
        lcd=dict(x=round(cx - lr, 1), y=round(cy - lr, 1), w=round(lr * 2, 1), h=round(lr * 2, 1), r=lr),
        info=dict(x=round(cx - lr * 0.74, 1), y=round(cy + lr * 0.16, 1), w=round(lr * 1.48, 1), h=34, r=12),
        spec=dict(x=round(cx - lr * 0.56, 1), y=round(cy + lr * 0.44, 1), w=round(lr * 1.12, 1), h=44, r=12),
        dial=dict(cx=cx, cy=cy, r0=round(DIAL_R0 * S0, 1), r1=round(DIAL_R1 * S0, 1), a0=-132, a1=132),
        buttons=btns,
    )
    with open(os.path.join(out_dir, 'orb.json'), 'w') as f:
        json.dump(meta, f, indent=2)
    print(f"[popup:orb] Done in {time.time() - t0:.1f}s ({final.size})", flush=True)


# ------------------------------------------------------------------ 3. Y2K LIQUID CHROME MINI-PILL ("pill")
def build_pill(out_dir, scale=1.50, ss=1.35):
    t0 = time.time()
    W0, H0 = 1000, 340
    PAD0 = 30
    OUT_W = int(round(W0 * scale))
    OUT_H = int(round(H0 * scale))
    PAD_OUT = int(round(PAD0 * scale))
    W = int(round(OUT_W * ss))
    H = int(round(OUT_H * ss))
    S0 = 280.0
    S = S0 * scale * ss
    px_world = 1.0 / S

    x_from_j = lambda j: (j + 0.5 - W * 0.5) / S
    y_from_i = lambda i: (H * 0.5 - (i + 0.5)) / S
    to_px = lambda wx, wy: (round(wx * S0 + W0 * 0.5 + PAD0, 1), round(H0 * 0.5 - wy * S0 + PAD0, 1))

    LCD_CX, LCD_CY, LCD_HX, LCD_HY = -0.58, -0.06, 0.99, 0.30
    DRAWER_BTN = ('drawers', 0.00, 0.46, 0.090, 3)
    BTNS = [
        ('prev',  0.64, -0.06, 0.108, 3),
        ('play',  0.93, -0.06, 0.134, 3),
        ('next',  1.22, -0.06, 0.108, 3),
        ('skin',  1.47,  0.125, 0.072, 7),
        ('close', 1.47, -0.245, 0.072, 4),
    ]

    def scene(x, y, z):
        d_pill = sd_2d_capsule(x, y, -1.25, -0.06, 1.25, -0.06, 0.48)
        d_tab  = sd_2d_capsule(x, y, -0.24,  0.42, 0.24,  0.42, 0.17)
        d2 = smin(d_pill, d_tab, 0.14)
        dome = 0.055 * np.exp(-(x * x * 0.18 + y * y * 2.0))
        d = extrude_2d(d2, z - dome, 0.0, 0.21, 0.13)

        bay_cut = sd_extruded_rrect(x, y, z, (0.0, -0.06, 0.24), 1.62, 0.34, 0.09, 0.32, 0.030)
        d = smax(d, -bay_cut, 0.018)

        lcd_cut = sd_extruded_rrect(x, y, z, (LCD_CX, LCD_CY, 0.21), LCD_HX + 0.012, LCD_HY + 0.012, 0.10, 0.28, 0.025)
        d = smax(d, -lcd_cut, 0.015)

        dr_well = sd_extruded_rrect(x, y, z, (DRAWER_BTN[1], DRAWER_BTN[2], 0.23), 0.24, 0.095, 0.08, 0.085, 0.018)
        d = smax(d, -dr_well, 0.014)

        best = d
        mat = np.zeros_like(d, dtype=np.int8)

        def put(dd, m_id):
            nonlocal best, mat
            sel = dd < best
            best = np.where(sel, dd, best)
            mat = np.where(sel, m_id, mat)

        ctrl_plate = sd_extruded_rrect(x, y, z, (0.0, -0.06, 0.145), 1.59, 0.32, 0.024, 0.30, 0.015)
        ctrl_plate = smax(ctrl_plate, -lcd_cut, 0.012)
        put(ctrl_plate, 2)

        lcd_glass = sd_extruded_rrect(x, y, z, (LCD_CX, LCD_CY, 0.125), LCD_HX - 0.005, LCD_HY - 0.005, 0.024, 0.26, 0.014)
        put(lcd_glass, 1)

        pipe_bot = sd_capsule(x, y, z, (-1.15, -0.465, 0.205), (1.15, -0.465, 0.205), 0.015)
        put(pipe_bot, 5)

        # Top 3D Drawers pill button
        dr_pill = sd_extruded_rrect(x, y, z, (DRAWER_BTN[1], DRAWER_BTN[2], 0.185), 0.215, 0.075, 0.044, 0.072, 0.030)
        put(dr_pill, 3)

        for bid, bx, by, br, mid in BTNS:
            put(sd_torus_z(x, y, z, (bx, by, 0.172), br + 0.012, 0.012), 0)
            put(sd_ellipsoid(x, y, z, (bx, by, 0.178), (br, br, br * 0.65)), mid)

        return best, mat

    print(f"[popup:pill] Raymarching HD Mini-Pill ({W}x{H} -> {OUT_W}x{OUT_H})...", flush=True)
    img = raymarch_layer(scene, lambda x, y, z: scene(x, y, z)[0], W, H, x_from_j, y_from_i, px_world,
                         z_start=1.6, max_dist=3.0, max_steps=72, eps=0.0009, norm_e=0.0015,
                         chunk_rows=40, do_shadow=True, workers=4)
    final = _save_hd_popup(img, OUT_W, OUT_H, PAD_OUT, os.path.join(out_dir, 'pill.png'),
                           y_off=int(round(9 * scale)), strength=0.60)

    lx0, ly0 = to_px(LCD_CX - LCD_HX, LCD_CY + LCD_HY)
    lx1, ly1 = to_px(LCD_CX + LCD_HX, LCD_CY - LCD_HY)
    btns = []
    for bid, bx, by, br, _ in BTNS + [DRAWER_BTN]:
        px_, py_ = to_px(bx, by)
        btns.append(dict(id=bid, cx=px_, cy=py_, r=round(br * S0, 1)))

    meta = dict(
        w=W0 + 2 * PAD0, h=H0 + 2 * PAD0, pad=PAD0,
        lcd=dict(x=lx0, y=ly0, w=round(lx1 - lx0, 1), h=round(ly1 - ly0, 1), r=round((ly1 - ly0) * 0.5, 1)),
        info=dict(x=round(lx0 + 140, 1), y=round(ly1 - 36, 1), w=round(lx1 - lx0 - 165, 1), h=36.0, r=12),
        buttons=btns,
    )
    with open(os.path.join(out_dir, 'pill.json'), 'w') as f:
        json.dump(meta, f, indent=2)
    print(f"[popup:pill] Done in {time.time() - t0:.1f}s ({final.size})", flush=True)


# ------------------------------------------------------------------ 4. TWIN 3D WING DRAWERS ("drawer_l" & "drawer_r")
def build_drawers(out_dir, scale=1.35, ss=1.35):
    """Pure-Python 3D Raymarched Twin Sliding Liquid-Chrome Wing Panels (Left & Right in 3D!)."""
    t0 = time.time()
    W0, H0 = 540, 680
    PAD0 = 26
    OUT_W = int(round(W0 * scale))
    OUT_H = int(round(H0 * scale))
    PAD_OUT = int(round(PAD0 * scale))
    W = int(round(OUT_W * ss))
    H = int(round(OUT_H * ss))
    S0 = 260.0
    S = S0 * scale * ss
    px_world = 1.0 / S

    x_from_j = lambda j: (j + 0.5 - W * 0.5) / S
    y_from_i = lambda i: (H * 0.5 - (i + 0.5)) / S
    to_px = lambda wx, wy: (round(wx * S0 + W0 * 0.5 + PAD0, 1), round(H0 * 0.5 - wy * S0 + PAD0, 1))

    SCR_HX, SCR_HY = 0.78, 1.05

    for side, name in ((-1.0, 'drawer_l'), (1.0, 'drawer_r')):
        # side = -1 (Left 3D Wing: outer grip on left -x, hinge spine on right +x, subtly angled in 3D!)
        # side = +1 (Right 3D Wing: outer grip on right +x, hinge spine on left -x, subtly angled in 3D!)
        def make_scene(s_dir=side):
            def scene(x, y, z):
                # Subtle 3D perspective wing slant: outer edge sweeps forward (+z), hinge recedes slightly
                z_tilt = z - s_dir * x * 0.085
                # Sleek sculpted Y2K chrome drawer slab with organic tapered outer edge
                hx_taper = 0.94 - 0.04 * (y * y * 0.32)
                qx = np.abs(x) - hx_taper + 0.24
                qy = np.abs(y) - 1.22 + 0.24
                d2 = np.sqrt(np.maximum(qx, 0.0) ** 2 + np.maximum(qy, 0.0) ** 2) + np.minimum(np.maximum(qx, qy), 0.0) - 0.24
                dome = 0.045 * np.exp(-(x * x * 0.65 + y * y * 0.35))
                d = extrude_2d(d2, z_tilt - dome, 0.0, 0.165, 0.095)

                # Sculpted 3D metallic wing pull-handle lip on outer edge (side * +0.96)
                grip = sd_capsule(x, y, z_tilt, (s_dir * 0.94, 0.42, 0.06), (s_dir * 0.94, -0.42, 0.06), 0.065)
                d = smin(d, grip, 0.055)

                # Twin chrome 3D hinge knuckles on inner spine (-side * 0.93)
                for hy in (0.72, -0.72):
                    knuckle = sd_capsule(x, y, z_tilt, (-s_dir * 0.88, hy + 0.14, -0.02),
                                         (-s_dir * 0.88, hy - 0.14, -0.02), 0.072)
                    d = smin(d, knuckle, 0.04)

                # Carve deep chamfered obsidian glass display cavity inside the 3D chrome bezel!
                scr_cx = -s_dir * 0.02
                scr_cut = sd_extruded_rrect(x, y, z_tilt, (scr_cx, 0.0, 0.20),
                                            SCR_HX + 0.018, SCR_HY + 0.018, 0.115, 0.165, 0.032)
                d = smax(d, -scr_cut, 0.020)

                best = d
                mat = np.zeros_like(d, dtype=np.int8)

                def put(dd, m_id):
                    nonlocal best, mat
                    sel = dd < best
                    best = np.where(sel, dd, best)
                    mat = np.where(sel, m_id, mat)

                # Curved Smoked Obsidian-Sapphire Glass Screen (Mat 1)
                glass = sd_extruded_rrect(x, y, z_tilt, (scr_cx, 0.0, 0.098),
                                          SCR_HX - 0.005, SCR_HY - 0.005, 0.022, 0.15, 0.014)
                put(glass, 1)

                # Electric-Cyan Optical Light-Pipe Rail along the outer wing flank (Mat 5)
                rail = sd_capsule(x, y, z_tilt, (s_dir * 0.865, 0.78, 0.155),
                                  (s_dir * 0.865, -0.78, 0.155), 0.016)
                put(rail, 5)

                # Brushed-aluminum top & bottom inner accent bars
                top_bar = sd_capsule(x, y, z_tilt, (-0.42, 1.135, 0.155), (0.42, 1.135, 0.155), 0.014)
                put(top_bar, 5)

                return best, mat
            return scene

        sc_fn = make_scene(side)
        print(f"[popup:{name}] Raymarching HD 3D Wing Drawer ({W}x{H} -> {OUT_W}x{OUT_H})...", flush=True)
        img = raymarch_layer(sc_fn, lambda x, y, z: sc_fn(x, y, z)[0], W, H, x_from_j, y_from_i, px_world,
                             z_start=1.6, max_dist=3.0, max_steps=70, eps=0.0009, norm_e=0.0015,
                             chunk_rows=40, do_shadow=True, workers=4)
        final = _save_hd_popup(img, OUT_W, OUT_H, PAD_OUT, os.path.join(out_dir, f'{name}.png'),
                               y_off=int(round(10 * scale)), strength=0.58)
        print(f"[popup:{name}] Done ({final.size})", flush=True)

    sx0, sy0 = to_px(-SCR_HX, SCR_HY)
    sx1, sy1 = to_px(SCR_HX, -SCR_HY)
    meta = dict(
        w=W0 + 2 * PAD0, h=H0 + 2 * PAD0, pad=PAD0,
        screen=dict(x=sx0, y=sy0, w=round(sx1 - sx0, 1), h=round(sy1 - sy0, 1), r=38),
    )
    with open(os.path.join(out_dir, 'drawer.json'), 'w') as f:
        json.dump(meta, f, indent=2)
    print(f"[popup:drawers] Finished both 3D wing drawers in {time.time() - t0:.1f}s!", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='app/src/main/assets/skins')
    ap.add_argument('--scale', type=float, default=1.50)
    ap.add_argument('--ss', type=float, default=1.35)
    ap.add_argument('--only', default='all', choices=['all', 'whale', 'orb', 'pill', 'drawers'])
    A = ap.parse_args()
    os.makedirs(A.out, exist_ok=True)
    if A.only in ('all', 'whale'):
        build_whale(A.out, A.scale, A.ss)
    if A.only in ('all', 'orb'):
        build_orb(A.out, A.scale, A.ss)
    if A.only in ('all', 'pill'):
        build_pill(A.out, A.scale, A.ss)
    if A.only in ('all', 'drawers'):
        build_drawers(A.out, min(1.35, A.scale), A.ss)


if __name__ == '__main__':
    main()
