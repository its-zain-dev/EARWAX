package app.earwax;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * EARWAX 4.0 Early-2000s Curved Chrome-Glass Forehead Visor Renderer.
 * Features:
 *  - Authentic Y2K curved top-half glass specular reflection sweep + bass-reactive sapphire halo
 *  - Top bar: [?] Tour • [STATUS • SOURCE BADGE] • [LYRICS] quick toggle • [HEART] Favorite
 *  - Rounded album artwork (Local & YouTube Music covers!) — tap to cycle 4 live visualizers
 *  - Crisp track title marquee, artist, and active DSP/source telemetry
 *  - 4 Switchable Visualizers: Mirrored Cyber Waveform, 32-Band Neon Spectrum, Harmonic Ribbon, Stereo VU
 *  - Interactive Liquid-Neon Seek Bar with glowing Aqua comet thumb & timestamps
 */
final class VisorRenderer {
    static final int VIZ_COUNT = 4;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final Path artClip = new Path();
    private final Path waveTop = new Path();
    private final Path waveBot = new Path();
    private final RectF rArt = new RectF(), rSeek = new RectF(), rTmp = new RectF();
    private final RectF rTour = new RectF(), rLyrics = new RectF(), rHeart = new RectF(), rViz = new RectF();

    private Shader glassBg, glassSheen, seekGrad, lrcOnGrad;
    private RectF cachedRect;
    private int cachedTheme = -1;

