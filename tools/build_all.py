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
    ap.add_argument('--bust-w', type=int, default=780, help='Bust render width in px (aspect 1080:2100)')
    ap.add_argument('--popup-scale', type=float, default=0.78, help='Popup raymarch scale before LANCZOS')
    A = ap.parse_args()

    bust_w = 420 if A.fast else A.bust_w
    pop_s = 0.45 if A.fast else A.popup_scale

    t0 = time.time()
    run([sys.executable, os.path.join(HERE, 'build_bust.py'), '--w', str(bust_w), '--out', SKINS])
    run([sys.executable, os.path.join(HERE, 'build_popups.py'), '--scale', str(pop_s), '--out', SKINS])
    run([sys.executable, os.path.join(HERE, 'build_brand.py')])
    print(f"[build_all] Complete EARWAX 4.0 3D asset suite built in {time.time() - t0:.1f}s!")


if __name__ == '__main__':
    main()
