# Architecture

```
earwax/
├─ .github/workflows/build.yml     CI: gradle assembleRelease -> uploads the APK
├─ app/src/main/
│  ├─ java/app/earwax/
│  │  ├─ MainActivity   permissions, starts the service, scans the library, hosts StageView
│  │  ├─ PlayerService  THE core: ExoPlayer + Dsp + MediaSession + notification + popup. UI never touches the player directly
│  │  ├─ Dsp            audio processor: EQ / shelves / channel mode / FFT spectrum (runs inside ExoPlayer's audio pipeline)
│  │  ├─ Library, Track MediaStore scan -> list of tracks
│  │  ├─ Hud            small snapshot (title, time, bars, current tool) used by every drawing routine
│  │  ├─ Skin           loads a skin (PNG + JSON) and draws LCD text, spectrum, glyphs and knob on top of it
│  │  ├─ StageView      main screen: head + plate artwork, screen content, list / EQ / FX panels, all touch handling
│  │  ├─ PopupView      floating shaped overlay window (WindowManager, TYPE_APPLICATION_OVERLAY)
│  │  └─ Notif          builds the RemoteViews notification from the "strip" skin
│  ├─ assets/skins/     head, plate, whale, orb, strip (*.png + *.json) and bg.jpg (blurred backdrop)  <- generated
│  └─ res/              notif_big.xml / notif_small.xml / icons  <- generated from strip.json
└─ tools/               artwork + layout generators and the preview composer (Python)
```

## Data flow
```
MainActivity --scan--> Library --> PlayerService.setLibrary() --> ExoPlayer queue (whole library)
                                         │
                 Dsp (EQ, spectrum) <────┤ audio samples
                 MediaSession  ─────────►│ lock screen / Bluetooth / headset
                 Notif  ◄────────────────┤ rebuilt on every player event (80 ms debounce)
StageView / PopupView ── read Hud ───────┤ ~20-30 fps redraw while playing
StageView / PopupView ── call methods ──►┘ toggle(), next(), setPitch(), setEq() ...
```

## Skins: one pipeline for every shape
* **Artwork** is pre-rendered 3-D, like the skinned players of the 2000s, so it costs nothing at runtime:
  * `tools/head.py` ray-marches a signed-distance-field head (chrome environment, ambient occlusion, ears attached on a sculpted root, four devil horns that double as buttons; the speaker cones are separate sprites in `cone.png`).
  * `tools/shapes.py` turns organic metaball silhouettes into puffy chrome bodies via a height-field + environment lookup,
    carves LCD recesses, and drops glossy gel-sphere buttons into sockets.
  * `tools/art.py` is the shared lighting/shading library.
* **Layout** is the JSON beside each PNG: button circles `{id,cx,cy,r}`, LCD rectangles `lcd/info/spec/screen/panel`.
  Java only reads this file, so new shapes need no new code, only a PNG and a JSON.
* **Notification buttons** are static XML (RemoteViews cannot move views at runtime), so `tools/gen_layout.py`
  writes `notif_big.xml` / `notif_small.xml` from `strip.json`. The artwork and the click targets cannot drift apart.
* `tools/preview.py` composes screenshots from those same files; use it to check a new skin without a phone.

## Audio notes
* ExoPlayer plays the whole library as one queue; shuffle/repeat are ExoPlayer's.
* `Dsp` is a Media3 `BaseAudioProcessor` for 16-bit PCM: RBJ biquads (10 peaking bands Q 1.41/2, bass shelf 120 Hz, treble shelf 8 kHz),
  pre-amp, mono L/R, then a Hann-windowed 1024-point FFT folded into 32 log bars. No RECORD_AUDIO permission needed.
* Pitch and speed are independent (`PlaybackParameters`, Sonic time-stretch).

## Adding a skin
1. Add a build call in `tools/shapes.py` (mask + holes + buttons) and run it.
2. Reference it in `PlayerService.cyclePopup()` (popups) or load it like `strip` in `Notif`.
Button ids understood by the popup: `prev play next tool mode app close knob`.

## Old-UI softness (blur + haze)
* **Baked into the art** (`art.finish()`): bloom on highlights + a touch of soft focus inside every silhouette; LCD and dark-glass
  recesses get a frosted gloss. Tune `bloom` / `haze` there.
* **Backdrop** `bg.jpg` (made by `tools/bg.py`): blurred steel-blue gradient with a cyan glow behind the head and light grain.
* **Runtime** (`StageView.bakeBackdrop()`): the three radial mists and the veil at the top and bottom are painted once into a half-size bitmap when the view is sized. They are completely still, so lights and background colours never change when a button is pressed or a window is switched.
  `Skin.text()` gives bright LCD text a soft glow (`setShadowLayer`), and the spectrum bars draw a faint halo.

## Button sizing
`head.json` stores the **touch radius** of each button (cones 54, horns 51 design units, about 21 / 18 dp on a 1080-px phone),
which is larger than the drawn dome so fingers hit it easily. Glyph and highlight sizes are scaled from it in `StageView.drawStage()`.


## 2.0 notes
* `StageView` draws one bust skin (`bust.png`, `ear_l.png`, `ear_r.png`, `bust.json`). Ears are separate bitmaps drawn with `drawBitmapMesh` so they can ripple.
* `PopupView` (whale / orb) uses `Rotary` for finger-circling control and `ScaleGestureDetector` for resizing; it runs in a hardware-accelerated overlay window.
* `Notif` uses `MediaStyleNotificationHelper.MediaStyle` (colorized chrome steel); `Art` frames the cover in chrome.
* `Radio` queries radio-browser.info; `PlayerService.playList(list, idx, key)` makes the shown list the queue.
* Head construction (tools/bust.py): face thirds hairline-brow-nose base-chin; ears in the middle third; mouth line 1/3 from nose base to chin; mouth width ~1.5x nose width; lower lip ~1.45x upper; jaw angle ~0.62 of cheekbone width. References: Loomis method and face-proportion guides (Proko, FineArtTutorials).
* `tools/head.py`, `gen_layout.py` and the old plate/strip skins were removed (replaced by `bust.py`, `brand.py`).

## Ears
`Dsp.fft()` keeps the raw dB level of a low / mid / high band; `Hud.beat()` turns it into a 0..1 pulse that rises on a hit above the band's own 1-second average (fast attack, 0.15 s release). `StageView.drawEars()` keeps the pods rigid (a 2 px shake on strong bass) and pumps each cone sprite with its band: bottom cone = low, middle = mid, top = high.