    private void ensureShaders(RectF r) {
        if (cachedRect != null && cachedRect.equals(r) && cachedTheme == Theme.index()) return;
        cachedRect = new RectF(r);
        cachedTheme = Theme.index();
        glassBg = new LinearGradient(r.left, r.top, r.left, r.bottom,
                new int[]{0xff0d1e38, 0xff060e1d, 0xff030710},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
        glassSheen = new LinearGradient(r.left, r.top, r.left + r.width() * 0.25f, r.top + r.height() * 0.48f,
                new int[]{0x2ee4f6ff, 0x0ca8dcff, 0x00ffffff},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        float padX = r.width() * 0.052f;
        seekGrad = Theme.accentGrad(r.left + padX, r.bottom - 13f, r.right - padX, r.bottom - 13f);
        float topY = r.top + r.height() * 0.145f;
        lrcOnGrad = Theme.aquaPillGrad(topY - 14.5f, topY + 5.5f, Theme.primary());
    }

    void draw(Canvas c, RectF r, Hud x, Bitmap albumArt, int vizMode, boolean lyricsActive, float tiltX) {
        if (r == null) return;
        ensureShaders(r);

        float rad = Math.min(r.width(), r.height()) * 0.22f;
        c.save();
        clip.reset();
        clip.addRoundRect(r.left + 2, r.top + 2, r.right - 2, r.bottom - 2, rad, rad, Path.Direction.CW);
        c.clipPath(clip);

        // 1. Deep sapphire-obsidian glass base (zero per-frame gradient allocations!)
        fill.setShader(glassBg);
        fill.setStyle(Paint.Style.FILL);
        fill.clearShadowLayer();
        c.drawRect(r, fill);
        fill.setShader(null);

        float padX = r.width() * 0.052f;
        float h = r.height();
        float l = r.left + padX;
        float rr = r.right - padX;
        float w = rr - l;

        // 3. Top bar: [?]   [STATUS • SOURCE BADGE]   [LRC]   [♥]
        float topY = r.top + h * 0.145f;
        rTour.set(l - 10, r.top, l + 34, r.top + h * 0.30f);
        rHeart.set(rr - 36, r.top, rr + 10, r.top + h * 0.30f);
        rLyrics.set(rr - 94, r.top, rr - 38, r.top + h * 0.30f);

        // [?] circle
        fill.setColor(0x3090c8f0);
        c.drawCircle(l + 11, topY - 4, 11.5f, fill);
        Skin.text(c, "?", l + 11, topY + 1.5f, 15.5f, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);

        // Center status + source/codec pill
        String st = x.buffering ? "LOADING" : (x.live ? "LIVE" : (x.playing ? "PLAYING" : "PAUSED"));
        String pill = x.badge.isEmpty() ? st : (st + " • " + x.badge);
        float centerMid = (l + 32f + rr - 92f) * 0.5f;
        float maxPillW = Math.max(80f, (rr - 96f) - (l + 32f));
        float pillW = Math.min(maxPillW, Skin.width(pill, 12.5f, Theme.TF_BOLD) + 20f);
        rTmp.set(centerMid - pillW / 2f, topY - 14.5f, centerMid + pillW / 2f, topY + 5.5f);
        fill.setColor(Theme.alpha(Theme.primary(), x.playing ? 38 : 20));
        c.drawRoundRect(rTmp, 10f, 10f, fill);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.3f);
        stroke.setColor(Theme.alpha(Theme.primary(), x.playing ? 150 : 70));
        c.drawRoundRect(rTmp, 10f, 10f, stroke);
        Skin.text(c, Skin.fit(pill, 12.5f, pillW - 10f, Theme.TF_BOLD),
                centerMid, topY - 0.5f, 12.5f,
                x.playing ? Theme.glow() : Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, x.playing);

        // [LRC] Synced Lyrics quick pill
        rTmp.set(rr - 88f, topY - 14.5f, rr - 36f, topY + 5.5f);
        if (lyricsActive) {
            fill.setShader(lrcOnGrad);
        } else {
            fill.setColor(0x2c8cc8f0);
        }
        c.drawRoundRect(rTmp, 10f, 10f, fill);
        fill.setShader(null);
        stroke.setStrokeWidth(1.3f);
        stroke.setColor(lyricsActive ? Theme.glow() : Theme.alpha(Theme.CHROME_HI, 95));
        c.drawRoundRect(rTmp, 10f, 10f, stroke);
        Skin.text(c, "LRC", rTmp.centerX(), topY - 0.5f, 12f,
                lyricsActive ? 0xff04101d : Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, false);

        // Pulsing favorite heart
        float hk = 8.8f * (x.fav && x.playing ? (1f + 0.12f * x.bass) : 1f);
        Skin.heart(c, rr - 13f, topY - 4f, hk, x.fav, x.fav ? Theme.HEART_ON : Theme.TEXT_SUB);

        // 4. Middle row: Rounded Glossy Album Art + Title / Artist / Active DSP tag
        float midTop = r.top + h * 0.245f;
        float artSize = Math.min(h * 0.34f, 64f);
        rArt.set(l, midTop, l + artSize, midTop + artSize);

        c.save();
        artClip.reset();
        artClip.addRoundRect(rArt, 12f, 12f, Path.Direction.CW);
        c.clipPath(artClip);
        fill.setColor(0xff0b1729);
        c.drawRect(rArt, fill);
        if (albumArt != null && !albumArt.isRecycled()) {
            float k = Math.max(rArt.width() / albumArt.getWidth(), rArt.height() / albumArt.getHeight());
            float aw = albumArt.getWidth() * k, ah = albumArt.getHeight() * k;
            rTmp.set(rArt.centerX() - aw / 2f, rArt.centerY() - ah / 2f,
                    rArt.centerX() + aw / 2f, rArt.centerY() + ah / 2f);
            c.drawBitmap(albumArt, null, rTmp, bmpPaint);
        }
        // Subtle crisp highlight strip over album tile
        fill.setColor(0x18ffffff);
        c.drawRect(rArt.left, rArt.top, rArt.right, rArt.centerY(), fill);
        c.restore();

        stroke.setStrokeWidth(1.8f);
        stroke.setColor(Theme.alpha(x.accentColor, 195));
        c.drawRoundRect(rArt, 12f, 12f, stroke);

        float tx = rArt.right + 12f;
        float tw = Math.max(40f, rr - tx);
        c.save();
        c.clipRect(tx, midTop - 2, rr, midTop + artSize + 2);
        Skin.marquee(c, x.title.isEmpty() ? "EARWAX 4.0" : x.title,
                tx, midTop + artSize * 0.37f, 21.5f, Theme.TEXT_MAIN, tw, Theme.TF_BOLD, true);
        String sub = x.artist.isEmpty() ? (x.album.isEmpty() ? "Y2K 3D CHROME ENGINE" : x.album) : x.artist;
        Skin.text(c, Skin.fit(sub, 15.5f, tw, Theme.TF_MEDIUM),
                tx, midTop + artSize * 0.70f, 15.5f, Theme.TEXT_SUB, Paint.Align.LEFT, Theme.TF_MEDIUM, false);
        String modeTag = x.reverbMode > 0 ? ("FX: " + Dsp.REVERB_NAMES[x.reverbMode])
                : (x.eqOn ? "EQ ACTIVE" : "DIRECT PCM");
        Skin.text(c, modeTag, tx, midTop + artSize * 0.97f, 12f,
                Theme.primary(), Paint.Align.LEFT, Theme.TF_BOLD, false);
        c.restore();

        // 5. Audio Visualizer Bay (tappable to switch between 4 styles)
        float vizTop = rArt.bottom + 5f;
        float seekY = r.bottom - 13f;
        float vizBot = seekY - 18f;
        rViz.set(l, vizTop, rr, Math.max(vizTop + 18f, vizBot));
        drawVisualizer(c, rViz, x, vizMode);

        // 6. Time labels + Interactive Liquid-Neon Seek Bar
        float timeY = seekY - 8.5f;
        Skin.text(c, Skin.mmss(x.pos), l, timeY, 14f, Theme.glow(), Paint.Align.LEFT, Theme.TF_MONO, false);
        Skin.text(c, x.live ? "LIVE" : Skin.mmss(x.dur), rr, timeY, 14f, Theme.TEXT_SUB, Paint.Align.RIGHT, Theme.TF_MONO, false);

        rSeek.set(l - 12, seekY - 18, rr + 12, r.bottom + 4);
        float frac = (x.dur > 0 && !x.live) ? Math.max(0f, Math.min(1f, x.pos / (float) x.dur)) : 0f;
        float fx = l + w * frac;

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(6f);
        stroke.setColor(0xff112540);
        c.drawLine(l, seekY, rr, seekY, stroke);

        if (x.buffering) {
            // Smooth glowing Y2K scanner pulse while resolving YouTube Music stream
            float pulse = (float) ((System.currentTimeMillis() % 1100L) / 1100.0);
            float sx0 = l + w * Math.max(0f, pulse - 0.18f);
            float sx1 = l + w * Math.min(1f, pulse + 0.18f);
            stroke.setColor(Theme.primary());
            Theme.applyGlow(stroke, 10f, Theme.primary(), 210);
            c.drawLine(sx0, seekY, sx1, seekY, stroke);
            stroke.clearShadowLayer();
        } else if (frac > 0.002f) {
            stroke.setShader(seekGrad);
            c.drawLine(l, seekY, fx, seekY, stroke);
            stroke.setShader(null);
        }

        fill.setColor(Theme.alpha(Theme.primary(), 105));
        c.drawCircle(fx, seekY, 10f, fill);
        fill.setColor(0xffffffff);
        c.drawCircle(fx, seekY, 5.2f, fill);

        // 7. Signature Y2K Curved Glass Reflection Arc across upper visor
        fill.setShader(glassSheen);
        c.drawRect(r.left, r.top, r.right, r.top + r.height() * 0.45f, fill);
        fill.setShader(null);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.0f);
        stroke.setColor(Theme.alpha(Theme.primary(), 105));
        c.drawRoundRect(r.left + 3, r.top + 3, r.right - 3, r.bottom - 3, rad, rad, stroke);
        c.restore();
    }

