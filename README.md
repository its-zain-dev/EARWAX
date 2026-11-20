# EARWAX 4.0

**Early-2000s Y2K 3D Liquid-Chrome Music Engine, Direct YouTube Music Player & Pro Studio DSP for Android (pure Java + pure Python 3D SDF pipeline).**

An anatomically sculpted, analytically raymarched 3D liquid-chrome cyber-bust is your entire interactive hi-fi hardware console: the curved obsidian-glass forehead visor is your live audio visualizer & player display, sculpted 3-driver headphone ear pods house 6 bass-pumping metallic cones radiating acoustic shockwaves, 4 glossy Aqua-chrome crown orbs levitate overhead, and the sculpted chrome chest houses a 5-mode obsidian-glass studio hub.

---

## Rebuilt from Scratch in 4.0

### 1. Pure-Python 3D Raymarched Y2K Liquid-Chrome Models (`tools/`)
* **Zero AI Cutouts, Zero C++ Dependencies**: Every single 3D hardware model is rendered from analytical Signed Distance Fields (SDFs) with tetrahedral normals, multi-scale ambient occlusion, soft shadows, and a custom Early-2000s liquid-mercury HDRI studio dome (`tools/y2k_shader.py`).
* **Sculpted Head, Face, Anatomical Neck & Chest Bust (`bust.png`, `tools/build_bust.py`)**: Seamless Sorayama-inspired mirror liquid-chrome cranium & face, curved obsidian-glass visor bezel with cyan optical rim, anatomical sternocleidomastoid neck & clavicles, sculpted deltoid/trapezius shoulders, deep chamfered chest console chassis, collarbone optical bar, and 11 precision-milled acoustic grille slots.
* **Hi-Fi 3-Driver Ear Speaker Pods (`ear_l.png`, `ear_r.png`, `cone.png`)**: Sculpted chrome acoustic monitor pods with temple hinge bridges, smoothly recessed outer cyan optical light-pipes, machined brushed-aluminum driver bezels, and 6 graduated metallic speaker cones pumping independently across 3 frequency bands + expanding acoustic shockwaves.
* **4 Levitating Aqua-Chrome Crown Orbs (`pearl.png`)**: Floating glossy Early-2000s candy-gel glass orbs overhead (Shuffle, Repeat + `1` badge, Smart Fade Sleep Timer, Floating Popup Launcher).
* **Real-Time 2.5D GyroParallax (`GyroParallax.java`)**: Multi-plane hardware tilt parallax shifting backdrop, bust + dynamic chrome sheen, ear pods, and crown orbs.
* **5 Live Y2K Neon-Chrome Themes (`Theme.java`)**: Aqua, Violet, Mint, Ruby, and Solar — dynamically re-tinting visor, visualizers, EQ curves, dialogs, popups, and notification.

### 2. Rebuilt Front-End Interfaces
* **Curved Chrome-Glass Forehead Visor (`VisorRenderer.java`)**: Album art (Local & YouTube Music), live source/codec/sample-rate pill, **1-tap Synced Lyrics (`LRC`) toggle**, **4 switchable live visualizers** (Mirrored Cyber Waveform, 32-Band Neon Spectrum + Peak Hold, Dual Harmonic Oscilloscope Ribbon, Stereo Precision VU Meters), liquid-neon seek bar, swipe left/right to skip, double-tap to play/pause, pulsing favorite heart.
* **5-Mode Obsidian-Glass Chest Console (`ConsoleRenderer.java`)**:
  * **Unified Music Hub (7 Tabs)**:
    * `YT`: Direct YouTube Music search with history & suggestion chips, 8 Mood/Chart discovery channels, Auto-Radio mixes, and **1-tap Offline Download (`↓`)**.
    * `SONGS`: Local MediaStore library with 5 sort modes (`A-Z`, `ARTIST`, `ALBUM`, `TIME`, `NEW`) & live filter.
    * `BROWSE`: Albums, Artists, and Folders drill-down.
    * `OFFLINE`: Saved YouTube Music tracks for zero-data, instant offline playback.
    * `FAVS`: Unified Favorites combining Local + YouTube Music tracks.
    * `LISTS`: Recently Played + Custom mixed Local & YouTube Music playlists.
    * `RADIO`: Live Internet Radio directory (8 genres + curated hi-fi stations + custom `.m3u`/`.pls`/`.xspf` URL resolver).
  * **10-Band Parametric EQ**: Live smooth Cubic Bézier response curve, 11 machined chrome faders (`PRE` + `32 Hz .. 16 kHz`) with **double-tap-to-zero**, EQ Bypass, Flat Reset, and 12 presets.
  * **Studio FX & Keep-Alive Cockpit**: Precision sliders (`PITCH`, `SPEED`, `BASS`, `TREBLE`, `3D WIDTH`, `VOLUME`) with **double-tap-to-zero**, Studio Reverb (4 rooms), Soft-Knee Limiter, 5 Channel Modes, Crossfade, Sleep Timer, **NO-STOP Ignore-Focus**, **YT Auto-Radio**, **Battery Keep-Alive Whitelist**, 5 Themes, and 3D Tilt toggle.
  * **Up-Next Queue**: Total queue duration, live upcoming Shuffle, instant jump, and remove.
  * **Synced Lyrics (`Lyrics.java`)**: Open-source LRCLIB time-synced lyrics with smooth auto-centering and tap-any-line-to-seek.
