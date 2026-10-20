# EARWAX

Chrome 3-D music player for Android (pure Java). A sculpted chrome bust is the whole UI: the forehead is the screen, the ears are
speaker pods that vibrate with the bass, the chest is the library / equalizer / FX panel.

## What's new in 2.0
* **New head** built from classic face proportions (thirds, mouth at 1/3 nose-chin, small lips, defined jaw and cheekbones); head, neck and chest are one lit object.
* **Ears vibrate** (travelling ripple + glow, driven by the bass).
* **Popups**: whale (polished) and orb (redesigned, spectrum clipped inside the glass). Real rotary control: circle your finger around the knob / dial. Pinch to resize.
* **Notification**: real media notification (Spotify-style layout) in chrome colours, with a **popup** button and a close button.
* **Library sections**: ALL, ALBUMS, ARTISTS, FAVS, RECENT, RADIO + search. Long-press = favourite
* **Streaming**: internet radio search (radio-browser.info) and any stream URL (mp3 / aac / HLS / m3u / pls); saved stations.
* **First-launch tour** (English / Arabic), app icon, splash, wordmark, feature graphic (`branding/`).
* Safe-area padding everywhere; text can no longer hide in rounded corners.
* Lighter: FFT and animation only run while a screen is visible, EQ bypass when flat, bitmaps decoded at display size, no per-frame allocations in hot paths.

## Build
Push to GitHub: the workflow builds `earwax.apk` (artifact; attached to the release when you push a `v*` tag).
Locally: `gradle assembleRelease` (or Termux, see ARCHITECTURE.md).

## Regenerate the artwork (optional, needs numpy scipy pillow matplotlib)
```
cd tools
python3 bust.py --w 1080 --out ../app/src/main/assets/skins   # head + chest + ears (~45 s)
python3 shapes.py ../app/src/main/assets/skins                # whale + orb popups
python3 brand.py                                              # logo, icons, feature graphic
python3 bust.py --w 360 --out /tmp/t --side                   # quick profile preview
```
Streaming of commercial services (Spotify, YouTube Music...) needs their own SDK/accounts and is not included.
