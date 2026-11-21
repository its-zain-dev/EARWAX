"""EARWAX 4.8 — Ultra-HD C++17 + Python 3D Raymarched Y2K Floating Popup & Twin 3D Wing Drawer Skins.

Upgraded to a native C++17 multi-threaded analytical SDF raymarcher (`tools/popup_render.cpp`)
rendering every floating hardware popup & twin 3D sliding wing drawer at 2.0x Ultra-HD resolution
with 2.0x analytical supersampling (SSAA -> LANCZOS downsample) for razor-sharp buttons & chrome bevels:
  1. whale.png   + whale.json   — Y2K Liquid Chrome Cyber Deck (2304x1364)
  2. orb.png     + orb.json     — Y2K Chrome Sonic Orb (1624x1624)
  3. pill.png    + pill.json    — Y2K Liquid Chrome Mini-Pill (2120x800)
  4. drawer_l.png / drawer_r.png + drawer.json — Twin 3D Sculpted Liquid-Chrome Wing Drawers (1184x1464)
"""

import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
import numpy as np
from PIL import Image
from y2k_shader import finish_y2k, downsample_rgba, add_studio_shadow

HERE = os.path.dirname(os.path.abspath(__file__))


def compile_cpp_renderer():
    src = os.path.join(HERE, 'popup_render.cpp')
    exe = os.path.join(HERE, 'popup_render')
    compiler = shutil.which('g++') or shutil.which('clang++')
    if not compiler:
        raise RuntimeError("Neither g++ nor clang++ found in PATH to compile tools/popup_render.cpp")
    need_build = (not os.path.exists(exe)) or (os.path.getmtime(src) > os.path.getmtime(exe))
    if need_build:
        cmd = [compiler, '-O3', '-ffast-math', '-std=c++17', '-pthread', src, '-o', exe]
        print(f"[popup] Compiling C++17 3D raymarcher: {' '.join(cmd)}", flush=True)
        subprocess.check_call(cmd)
    return exe


def render_popup_cpp(mode, W, H, S, threads=2, ss=2):
    exe = compile_cpp_renderer()
    with tempfile.NamedTemporaryFile(suffix='.rgba', delete=False) as tf:
        raw_path = tf.name
    try:
        subprocess.check_call([exe, mode, str(W), str(H), f"{S:.6f}", raw_path, str(threads), str(ss)])
        data = np.fromfile(raw_path, dtype=np.uint8).reshape(H, W, 4)
        return data.astype(np.float32) / 255.0
    finally:
        if os.path.exists(raw_path):
            os.remove(raw_path)


def _save_hd_popup(img, pad_px, out_path, y_off=10, strength=0.62):
    """Applies crisp Y2K bloom (zero haze) + studio drop shadow and saves PNG."""
    img = finish_y2k(img, bloom=0.14, bloom_thr=0.84, haze=0.0)
    im = Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGBA')
    del img
    final = add_studio_shadow(im, pad_px, y_off=y_off, strength=strength)
    final.save(out_path, optimize=True)
    return final