* **Y2K Liquid-Chrome Modal Dialogs (`ChromeDialog.java`)**: Brushed-chrome multi-stop bezels, glossy sapphire glass wells, Aqua candy-pill buttons, and instant search suggestion chips.

### 3. Rebuilt Back-End Services & Pro Audio DSP
* **Freeze-Proof Background Audio Engine (`PlayerService.java`)**: `START_STICKY` + `stopWithTask="false"`, 60s–180s freeze-proof ExoPlayer buffer + 30s back-buffer, `PARTIAL_WAKE_LOCK` & low-latency `WifiLock`, resilient AudioFocus manager with smooth ducking & optional `NO-STOP` mode, self-healing stall/error recovery at the exact millisecond, complete Queue + Position JSON persistence, guaranteed Next/Prev track switching (including Lock Screen & Bluetooth AVRCP via `ForwardingPlayer`), MediaStore `ContentObserver` sync, and smart fade Sleep Timer.
* **Direct YouTube Music Engine (`YtMusic.java`) + Offline Downloader (`Downloader.java`)**: Zero-API-key InnerTube search, discovery, Auto-Radio (`RDAMVM`), multi-tier direct audio resolution (`ANDROID_VR` → `IOS` → Piped → Invidious) with 3-hour stream caching, neighbor pre-fetching, and seamless offline M4A downloads.
* **Zero-GC Pro DSP (`Dsp.java`)**: Thread-safe 55ms cosine micro-crossfade on switches/seeks (zero pops), smooth ducking ramp, 10-band double-precision RBJ EQ, sub-bass & air shelves, Mid-Side 3D Stereo Width with `<115 Hz` mono bass anchor, anti-denormal Schroeder/Freeverb Studio Reverb, Soft-Knee Limiter, and zero-CPU background FFT gating.

### 4. Three Redesigned 3D Y2K Floating Popups (`PopupView.java`, `tools/build_popups.py`)
1. **Y2K Cyber Deck (`whale.png`)**: Asymmetric liquid-chrome body, recessed glass media visor with album art/spectrum/seek, concave brushed-aluminum jog wheel with glowing 270° ring (double-tap knob to reset tool), vertical level tube, and glossy 3D Aqua/Emerald/Ruby gel jewels.
2. **Chrome Sonic Orb (`orb.png`)**: Domed mirror-chrome sphere-puck, circular glass visor with radial progress halo, razor-smooth 270° recessed orbital touch dial, tap-to-cycle chin readout, and 3D gel jewels.
3. **Liquid Mini-Pill (`pill.png`)**: MiniDisc-inspired sculpted chrome lozenge with twin cyan optical light-pipes, mini live visualizer, bottom seek strip, and 3D gel transport controls.

Circle your finger around the knob/dial with haptic detent ticks, pinch to resize (saved per skin), drag with magnetic edge snapping, or double-tap the screen to morph between Deck → Orb → Pill.

---

## Build
* **GitHub Actions CI**: Every push to `main` (or tag `v*`) automatically runs `gradle --no-daemon assembleRelease` and uploads `earwax.apk`.
* **3D Asset Pipeline**: `python3 tools/build_all.py` regenerates all analytical 3D chrome skins, cones, orbs, popups, icons, and store graphics using only NumPy + Pillow.
