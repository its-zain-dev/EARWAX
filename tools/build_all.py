"""EARWAX 4.0 — Pure Python 3D Early-2000s Asset Build Orchestrator.

Rebuilds every 3D liquid-chrome bust, ear pod, speaker cone, Aqua crown orb,
floating Y2K popup hardware skin (whale, orb, pill), studio backdrop, 3D chrome
wordmark, head mark, launcher icons, and store branding from analytical Python SDFs.

Usage:
  python3 tools/build_all.py             # Full production render
  python3 tools/build_all.py --fast      # Fast preview-resolution render
"""

import argparse
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))
SKINS = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'skins')


def run(cmd):
    print(f"[build_all] Running: {' '.join(cmd)}", flush=True)
    subprocess.check_call(cmd, cwd=ROOT)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--fast', action='store_true', help='Render at fast preview resolution')
    ap.add_argument('--bust-w', type=int, default=1080, help='Bust output width in px (aspect 1080:2100)')
    ap.add_argument('--bust-ss', type=float, default=1.0, help='Bust supersample factor')
    ap.add_argument('--popup-scale', type=float, default=1.45, help='Popup output pixel density multiplier')
    ap.add_argument('--popup-ss', type=float, default=1.0, help='Popup supersample factor')
    A = ap.parse_args()

    bust_w = 540 if A.fast else A.bust_w
    bust_ss = 1.0 if A.fast else A.bust_ss
    pop_s = 0.75 if A.fast else A.popup_scale
    pop_ss = 1.0 if A.fast else A.popup_ss

    t0 = time.time()
    run([sys.executable, os.path.join(HERE, 'build_bust.py'), '--w', str(bust_w), '--ss', str(bust_ss), '--out', SKINS])
    run([sys.executable, os.path.join(HERE, 'build_popups.py'), '--scale', str(pop_s), '--ss', str(pop_ss), '--out', SKINS])
    run([sys.executable, os.path.join(HERE, 'build_brand.py')])
    print(f"[build_all] Complete EARWAX 4.5 Hi-Res 3D asset suite built in {time.time() - t0:.1f}s!")


if __name__ == '__main__':
    main()