    private void drawVisualizer(Canvas c, RectF r, Hud x, int mode) {
        float w = r.width(), h = r.height(), cy = r.centerY();
        if (h < 10f || w < 20f) return;

        switch (mode % VIZ_COUNT) {
            case 0: { // Signature Mirrored Cyber Waveform
                int n = 32;
                float bw = w / n;
                fill.setStyle(Paint.Style.FILL);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(1.4f);
                stroke.setColor(Theme.alpha(Theme.primary(), 95));
                c.drawLine(r.left, cy, r.right, cy, stroke);

                for (int i = 0; i < n; i++) {
                    int bi = Math.abs(i - n / 2) * 2;
                    if (bi >= 32) bi = 31;
                    float amp = x.playing ? Math.max(0.06f, x.bars[bi] * 0.85f + Math.abs(x.wave[(i * 2) % Dsp.WAVE_N]) * 0.35f) : 0.05f;
                    float hh = Math.min(h * 0.48f, amp * h * 0.48f);
                    float distCenter = Math.abs(i - (n - 1) / 2f) / (n / 2f);
                    int col = Theme.lerp(Theme.primary(), Theme.secondary(), distCenter);
                    float x0 = r.left + i * bw + 1.8f;
                    float x1 = r.left + (i + 1) * bw - 1.8f;
                    fill.setColor(Theme.alpha(col, 65));
                    c.drawRoundRect(x0 - 1f, cy - hh - 2f, x1 + 1f, cy + hh + 2f, 3f, 3f, fill);
                    fill.setColor(Theme.lerp(col, 0xffffffff, amp * 0.45f));
                    c.drawRoundRect(x0, cy - hh, x1, cy + hh, 2.5f, 2.5f, fill);
                }
                break;
            }
            case 1: { // 32-Band Neon Spectrum + Floating Peak Caps
                int n = 32;
                float bw = w / n;
                fill.setStyle(Paint.Style.FILL);
                for (int i = 0; i < n; i++) {
                    float t = i / (float) (n - 1);
                    int col = Theme.lerp(Theme.primary(), Theme.secondary(), t);
                    float v = x.playing ? x.bars[i] : 0.03f;
                    float pk = x.playing ? x.peaks[i] : 0.03f;
                    float bh = Math.max(2.5f, v * h);
                    float x0 = r.left + i * bw + 1.5f;
                    float x1 = r.left + (i + 1) * bw - 1.5f;
                    fill.setColor(Theme.alpha(col, 42));
                    c.drawRect(x0, r.top, x1, r.bottom, fill);
                    fill.setColor(col);
                    c.drawRoundRect(x0, r.bottom - bh, x1, r.bottom, 2f, 2f, fill);
                    float py = r.bottom - Math.max(3f, pk * h);
                    fill.setColor(0xeeffffff);
                    c.drawRect(x0, py - 2.5f, x1, py, fill);
                }
                break;
            }
            case 2: { // Dual Harmonic Glowing Oscilloscope Ribbon
                waveTop.reset();
                waveBot.reset();
                float step = w / (Dsp.WAVE_N - 1);
                for (int i = 0; i < Dsp.WAVE_N; i++) {
                    float px = r.left + i * step;
                    float v = x.playing ? x.wave[i] : 0f;
                    float dy = v * h * 0.46f;
                    if (i == 0) {
                        waveTop.moveTo(px, cy - dy);
                        waveBot.moveTo(px, cy + dy * 0.55f);
                    } else {
                        waveTop.lineTo(px, cy - dy);
                        waveBot.lineTo(px, cy + dy * 0.55f);
                    }
                }
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(5f);
                stroke.setColor(Theme.alpha(Theme.secondary(), 120));
                c.drawPath(waveBot, stroke);

                stroke.setStrokeWidth(3.2f);
                stroke.setShader(Theme.accentGrad(r.left, cy, r.right, cy));
                Theme.applyGlow(stroke, 8f, Theme.primary(), 200);
                c.drawPath(waveTop, stroke);
                stroke.setShader(null);
                stroke.clearShadowLayer();
                break;
            }
            default: { // Stereo Precision VU Bars
                float barH = h * 0.30f;
                float gap = h * 0.14f;
                float topL = cy - barH - gap * 0.5f;
                float topR = cy + gap * 0.5f;
                drawVuBar(c, r.left + 24, topL, r.right, topL + barH, x.vuL, "L");
                drawVuBar(c, r.left + 24, topR, r.right, topR + barH, x.vuR, "R");
                break;
            }
        }
    }

