"""EARWAX 4.0 — Pure Python 3D Raymarched Early-2000s Y2K Floating Popup Skins.

Redesigns all 3 floating hardware popups from scratch using analytical 3D SDF raymarching
(ZERO jagged 2D heightfield bevels, ZERO AI artifacts, genuine 3D studio chrome + Aqua gel jewels):
  1. whale.png + whale.json — Y2K Liquid Chrome Cyber Deck (organic sculpted body, recessed curved
                              glass display, machined brushed-aluminum jog wheel w/ cyan halo,
                              vertical crystal level tube, glossy Aqua & Ruby gel jewel buttons)
  2. orb.png   + orb.json   — Y2K Chrome Sonic Orb (domed liquid-chrome puck, razor-smooth 270-deg
                              recessed orbital track, central circular curved visor, Aqua & Ruby jewels)
  3. pill.png  + pill.json  — Y2K Liquid Chrome Mini-Pill (MiniDisc-inspired sculpted chrome lozenge,
                              twin electric-cyan optical light-pipes, curved display, Aqua & Ruby jewels)

Usage:
  python3 tools/build_popups.py --out app/src/main/assets/skins
  python3 tools/build_popups.py --out /tmp/preview --scale 0.5  (fast preview)
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

# ------------------------------------------------------------------ Helper: 3D Aqua / Ruby Gel Jewel in Chrome Socket
def carve_socket_and_jewel(d_body, best, mat, x, y, z, cx, cy, z_surf, r_jewel, mat_id):
    """Carves a crisp recessed chrome socket into d_body and embeds a glossy 3D dome gel jewel."""
    # Recessed bevel well
    well = sd_cylinder_z(x, y, z, (cx, cy, z_surf + 0.045), r_jewel + 0.014, 0.065)
    d_body = smax(d_body, -well, 0.012)
    # Glossy domed 3D Aqua/Ruby gel jewel rising smoothly from socket
    jewel = sd_ellipsoid(x, y, z, (cx, cy, z_surf - 0.004), (r_jewel, r_jewel, r_jewel * 0.62))
    sel = jewel < best
    best = np.where(sel, jewel, best)
    mat = np.where(sel, mat_id, mat)
    return d_body, best, mat

# ------------------------------------------------------------------ 1. Y2K LIQUID CHROME CYBER DECK ("whale")
def build_whale(out_dir, scale=1.0):
    t0 = time.time()
    W0, H0 = 1080, 560
    PAD = 36
    W = int(round(W0 * scale))
    H = int(round(H0 * scale))
    # World space: x in [-1.80, +1.80], y in [-0.933, +0.933], 1 world unit = 300 design px
    S0 = 300.0
    S = S0 * scale
    px_world = 1.0 / S

    x_from_j = lambda j: (j + 0.5 - W * 0.5) / S
    y_from_i = lambda i: (H * 0.5 - (i + 0.5)) / S
    to_px = lambda wx, wy: (round(wx * S0 + W0 * 0.5 + PAD, 1), round(H0 * 0.5 - wy * S0 + PAD, 1))

    # Layout in world coords (1 unit = 300 px)
    # Display screen bay (left-upper)
    LCD_CX, LCD_CY, LCD_HX, LCD_HY = -0.58, 0.14, 0.90, 0.36
    # Brushed aluminum rotary jog wheel (right-upper)
    KNOB_CX, KNOB_CY, KNOB_R = 0.78, 0.14, 0.255
    # Vertical level meter bar (far right)
    MET_CX, MET_CY, MET_HX, MET_HY = 1.30, 0.14, 0.055, 0.34
    # Recessed lower dock bar
    DOCK_CX, DOCK_CY, DOCK_HX, DOCK_HY = 0.00, -0.52, 1.32, 0.155

    # Glossy 3D Gel Jewel Buttons along the lower sculpted dock + Ruby Close jewel at upper-right shoulder
    DOCK_BTNS = [
        ('prev',    -1.15, DOCK_CY, 0.088, 3),
        ('play',    -0.91, DOCK_CY, 0.106, 3),
        ('next',    -0.67, DOCK_CY, 0.088, 3),
        ('t_vol',   -0.31, DOCK_CY, 0.082, 3),
        ('t_pitch',  0.03, DOCK_CY, 0.082, 3),
        ('t_speed',  0.37, DOCK_CY, 0.082, 3),
        ('tool',     0.69, DOCK_CY, 0.080, 3),
        ('eq',       0.92, DOCK_CY, 0.080, 3),
        ('skin',     1.15, DOCK_CY, 0.080, 7),  # Emerald-aqua morph jewel!
    ]
    CLOSE_BTN = ('close', 1.48, 0.57, 0.082, 4)  # Ruby-cherry close jewel!

    def whale_body_2d(x, y):
        # Iconic Early-2000s asymmetric liquid-chrome wave silhouette (sleek ergonomic swoop!)
        # Main upper wave + sculpted rounded lower dock belly + dynamic right wing tip
        d_main = sd_2d_capsule(x, y, -1.06, 0.13, 1.00, 0.16, 0.62)
        d_wing = sd_2d_capsule(x, y, 0.90, 0.30, 1.46, 0.54, 0.22)
        d_dock = sd_2d_capsule(x, y, -1.08, -0.49, 1.08, -0.49, 0.30)
        d_belly = sd_2d_capsule(x, y, -0.78, -0.18, 0.85, -0.22, 0.48)
        d = smin(d_main, d_dock, 0.22)
        d = smin(d, d_belly, 0.18)
        d = smin(d, d_wing, 0.22)
        return d

    def scene(x, y, z):
        # 1. Flowing 3D Chrome Outer Chassis with convex organic dome curvature
        d2 = whale_body_2d(x, y)
        # Subtle organic pillow dome on front face for luscious curved chrome reflections!
        dome = 0.065 * np.exp(-(x * x * 0.28 + y * y * 0.85))
        d = extrude_2d(d2, z - dome, 0.0, 0.22, 0.125)

        # Recessed brushed-aluminum upper control plate contour
        plate_cut = sd_extruded_rrect(x, y, z, (-0.12, 0.14, 0.255), 1.46, 0.44, 0.08, 0.36, 0.03)
        d = smax(d, -plate_cut, 0.018)

        # Carve curved obsidian glass display bay (left-upper)
        lcd_cut = sd_extruded_rrect(x, y, z, (LCD_CX, LCD_CY, 0.24), LCD_HX + 0.015, LCD_HY + 0.015, 0.12, 0.22, 0.035)
        d = smax(d, -lcd_cut, 0.022)

        # Carve circular machined well for the rotary jog wheel
        knob_well = sd_cylinder_z(x, y, z, (KNOB_CX, KNOB_CY, 0.24), KNOB_R + 0.068, 0.11)
        d = smax(d, -knob_well, 0.020)

        # Carve vertical illuminated level meter slot
        met_cut = sd_extruded_rrect(x, y, z, (MET_CX, MET_CY, 0.24), MET_HX + 0.014, MET_HY + 0.014, 0.10, 0.055, 0.020)
        d = smax(d, -met_cut, 0.012)

        # Carve sculpted recessed lower control dock trough
        dock_cut = sd_extruded_rrect(x, y, z, (DOCK_CX, DOCK_CY, 0.24), DOCK_HX + 0.04, DOCK_HY + 0.02, 0.095, 0.145, 0.032)
        d = smax(d, -dock_cut, 0.018)

        # Carve chrome socket for the ruby close jewel at upper-right wing
        c_well = sd_cylinder_z(x, y, z, (CLOSE_BTN[1], CLOSE_BTN[2], 0.23), CLOSE_BTN[3] + 0.024, 0.08)
        d = smax(d, -c_well, 0.014)

        best = d
        mat = np.zeros_like(d, dtype=np.int8)

        def put(dd, m_id):
            nonlocal best, mat
            sel = dd < best
            best = np.where(sel, dd, best)
            mat = np.where(sel, m_id, mat)

        # 2. Recessed Brushed Satin Aluminum Upper Faceplate (Mat 2)
        plate = sd_extruded_rrect(x, y, z, (-0.12, 0.14, 0.172), 1.44, 0.42, 0.022, 0.34, 0.016)
        plate = smax(plate, -lcd_cut, 0.015)
        plate = smax(plate, -knob_well, 0.015)
        plate = smax(plate, -met_cut, 0.010)
        put(plate, 2)

        # 3. Curved Smoked Sapphire-Obsidian Media Visor Glass (Mat 1)
        dx_l = (x - LCD_CX) / LCD_HX
        dy_l = (y - LCD_CY) / LCD_HY
        glass_bulge = 0.018 * (1.0 - np.clip(dx_l * dx_l + dy_l * dy_l, 0.0, 1.0))
        lcd_glass = sd_extruded_rrect(x, y, z - glass_bulge, (LCD_CX, LCD_CY, 0.125),
                                      LCD_HX - 0.008, LCD_HY - 0.008, 0.024, 0.20, 0.015)
        put(lcd_glass, 1)

        # 4. Luminous Electric-Cyan Halo Ring around Rotary Jog Wheel (Mat 5)
        knob_halo = sd_torus_z(x, y, z, (KNOB_CX, KNOB_CY, 0.170), KNOB_R + 0.036, 0.016)
        put(knob_halo, 5)

        # 5. 3D Machined Brushed-Aluminum Rotary Jog Wheel (smooth concave dish + mirror bevel lip)
        krho = np.sqrt((x - KNOB_CX) ** 2 + (y - KNOB_CY) ** 2)
        dish = -0.016 * (1.0 - np.clip((krho / (KNOB_R * 0.85)) ** 2, 0.0, 1.0))
        knob_cap = sd_cylinder_z(x, y, z - dish, (KNOB_CX, KNOB_CY, 0.212), KNOB_R, 0.068)
        knob_bevel = sd_torus_z(x, y, z, (KNOB_CX, KNOB_CY, 0.262), KNOB_R - 0.022, 0.022)
        knob_body = smin(knob_cap, knob_bevel, 0.016)
        put(knob_body, 2)
        # Gleaming mirror-chrome chamfer ring around outer edge of jog wheel
        put(sd_torus_z(x, y, z, (KNOB_CX, KNOB_CY, 0.268), KNOB_R - 0.026, 0.011), 0)

        # 6. Vertical Recessed Glass Level Meter Tube (Mat 1)
        met_glass = sd_extruded_rrect(x, y, z, (MET_CX, MET_CY, 0.145), MET_HX - 0.004, MET_HY - 0.004, 0.020, 0.046, 0.012)
        put(met_glass, 1)

        # 7. Recessed Obsidian Lower Dock Floor (Mat 1)
        dock_floor = sd_extruded_rrect(x, y, z, (DOCK_CX, DOCK_CY, 0.135), DOCK_HX + 0.01, DOCK_HY - 0.005, 0.020, 0.13, 0.012)
        put(dock_floor, 1)

        # 8. Glossy 3D Early-2000s Aqua & Emerald Gel Buttons along Dock (only icons for transport & tool/eq/skin!)
        for bid, bx, by, br, mid in DOCK_BTNS:
            if bid.startswith('t_'):
                # Sleek pill-shaped glossy gel selector chip
                chip = sd_extruded_rrect(x, y, z, (bx, by, 0.172), br * 1.48, br * 0.72, 0.036, br * 0.68, 0.024)
                put(chip, 6)  # dark titanium recessed pill (lights up in app Canvas!)
            else:
                # Glossy dome gel button inside micro chrome bezel ring
                bez_r = sd_torus_z(x, y, z, (bx, by, 0.158), br + 0.010, 0.010)
                put(bez_r, 0)
                jewel = sd_ellipsoid(x, y, z, (bx, by, 0.165), (br, br, br * 0.62))
                put(jewel, mid)

        # 9. Ruby-Cherry Close Jewel at Upper-Right Shoulder (Mat 4)
        _, cx, cy, cr, cmid = CLOSE_BTN
        put(sd_torus_z(x, y, z, (cx, cy, 0.190), cr + 0.014, 0.012), 0)
        put(sd_ellipsoid(x, y, z, (cx, cy, 0.195), (cr, cr, cr * 0.65)), cmid)

        # 10. Twin Body-Hugging Smooth Electric-Cyan Rim Light-Pipes recessed inside outer chrome curves (Mat 5)
        # Guaranteed to hug d2 = -0.058 (strictly inside the chrome rim!) with smooth rounded end caps
        d_rim = np.sqrt((d2 + 0.058) ** 2 + (z - 0.215) ** 2) - 0.011
        pipe_l = smax(d_rim, smax(np.abs(y - 0.16) - 0.28, x + 1.44, 0.03), 0.018)
        pipe_r = smax(d_rim, smax(np.abs(y - 0.02) - 0.24, 1.42 - x, 0.03), 0.018)
        put(np.minimum(pipe_l, pipe_r), 5)

        return best, mat

    print(f"[popup:whale] Raymarching Y2K Liquid Chrome Cyber Deck ({W}x{H})...")
    img = raymarch_layer(scene, lambda x, y, z: scene(x, y, z)[0], W, H, x_from_j, y_from_i, px_world,
                         z_start=1.6, max_dist=3.0, max_steps=84, chunk_rows=48, do_shadow=True)
    img = finish_y2k(img, bloom=0.30, bloom_thr=0.75, haze=0.025)
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGBA')
    if scale != 1.0:
        im = im.resize((W0, H0), Image.LANCZOS)
    final = add_studio_shadow(im, PAD, y_off=11, strength=0.64)
    final.save(os.path.join(out_dir, 'whale.png'), optimize=True)

    # Pixel-calibrated JSON metadata matching PopupView.java
    lx0, ly0 = to_px(LCD_CX - LCD_HX, LCD_CY + LCD_HY)
    lx1, ly1 = to_px(LCD_CX + LCD_HX, LCD_CY - LCD_HY)
    mx0, my0 = to_px(MET_CX - MET_HX, MET_CY + MET_HY)
    mx1, my1 = to_px(MET_CX + MET_HX, MET_CY - MET_HY)
    dx0, dy0 = to_px(DOCK_CX - DOCK_HX, DOCK_CY + DOCK_HY)
    dx1, dy1 = to_px(DOCK_CX + DOCK_HX, DOCK_CY - DOCK_HY)
    kcx, kcy = to_px(KNOB_CX, KNOB_CY)
    kr_px = round(KNOB_R * S0, 1)

    btns = [dict(id='knob', cx=kcx, cy=kcy, r=kr_px)]
    for bid, bx, by, br, _ in DOCK_BTNS + [CLOSE_BTN]:
        px_, py_ = to_px(bx, by)
        btns.append(dict(id=bid, cx=px_, cy=py_, r=round(br * S0, 1)))

    meta = dict(
        w=W0 + 2 * PAD, h=H0 + 2 * PAD, pad=PAD,
        lcd=dict(x=lx0, y=ly0, w=round(lx1 - lx0, 1), h=round(ly1 - ly0, 1), r=52),
        info=dict(x=round(lx0 + 185, 1), y=round(ly1 - 62, 1), w=round(lx1 - lx0 - 205, 1), h=56, r=14),
        meter=dict(x=mx0, y=my0, w=round(mx1 - mx0, 1), h=round(my1 - my0, 1), r=14),
        dock=dict(x=dx0, y=dy0, w=round(dx1 - dx0, 1), h=round(dy1 - dy0, 1), r=38),
        knob=dict(id='knob', cx=kcx, cy=kcy, r=kr_px),
        buttons=btns,
    )
    with open(os.path.join(out_dir, 'whale.json'), 'w') as f:
        json.dump(meta, f, indent=2)
    print(f"[popup:whale] Done in {time.time() - t0:.1f}s ({final.size})")


# ------------------------------------------------------------------ 2. Y2K CHROME SONIC ORB ("orb")
def build_orb(out_dir, scale=1.0):
    t0 = time.time()
    S0_PX = 740
    PAD = 36
    W = int(round(S0_PX * scale))
    H = W
    # World space: radius = 1.00 fits neatly inside [-1.05, +1.05]
    S0 = S0_PX / 2.10
    S = S0 * scale
    px_world = 1.0 / S

    x_from_j = lambda j: (j + 0.5 - W * 0.5) / S
    y_from_i = lambda i: (H * 0.5 - (i + 0.5)) / S
    to_px = lambda wx, wy: (round(wx * S0 + S0_PX * 0.5 + PAD, 1), round(S0_PX * 0.5 - wy * S0 + PAD, 1))

    LCD_R = 0.455
    DIAL_R0, DIAL_R1 = 0.785, 0.905
    BTNS = [
        ('prev',  -0.31, -0.585, 0.095, 3),
        ('play',   0.00, -0.640, 0.122, 3),
        ('next',   0.31, -0.585, 0.095, 3),
        ('tool',  -0.61, -0.145, 0.088, 3),
        ('skin',   0.61, -0.145, 0.088, 7),
        ('close',  0.00,  0.635, 0.078, 4),
    ]

    def scene(x, y, z):
        rho = np.sqrt(x * x + y * y)
        ang = np.degrees(np.arctan2(x, y))  # 0 = up, +/-180 = down

        # 1. Domed Mirror-Chrome Spherical Puck (authentic early-2000s Sony/WMP sonic orb!)
        dome = 0.15 * np.sqrt(np.maximum(0.0, 1.0 - np.clip(rho / 1.04, 0.0, 1.0) ** 2))
        d = extrude_2d(rho - 1.00, z - dome, 0.0, 0.19, 0.145)

        # Stepped brushed-chrome outer bevel ring
        step_ring = np.maximum(np.abs(rho - 0.725) - 0.012, 0.255 - z)
        d = smax(d, -step_ring, 0.008)

        # 2. Razor-Smooth Analytical 270-deg Recessed Orbital Dial Track (-132..+132 deg)
        # Exact smooth polar capsule distance for the 270-deg arc!
        mid_r = 0.5 * (DIAL_R0 + DIAL_R1)
        half_w = 0.5 * (DIAL_R1 - DIAL_R0)
        # Angle clamped to [-132, 132] degrees
        ang_rad = np.radians(np.clip(ang, -132.0, 132.0))
        arc_x = np.sin(ang_rad) * mid_r
        arc_y = np.cos(ang_rad) * mid_r
        d_arc2d = np.sqrt((x - arc_x) ** 2 + (y - arc_y) ** 2) - half_w
        track_cut = np.maximum(d_arc2d, (0.205 + dome * 0.55) - z)
        d = smax(d, -track_cut, 0.015)

        # 3. Central Circular Visor Pocket
        lcd_cut = np.maximum(rho - (LCD_R + 0.024), 0.23 - z)
        d = smax(d, -lcd_cut, 0.020)

        # 4. Carve recessed chrome sockets for all 6 jewel buttons
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

        # Recessed Brushed Aluminum Mid-Ring Faceplate (Mat 2) between visor & orbital groove
        mid_face = np.maximum(np.abs(rho - 0.60) - 0.115, np.abs(z - (0.18 + dome * 0.92)) - 0.015)
        mid_face = np.maximum(mid_face, d - 0.002)
        put(mid_face, 2)

        # Dark anodized orbital track channel floor (Mat 6)
        track_fl = np.maximum(d_arc2d + 0.006, np.abs(z - (0.202 + dome * 0.55)) - 0.012)
        put(track_fl, 6)

        # Glowing cyan hairline rim ringing the central visor bezel (Mat 5)
        visor_ring = sd_torus_z(x, y, z, (0.0, 0.0, 0.302), LCD_R + 0.014, 0.011)
        put(visor_ring, 5)

        # Central Convex Curved Smoked-Sapphire Glass Visor (Mat 1)
        glass_bulge = 0.036 * (1.0 - np.clip((rho / LCD_R) ** 2, 0.0, 1.0))
        lcd_glass = sd_cylinder_z(x, y, z - glass_bulge, (0.0, 0.0, 0.258), LCD_R - 0.004, 0.022)
        put(lcd_glass, 1)

        # Glossy 3D Y2K Aqua / Emerald / Ruby Gel Jewels inside Chrome Bezel Collars
        for bid, bx, by, br, mid in BTNS:
            b_dome = 0.15 * np.sqrt(max(0.0, 1.0 - (np.hypot(bx, by) / 1.04) ** 2))
            zb = 0.175 + b_dome
            put(sd_torus_z(x, y, z, (bx, by, zb + 0.012), br + 0.010, 0.010), 0)
            put(sd_ellipsoid(x, y, z, (bx, by, zb + 0.016), (br, br, br * 0.64)), mid)

        return best, mat

    print(f"[popup:orb] Raymarching Y2K Chrome Sonic Orb ({W}x{H})...")
    img = raymarch_layer(scene, lambda x, y, z: scene(x, y, z)[0], W, H, x_from_j, y_from_i, px_world,
                         z_start=1.6, max_dist=3.0, max_steps=84, chunk_rows=48, do_shadow=True)
    img = finish_y2k(img, bloom=0.30, bloom_thr=0.75, haze=0.025)
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGBA')
    if scale != 1.0:
        im = im.resize((S0_PX, S0_PX), Image.LANCZOS)
    final = add_studio_shadow(im, PAD, y_off=10, strength=0.64)
    final.save(os.path.join(out_dir, 'orb.png'), optimize=True)

    cx, cy = to_px(0.0, 0.0)
    lr = round(LCD_R * S0, 1)
    btns = []
    for bid, bx, by, br, _ in BTNS:
        px_, py_ = to_px(bx, by)
        btns.append(dict(id=bid, cx=px_, cy=py_, r=round(br * S0, 1)))

    meta = dict(
        w=S0_PX + 2 * PAD, h=S0_PX + 2 * PAD, pad=PAD,
        lcd=dict(x=round(cx - lr, 1), y=round(cy - lr, 1), w=round(lr * 2, 1), h=round(lr * 2, 1), r=lr),
        info=dict(x=round(cx - lr * 0.74, 1), y=round(cy + lr * 0.16, 1), w=round(lr * 1.48, 1), h=34, r=12),
        spec=dict(x=round(cx - lr * 0.56, 1), y=round(cy + lr * 0.44, 1), w=round(lr * 1.12, 1), h=44, r=12),
        dial=dict(cx=cx, cy=cy, r0=round(DIAL_R0 * S0, 1), r1=round(DIAL_R1 * S0, 1), a0=-132, a1=132),
        buttons=btns,
    )
    with open(os.path.join(out_dir, 'orb.json'), 'w') as f:
        json.dump(meta, f, indent=2)
    print(f"[popup:orb] Done in {time.time() - t0:.1f}s ({final.size})")


# ------------------------------------------------------------------ 3. Y2K LIQUID CHROME MINI-PILL ("pill")
def build_pill(out_dir, scale=1.0):
    t0 = time.time()
    W0, H0 = 1000, 290
    PAD = 30
    W = int(round(W0 * scale))
    H = int(round(H0 * scale))
    S0 = 280.0
    S = S0 * scale
    px_world = 1.0 / S

    x_from_j = lambda j: (j + 0.5 - W * 0.5) / S
    y_from_i = lambda i: (H * 0.5 - (i + 0.5)) / S
    to_px = lambda wx, wy: (round(wx * S0 + W0 * 0.5 + PAD, 1), round(H0 * 0.5 - wy * S0 + PAD, 1))

    # Recessed glass display on left 56%, glossy 3D gel jewels on right 44% with crisp separation
    LCD_CX, LCD_CY, LCD_HX, LCD_HY = -0.58, 0.0, 0.99, 0.30
    BTNS = [
        ('prev',  0.64, 0.0, 0.108, 3),
        ('play',  0.93, 0.0, 0.134, 3),
        ('next',  1.22, 0.0, 0.108, 3),
        ('skin',  1.47, 0.185, 0.072, 7),
        ('close', 1.47, -0.185, 0.072, 4),
    ]

    def scene(x, y, z):
        # 1. Sculpted Early-2000s Sony MiniDisc-inspired Chrome Pill Housing
        d2 = sd_2d_capsule(x, y, -1.25, 0.0, 1.25, 0.0, 0.48)
        dome = 0.055 * np.exp(-(x * x * 0.18 + y * y * 2.2))
        d = extrude_2d(d2, z - dome, 0.0, 0.21, 0.13)

        # Carve recessed inner chrome bezel groove around the capsule face
        bay_cut = sd_extruded_rrect(x, y, z, (0.0, 0.0, 0.24), 1.62, 0.34, 0.09, 0.32, 0.030)
        d = smax(d, -bay_cut, 0.018)

        # Carve deeper glass bay on left half
        lcd_cut = sd_extruded_rrect(x, y, z, (LCD_CX, LCD_CY, 0.21), LCD_HX + 0.012, LCD_HY + 0.012, 0.10, 0.28, 0.025)
        d = smax(d, -lcd_cut, 0.015)

        best = d
        mat = np.zeros_like(d, dtype=np.int8)

        def put(dd, m_id):
            nonlocal best, mat
            sel = dd < best
            best = np.where(sel, dd, best)
            mat = np.where(sel, m_id, mat)

        # Recessed brushed titanium backing plate on right control cluster (Mat 2)
        ctrl_plate = sd_extruded_rrect(x, y, z, (0.0, 0.0, 0.145), 1.59, 0.32, 0.024, 0.30, 0.015)
        ctrl_plate = smax(ctrl_plate, -lcd_cut, 0.012)
        put(ctrl_plate, 2)

        # Curved Obsidian-Sapphire Glass Capsule Screen (Mat 1)
        lcd_glass = sd_extruded_rrect(x, y, z, (LCD_CX, LCD_CY, 0.125), LCD_HX - 0.005, LCD_HY - 0.005, 0.024, 0.26, 0.014)
        put(lcd_glass, 1)

        # Twin Electric-Cyan Optical Light-Pipe Strips along top & bottom chrome shoulders (Mat 5)
        pipe_top = sd_capsule(x, y, z, (-1.15, 0.405, 0.205), (1.15, 0.405, 0.205), 0.015)
        pipe_bot = sd_capsule(x, y, z, (-1.15, -0.405, 0.205), (1.15, -0.405, 0.205), 0.015)
        put(np.minimum(pipe_top, pipe_bot), 5)

        # Glossy 3D Y2K Aqua, Emerald & Ruby Gel Buttons seated in Chrome Collars
        for bid, bx, by, br, mid in BTNS:
            put(sd_torus_z(x, y, z, (bx, by, 0.172), br + 0.012, 0.012), 0)
            put(sd_ellipsoid(x, y, z, (bx, by, 0.178), (br, br, br * 0.65)), mid)

        return best, mat

    print(f"[popup:pill] Raymarching Y2K Liquid Chrome Mini-Pill ({W}x{H})...")
    img = raymarch_layer(scene, lambda x, y, z: scene(x, y, z)[0], W, H, x_from_j, y_from_i, px_world,
                         z_start=1.6, max_dist=3.0, max_steps=84, chunk_rows=48, do_shadow=True)
    img = finish_y2k(img, bloom=0.30, bloom_thr=0.75, haze=0.025)
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGBA')
    if scale != 1.0:
        im = im.resize((W0, H0), Image.LANCZOS)
    final = add_studio_shadow(im, PAD, y_off=9, strength=0.60)
    final.save(os.path.join(out_dir, 'pill.png'), optimize=True)

    lx0, ly0 = to_px(LCD_CX - LCD_HX, LCD_CY + LCD_HY)
    lx1, ly1 = to_px(LCD_CX + LCD_HX, LCD_CY - LCD_HY)
    btns = []
    for bid, bx, by, br, _ in BTNS:
        px_, py_ = to_px(bx, by)
        btns.append(dict(id=bid, cx=px_, cy=py_, r=round(br * S0, 1)))

    meta = dict(
        w=W0 + 2 * PAD, h=H0 + 2 * PAD, pad=PAD,
        lcd=dict(x=lx0, y=ly0, w=round(lx1 - lx0, 1), h=round(ly1 - ly0, 1), r=round((ly1 - ly0) * 0.5, 1)),
        info=dict(x=round(lx0 + 140, 1), y=round(ly1 - 36, 1), w=round(lx1 - lx0 - 165, 1), h=36.0, r=12),
        buttons=btns,
    )
    with open(os.path.join(out_dir, 'pill.json'), 'w') as f:
        json.dump(meta, f, indent=2)
    print(f"[popup:pill] Done in {time.time() - t0:.1f}s ({final.size})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default='app/src/main/assets/skins')
    ap.add_argument('--scale', type=float, default=1.0)
    ap.add_argument('--only', default='all', choices=['all', 'whale', 'orb', 'pill'])
    A = ap.parse_args()
    os.makedirs(A.out, exist_ok=True)
    if A.only in ('all', 'whale'):
        build_whale(A.out, A.scale)
    if A.only in ('all', 'orb'):
        build_orb(A.out, A.scale)
    if A.only in ('all', 'pill'):
        build_pill(A.out, A.scale)

if __name__ == '__main__':
    main()
