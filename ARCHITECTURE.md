# EARWAX 4.0 Architecture

```text
EARWAX/
├─ .github/workflows/build.yml        CI: gradle --no-daemon assembleRelease -> earwax.apk
├─ app/src/main/
│  ├─ java/app/earwax/
│  │  ├─ MainActivity.java            Immersive host, permissions, Battery Whitelist, ACTION_VIEW, low-RAM trim
│  │  ├─ PlayerService.java           Freeze-proof foreground audio engine: ExoPlayer + ResolvingDataSource + Dsp + ForwardingPlayer/MediaSession + Queue + Playlists + Offline + Lyrics + Popups
│  │  ├─ Dsp.java                     Zero-GC Media3 BaseAudioProcessor: click-free switch ramp, smooth ducking, 10-band RBJ EQ, Shelves, 3D Width, Schroeder/Freeverb Reverb, Limiter, FFT/Wave/VU
│  │  ├─ YtMusic.java                 Zero-key YouTube Music InnerTube client (search, moods, Auto-Radio, multi-tier direct audio resolver + prefetch cache)
│  │  ├─ Downloader.java              Offline YouTube Music M4A audio downloader (app-scoped Music/EARWAX_Offline)
│  │  ├─ Lyrics.java                  Open-source LRCLIB time-synced LRC + plain lyrics client with binary-search timestamp sync
│  │  ├─ Radio.java                   Multi-mirror radio-browser.info client + 8 genres + curated hi-fi stations + playlist link resolver
│  │  ├─ Library.java                 MediaStore audio scanner (folders, codec, year, size) + 5 sort modes + external URI resolver
│  │  ├─ Track.java                   Immutable JSON-serializable unified Track (Local / YT Music / Offline / Radio / External)
│  │  ├─ Playlist.java                Mixed Local + YT Music + Offline user playlist model with JSON serialization
│  │  ├─ Prefs.java                   Persistent JSON queue/position snapshot, DSP profile, themes, playlists, favs, recents, offline index, search history, popup geometry
│  │  ├─ Hud.java                     Zero-GC per-frame telemetry snapshot + 3-band beat envelope + kick shockwave trigger
│  │  ├─ Art.java                     RAM-disciplined LRU + disk-cached album art loader (Local + Remote YT Music) + dominant accent + chrome notification frame
│  │  ├─ Notif.java                   Dynamic theme-colorized MediaStyle notification with 5 transport/popup actions
│  │  ├─ Theme.java                   5 live Y2K Cyber-Chrome & Glossy Aqua palettes + gradient/glow utilities
│  │  ├─ GyroParallax.java            Low-power Game Rotation Vector / Accel sensor fusion for 2.5D depth parallax
│  │  ├─ Rotary.java                  Precision circular finger-tracking dial controller
│  │  ├─ Skin.java                    RAM-capped 3D PNG asset loader + calibrated JSON hitboxes + crisp vector icon/marquee engine
│  │  ├─ VisorRenderer.java           Forehead curved chrome-glass visor: album art, 4 visualizers, LRC button, neon seek bar, favorite heart
│  │  ├─ ConsoleRenderer.java         5-mode chest console: 7-Tab Hub (YT/Songs/Browse/Offline/Favs/Lists/Radio), 10-Band EQ, Studio FX + Keep-Alive, Queue, Synced Lyrics
│  │  ├─ StageView.java               Main 2.5D interactive stage: parallax bust + specular sheen, pumping ear pods, shockwaves, floating crown orbs, intro & tour
│  │  ├─ PopupView.java               3 sculpted 3D Y2K floating overlay popups (Cyber Deck, Sonic Orb, Mini-Pill)
│  │  └─ ChromeDialog.java            Custom Y2K brushed-chrome & glossy Aqua modal dialogs with search history chips
│  ├─ assets/skins/                   bust, ear_l, ear_r, cone, pearl, whale, orb, pill (*.png + *.json), bg.jpg, logo.png, mark.png
│  └─ res/                            Adaptive & legacy launcher icons, obsidian splash, vector notification icons, bilingual EN/AR strings
├─ branding/                          512px store icon & 1024x500 feature graphic
└─ tools/
   ├─ y2k_shader.py                   Pure-Python analytical SDF math, Early-2000s liquid-chrome HDRI dome, 8 Y2K PBR materials & chunked raymarcher
   ├─ build_bust.py                   3D sculpted liquid-chrome head, neck, chest, 3-driver ear pods, speaker cones, Aqua crown orbs & bust.json
   ├─ build_popups.py                 3D raymarched Cyber Deck (whale), Sonic Orb (orb), Mini-Pill (pill) & calibrated JSON specs
   ├─ build_brand.py                  Studio backdrop, 3D beveled chrome wordmark, feathered head mark, adaptive/legacy icons & store graphics
   └─ build_all.py                    End-to-end 3D asset suite build orchestrator
```

## Runtime Data Flow
```text
Local MediaStore (+ ContentObserver) / YouTube Music InnerTube / Offline M4A / Radio / External URI
                │
                ▼
         PlayerService  ◄───────► Prefs (JSON queue + ms resume snapshot, DSP, playlists, favs)
          ├─ ExoPlayer + ResolvingDataSource (earwax-yt:// -> Offline or Multi-Tier InnerTube Stream)
          │    └─► Dsp (Smooth Switch Ramp, Ducking, 10-Band EQ, Shelves, 3D Width, Reverb, Limiter, FFT/Wave/VU)
          ├─ ForwardingPlayer ──► MediaSession (Lock screen, Notification, Bluetooth AVRCP)
          ├─ Art (LRU + Disk Cache + Dominant Accent) ──► Notif (Colorized MediaStyle)
          ├─ Lyrics (LRCLIB) & Downloader (Offline M4A)
          └─ Reactive Listener Bus ──┬──► StageView (VisorRenderer + ConsoleRenderer + GyroParallax)
                                     └──► PopupView (Cyber Deck / Chrome Sonic Orb / Liquid Mini-Pill)
```