    private void drawVuBar(Canvas c, float x0, float y0, float x1, float y1, float val, String lbl) {
        Skin.text(c, lbl, x0 - 13f, (y0 + y1) * 0.5f + 5.5f, 14.5f, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MONO, false);
        int segs = 24;
        float sw = (x1 - x0) / segs;
        int lit = Math.round(Math.max(0f, Math.min(1f, val)) * segs);
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < segs; i++) {
            float sx0 = x0 + i * sw + 1.5f, sx1 = x0 + (i + 1) * sw - 1.5f;
            int col;
            if (i >= lit) col = 0xff102035;
            else if (i < 16) col = Theme.primary();
            else if (i < 21) col = Theme.secondary();
            else col = Theme.HEART_ON;
            fill.setColor(col);
            c.drawRoundRect(sx0, y0, sx1, y1, 2.5f, 2.5f, fill);
        }
    }

    boolean hitTour(float x, float y) { return rTour.contains(x, y); }
    boolean hitLyrics(float x, float y) { return rLyrics.contains(x, y); }
    boolean hitHeart(float x, float y) { return rHeart.contains(x, y); }
    boolean hitSeek(float x, float y) { return rSeek.contains(x, y); }
    boolean hitVizOrArt(float x, float y) { return rArt.contains(x, y) || rViz.contains(x, y); }
}