# ------------------------------------------------------------------ 1. Y2K LIQUID CHROME CYBER DECK ("whale")
def build_whale(out_dir, scale=1.5, ss=2, threads=2):
    t0 = time.time()
    W0, H0, PAD0 = 1080, 610, 36
    OUT_W = int(round(W0 * scale))
    OUT_H = int(round(H0 * scale))
    PAD_OUT = int(round(PAD0 * scale))
    S0 = 300.0
    S = S0 * scale
    to_px = lambda wx, wy: (round(wx * S0 + W0 * 0.5 + PAD0, 1), round(H0 * 0.5 - wy * S0 + PAD0, 1))

    LCD_CX, LCD_CY, LCD_HX, LCD_HY = -0.58, 0.08, 0.90, 0.36
    KNOB_CX, KNOB_CY, KNOB_R = 0.78, 0.08, 0.255
    MET_CX, MET_CY, MET_HX, MET_HY = 1.30, 0.08, 0.055, 0.34
    DOCK_CX, DOCK_CY, DOCK_HX, DOCK_HY = 0.00, -0.58, 1.32, 0.155

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

    print(f"[popup:whale] Raymarching C++17 Cyber Deck ({OUT_W}x{OUT_H}, {ss}x{ss} in-loop SSAA)...", flush=True)
    img = render_popup_cpp('whale', OUT_W, OUT_H, S, threads=threads, ss=ss)
    final = _save_hd_popup(img, PAD_OUT, os.path.join(out_dir, 'whale.png'),
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
def build_orb(out_dir, scale=1.5, ss=2, threads=2):
    t0 = time.time()
    S0_PX, PAD0 = 740, 36
    OUT_S = int(round(S0_PX * scale))
    PAD_OUT = int(round(PAD0 * scale))
    S0 = S0_PX / 2.10
    S = S0 * scale
    to_px = lambda wx, wy: (round(wx * S0 + S0_PX * 0.5 + PAD0, 1), round(S0_PX * 0.5 - wy * S0 + PAD0, 1))

    LCD_R = 0.455
    DIAL_R0, DIAL_R1 = 0.785, 0.905
    BTNS = [
        ('prev',    -0.31, -0.585, 0.095, 3),
        ('play',     0.00, -0.640, 0.122, 3),
        ('next',     0.31, -0.585, 0.095, 3),
        ('tool',    -0.61, -0.145, 0.088, 3),
        ('skin',     0.61, -0.145, 0.088, 7),
        ('drawers',  0.00,  0.635, 0.088, 3),
        ('close',    0.49,  0.430, 0.074, 4),
    ]

    print(f"[popup:orb] Raymarching C++17 Sonic Orb ({OUT_S}x{OUT_S}, {ss}x{ss} in-loop SSAA)...", flush=True)
    img = render_popup_cpp('orb', OUT_S, OUT_S, S, threads=threads, ss=ss)
    final = _save_hd_popup(img, PAD_OUT, os.path.join(out_dir, 'orb.png'),
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
def build_pill(out_dir, scale=1.5, ss=2, threads=2):
    t0 = time.time()
    W0, H0, PAD0 = 1000, 340, 30
    OUT_W = int(round(W0 * scale))
    OUT_H = int(round(H0 * scale))
    PAD_OUT = int(round(PAD0 * scale))
    S0 = 280.0
    S = S0 * scale
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

    print(f"[popup:pill] Raymarching C++17 Mini-Pill ({OUT_W}x{OUT_H}, {ss}x{ss} in-loop SSAA)...", flush=True)
    img = render_popup_cpp('pill', OUT_W, OUT_H, S, threads=threads, ss=ss)
    final = _save_hd_popup(img, PAD_OUT, os.path.join(out_dir, 'pill.png'),
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
def build_drawers(out_dir, scale=1.5, ss=2, threads=2):
    t0 = time.time()
    W0, H0, PAD0 = 540, 680, 26
    OUT_W = int(round(W0 * scale))
    OUT_H = int(round(H0 * scale))
    PAD_OUT = int(round(PAD0 * scale))
    S0 = 260.0
    S = S0 * scale
    to_px = lambda wx, wy: (round(wx * S0 + W0 * 0.5 + PAD0, 1), round(H0 * 0.5 - wy * S0 + PAD0, 1))
    SCR_HX, SCR_HY = 0.78, 1.05

    for name in ('drawer_l', 'drawer_r'):
        print(f"[popup:{name}] Raymarching C++17 3D Wing Drawer ({OUT_W}x{OUT_H}, {ss}x{ss} in-loop SSAA)...", flush=True)
        img = render_popup_cpp(name, OUT_W, OUT_H, S, threads=threads, ss=ss)
        final = _save_hd_popup(img, PAD_OUT, os.path.join(out_dir, f'{name}.png'),
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
    ap.add_argument('--scale', type=float, default=1.5)
    ap.add_argument('--ss', type=int, default=2)
    ap.add_argument('--threads', type=int, default=2)
    ap.add_argument('--only', default='all', choices=['all', 'whale', 'orb', 'pill', 'drawers'])
    A = ap.parse_args()
    os.makedirs(A.out, exist_ok=True)
    if A.only in ('all', 'whale'):
        build_whale(A.out, A.scale, A.ss, A.threads)
    if A.only in ('all', 'orb'):
        build_orb(A.out, A.scale, A.ss, A.threads)
    if A.only in ('all', 'pill'):
        build_pill(A.out, A.scale, A.ss, A.threads)
    if A.only in ('all', 'drawers'):
        build_drawers(A.out, A.scale, A.ss, A.threads)


if __name__ == '__main__':
    main()
