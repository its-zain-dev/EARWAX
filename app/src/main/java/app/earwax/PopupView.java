package app.earwax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import androidx.media3.common.util.UnstableApi;

/**
 * EARWAX 4.0 Redesigned Early-2000s Y2K Floating 3D Liquid-Chrome Popups.
 * Powered by our Pure-Python 3D Raymarched hardware shells with genuine 3D glossy Aqua,
 * Emerald & Ruby candy-gel dome jewels, curved obsidian-sapphire glass screens, and
 * concave machined brushed-aluminum jog wheels:
 *
 *  1. Y2K CYBER DECK ("whale"): Asymmetric liquid-chrome shell, recessed curved media visor
 *     with album art + mirrored spectrum + seek bar, concave brushed-aluminum jog wheel with
 *     glowing cyan ring, vertical crystal level tube, and glossy Aqua/Emerald/Ruby 3D gel jewels!
 *  2. CHROME SONIC ORB ("orb"): Domed mirror-chrome sphere-puck, central circular visor with
 *     live track halo, razor-smooth 270° recessed orbital touch dial, and 3D gel jewel controls!
 *  3. LIQUID MINI-PILL ("pill"): Sony MiniDisc-inspired chrome lozenge with twin cyan optical
 *     light-pipes, curved glass screen, and glossy 3D gel transport controls!
 *
 * Notice: NEVER covers the Python-rendered 3D candy-gel jewels with opaque circles — crisp
 * glowing white vector icons float cleanly INSIDE the raymarched 3D glass jewels!
 */
@UnstableApi
final class PopupView extends View implements PlayerService.Listener {
    private final String skinName;
    private final Skin sk;
    private final PlayerService sv;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final DisplayMetrics dm;
    private final ScaleGestureDetector pinch;
    private final Rotary rot = new Rotary();
    private final float baseDp, aspect, slop;
    private float scale;

    private final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint S = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint B = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final Path artClip = new Path();
    private final RectF rTmp = new RectF(), rArt = new RectF();

    private Skin.Btn down;
    private boolean drag, seek, rotating, scaling, rotMoved;
    private float rx, ry, downDesignX, downDesignY, lastTick;
    private long lastTapMs, lastKnobTapMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            boolean busy = sv.pl.isPlaying() || rotating || down != null || seek;
            postDelayed(this, busy ? 36 : 380);
        }
    };

    PopupView(PlayerService s, String name) {
        super(s);
        sv = s;
        skinName = name;
        dm = s.getResources().getDisplayMetrics();
        baseDp = "orb".equals(name) ? 204f : ("pill".equals(name) ? 286f : 332f);
        scale = Math.max(0.65f, Math.min(1.65f, Prefs.popupScale(s, name)));
        sk = Skin.load(s, name, (int) (baseDp * 1.45f * dm.density));
        aspect = sk.h / (float) sk.w;
        slop = ViewConfiguration.get(s).getScaledTouchSlop();

        int wp = (int) (baseDp * scale * dm.density);
        lp = new WindowManager.LayoutParams(
                wp, (int) (wp * aspect),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.popupX(s, name, (int) (18 * dm.density));
        lp.y = Prefs.popupY(s, name, (int) (130 * dm.density));

        pinch = new ScaleGestureDetector(s, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                resize(scale * d.getScaleFactor());
                return true;
            }
        });

        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        wm.addView(this, lp);
        s.viewer(true);
        s.addListener(this);
        tick.run();
    }

    @Override public void onServiceUpdate() { postInvalidateOnAnimation(); }

    void dismiss() {
        removeCallbacks(tick);
        sv.removeListener(this);
        Prefs.popupPos(sv, skinName, lp.x, lp.y);
        Prefs.popupScale(sv, skinName, scale);
        try { wm.removeView(this); } catch (Exception ignored) { }
        sv.viewer(false);
        sk.release();
    }

    private void resize(float k) {
        k = Math.max(0.65f, Math.min(1.65f, k));
        if (Math.abs(k - scale) < 0.004f) return;
        float cx = lp.x + lp.width / 2f, cy = lp.y + lp.height / 2f;
        scale = k;
        lp.width = (int) (baseDp * scale * dm.density);
        lp.height = (int) (lp.width * aspect);
        lp.x = (int) (cx - lp.width / 2f);
        lp.y = (int) (cy - lp.height / 2f);
        wm.updateViewLayout(this, lp);
    }

    private void keepOnScreen() {
        int margin = (int) (6 * dm.density);
        int snapDist = (int) (24 * dm.density);
        if (Math.abs(lp.x - margin) < snapDist) lp.x = margin;
        else if (Math.abs((lp.x + lp.width) - (dm.widthPixels - margin)) < snapDist)
            lp.x = dm.widthPixels - margin - lp.width;

        lp.x = Math.max(-lp.width / 4, Math.min(dm.widthPixels - lp.width * 3 / 4, lp.x));
        lp.y = Math.max(margin, Math.min(dm.heightPixels - lp.height / 2, lp.y));
        wm.updateViewLayout(this, lp);
    }

    // ------------------------------------------------------------------ Drawing
    @Override
    protected void onDraw(Canvas c) {
        if (sk.bmp == null || sk.bmp.isRecycled()) return;
        Skin.resetPaints();
        Q.setShader(null); Q.setAlpha(255); Q.clearShadowLayer(); Q.setStyle(Paint.Style.FILL);
        S.setShader(null); S.setAlpha(255); S.clearShadowLayer(); S.setStyle(Paint.Style.STROKE);

        float sc = getWidth() / (float) sk.w;
        c.save();
        c.scale(sc, sc);

        // Draw our Pure-Python 3D Raymarched Y2K Liquid-Chrome Hardware Shell!
        c.drawBitmap(sk.bmp, null, sk.full, Skin.P);

        Hud x = sv.hud();
        if ("orb".equals(skinName)) drawOrbOverlay(c, x);
        else if ("pill".equals(skinName)) drawPillOverlay(c, x);
        else drawCyberDeckOverlay(c, x);

        c.restore();
    }

    // ---- 1. Y2K LIQUID CHROME CYBER DECK ("whale")
    private void drawCyberDeckOverlay(Canvas c, Hud x) {
        // A. Recessed Curved Sapphire-Obsidian Media Display
        if (sk.lcd != null) {
            RectF r = sk.lcd;
            c.save();
            clip.reset();
            clip.addRoundRect(r.left + 4, r.top + 4, r.right - 4, r.bottom - 4, 48f, 48f, Path.Direction.CW);
            c.clipPath(clip);

            Q.setShader(Theme.glassGrad(r.top, r.bottom));
            Q.setAlpha(222);
            c.drawRect(r, Q);
            Q.setAlpha(255);
            Q.setShader(null);

            float pad = 18f;
            float artS = r.height() - pad * 2f;
            rArt.set(r.left + pad, r.top + pad, r.left + pad + artS, r.top + pad + artS);
            drawAlbumTile(c, rArt, sv.artRaw(), 18f);

            float tx = rArt.right + 16f;
            float rr = r.right - pad;
            float tw = Math.max(60f, rr - tx);

            c.save();
            c.clipRect(tx, r.top, rr, r.bottom);
            Skin.marquee(c, x.title, tx, r.top + 46f, 29f, Theme.TEXT_MAIN, tw, Theme.TF_BOLD, true);
            String sub = (x.artist.isEmpty() ? "EARWAX 4.0" : x.artist) + (x.badge.isEmpty() ? "" : ("  •  " + x.badge));
            Skin.text(c, Skin.fit(sub, 17.5f, tw, Theme.TF_MEDIUM), tx, r.top + 74f, 17.5f, Theme.glow(), Paint.Align.LEFT, Theme.TF_MEDIUM, false);
            c.restore();

            // Live mirrored Y2K neon spectrum inside display
            float wy = r.top + 106f;
            int bars = 24;
            float bw = tw / bars;
            Q.setStyle(Paint.Style.FILL);
            for (int i = 0; i < bars; i++) {
                float amp = x.playing ? Math.max(0.08f, x.bars[(i * 32) / bars]) : 0.06f;
                float hh = amp * 20f;
                Q.setColor(Theme.lerp(Theme.primary(), Theme.secondary(), i / (float) bars));
                c.drawRoundRect(tx + i * bw + 1.5f, wy - hh, tx + (i + 1) * bw - 1.5f, wy + hh, 2f, 2f, Q);
            }

            // Liquid-neon interactive seek bar + timestamps
            float sy = r.bottom - 36f;
            float frac = (x.dur > 0 && !x.live) ? Math.max(0f, Math.min(1f, x.pos / (float) x.dur)) : 0f;
            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(7f);
            S.setColor(0xff132742);
            c.drawLine(tx, sy, rr, sy, S);
            if (frac > 0.002f) {
                S.setShader(Theme.accentGrad(tx, sy, rr, sy));
                c.drawLine(tx, sy, tx + tw * frac, sy, S);
                S.setShader(null);
            }
            Q.setColor(0xffffffff);
            c.drawCircle(tx + tw * frac, sy, 8f, Q);

            Skin.text(c, Skin.mmss(x.pos), tx, r.bottom - 10f, 16f, Theme.glow(), Paint.Align.LEFT, Theme.TF_MONO, false);
            Skin.text(c, x.live ? "LIVE" : Skin.mmss(x.dur), rr, r.bottom - 10f, 16f, Theme.TEXT_SUB, Paint.Align.RIGHT, Theme.TF_MONO, false);

            // Signature Y2K top-half glossy glass reflection
            Q.setShader(Theme.y2kGlossSheen(r.top, r.top + r.height() * 0.48f));
            c.drawRect(r.left, r.top, r.right, r.top + r.height() * 0.48f, Q);
            Q.setShader(null);
            c.restore();
        }

        // B. Concave Machined Brushed-Aluminum Rotary Jog Wheel (3D metal baked in Python — draw glowing ring + LED!)
        if (sk.knob != null) {
            float kx = sk.knob.cx, ky = sk.knob.cy, kr = sk.knob.r;
            Skin.text(c, x.tool, kx, ky - kr - 18f, 18.5f, Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, true);

            rTmp.set(kx - kr * 1.14f, ky - kr * 1.14f, kx + kr * 1.14f, ky + kr * 1.14f);
            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(7.5f);
            S.setColor(0x40102844);
            c.drawArc(rTmp, -225, 270, false, S);

            float v = Math.max(0.01f, Math.min(1f, x.toolNorm));
            S.setShader(Theme.accentGrad(rTmp.left, rTmp.top, rTmp.right, rTmp.bottom));
            Theme.applyGlow(S, rotating ? 16f : 10f, Theme.primary(), 225);
            c.drawArc(rTmp, -225, 270f * v, false, S);
            S.setShader(null);
            S.clearShadowLayer();

            // Glowing machined indicator needle & sapphire LED dot rotating on the brushed aluminum dish!
            double a = Math.toRadians(-135.0 + 270.0 * v);
            float sx = (float) Math.sin(a), cy = -(float) Math.cos(a);
            S.setStrokeWidth(5f);
            S.setColor(rotating ? 0xffffffff : 0xff0d2340);
            c.drawLine(kx + sx * kr * 0.30f, ky + cy * kr * 0.30f, kx + sx * kr * 0.72f, ky + cy * kr * 0.72f, S);
            Q.setColor(Theme.primary());
            Theme.applyGlow(Q, 9f, Theme.primary(), 220);
            c.drawCircle(kx + sx * kr * 0.68f, ky + cy * kr * 0.68f, 8.5f, Q);
            Q.clearShadowLayer();
            Q.setColor(0xffffffff);
            c.drawCircle(kx + sx * kr * 0.68f, ky + cy * kr * 0.68f, 4f, Q);

            Skin.text(c, x.toolText, kx, ky + kr + 25f, 18.5f, Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_MONO, rotating);
        }

        // C. Vertical Recessed Crystal Level Meter Bar
        if (sk.meter != null) {
            RectF m = sk.meter;
            float v = Math.max(0.05f, Math.min(1f, x.toolNorm));
            float top = m.bottom - (m.height() - 8f) * v;
            Q.setShader(new LinearGradient(m.left, m.bottom, m.left, m.top, Theme.secondary(), Theme.primary(), Shader.TileMode.CLAMP));
            rTmp.set(m.left + 4, top, m.right - 4, m.bottom - 4);
            c.drawRoundRect(rTmp, m.width() * 0.4f, m.width() * 0.4f, Q);
            Q.setShader(null);
        }

        // D. Glossy 3D Gel Jewel Buttons & Quick Tool Selector Pills
        for (Skin.Btn b : sk.btns) {
            if (b == sk.knob) continue;
            boolean pr = (b == down);
            if (b.id.startsWith("t_")) {
                int tIdx = "t_vol".equals(b.id) ? 0 : ("t_pitch".equals(b.id) ? 1 : 2);
                boolean active = (sv.tool == tIdx);
                String lbl = tIdx == 0 ? "VOL" : (tIdx == 1 ? "PITCH" : "SPEED");
                float hw = b.r * 1.44f, hh = b.r * 0.72f;
                rTmp.set(b.cx - hw, b.cy - hh, b.cx + hw, b.cy + hh);
                if (active || pr) {
                    Q.setShader(Theme.aquaPillGrad(rTmp.top, rTmp.bottom, Theme.primary()));
                    c.drawRoundRect(rTmp, hh, hh, Q);
                    Q.setShader(null);
                    S.setStrokeWidth(2.2f);
                    S.setColor(Theme.glow());
                    c.drawRoundRect(rTmp, hh, hh, S);
                }
                Skin.text(c, lbl, b.cx, b.cy + 6.5f, 17.5f,
                        active || pr ? 0xff03101d : Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, !active);
            } else {
                drawJewelGlyph(c, b, pr, "play".equals(b.id), x);
            }
        }
    }

    // ---- 2. Y2K CHROME SONIC ORB ("orb")
    private void drawOrbOverlay(Canvas c, Hud x) {
        if (sk.lcd != null) {
            float cx = sk.lcd.centerX(), cy = sk.lcd.centerY(), r = sk.lcd.width() * 0.5f;

            // Circular track progress halo around the glass visor
            if (x.dur > 0 && !x.live) {
                rTmp.set(cx - r - 6, cy - r - 6, cx + r + 6, cy + r + 6);
                S.setStrokeWidth(5f);
                S.setStrokeCap(Paint.Cap.ROUND);
                S.setColor(0x40122c4a);
                c.drawArc(rTmp, 0, 360, false, S);
                S.setShader(Theme.accentGrad(rTmp.left, rTmp.top, rTmp.right, rTmp.bottom));
                c.drawArc(rTmp, -90, 360f * Math.min(1f, x.pos / (float) x.dur), false, S);
                S.setShader(null);
            }

            c.save();
            clip.reset();
            clip.addCircle(cx, cy, r - 4f, Path.Direction.CW);
            c.clipPath(clip);

            Bitmap art = sv.artRaw();
            if (art != null && !art.isRecycled()) {
                rTmp.set(cx - r, cy - r, cx + r, cy + r);
                B.setAlpha(74);
                c.drawBitmap(art, null, rTmp, B);
                B.setAlpha(255);
            }
            Q.setColor(0xb4050c18);
            c.drawCircle(cx, cy, r, Q);

            Skin.text(c, x.buffering ? "LOADING" : (x.live ? "LIVE" : (x.playing ? "PLAYING" : "PAUSED")),
                    cx, cy - r * 0.56f, 17f, Theme.primary(), Paint.Align.CENTER, Theme.TF_BOLD, true);
            Skin.text(c, Skin.mmss(x.pos), cx, cy - r * 0.04f, 54f, Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_MONO, true);

            if (sk.info != null) {
                c.save();
                c.clipRect(sk.info);
                Skin.marquee(c, x.title, sk.info.left, sk.info.centerY() + 7f, 21f, Theme.glow(), sk.info.width(), Theme.TF_BOLD, false);
                c.restore();
            }

            if (sk.spec != null) {
                if (x.buffering) {
                    float pulse = (float) ((System.currentTimeMillis() % 1000L) / 1000.0);
                    float sx0 = sk.spec.left + sk.spec.width() * Math.max(0f, pulse - 0.22f);
                    float sx1 = sk.spec.left + sk.spec.width() * Math.min(1f, pulse + 0.22f);
                    S.setStrokeCap(Paint.Cap.ROUND);
                    S.setStrokeWidth(6f);
                    S.setColor(Theme.primary());
                    c.drawLine(sx0, sk.spec.centerY(), sx1, sk.spec.centerY(), S);
                } else {
                    int bars = 18;
                    float bw = sk.spec.width() / bars;
                    float my = sk.spec.centerY();
                    Q.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < bars; i++) {
                        float amp = x.playing ? Math.max(0.08f, x.bars[(i * 32) / bars]) : 0.06f;
                        float hh = amp * sk.spec.height() * 0.46f;
                        Q.setColor(Theme.lerp(Theme.primary(), Theme.secondary(), i / (float) bars));
                        c.drawRoundRect(sk.spec.left + i * bw + 1.5f, my - hh,
                                sk.spec.left + (i + 1) * bw - 1.5f, my + hh, 2f, 2f, Q);
                    }
                }
            }

            // Glossy curved upper glass reflection arc
            Q.setShader(Theme.y2kGlossSheen(cy - r, cy));
            c.drawRect(cx - r, cy - r, cx + r, cy, Q);
            Q.setShader(null);
            c.restore();
        }

        // 270° Recessed Orbital Touch Dial (baked cleanly in 3D Python — paint luminous neon arc inside channel!)
        if (sk.dial != null) {
            Skin.Dial d = sk.dial;
            float rm = (d.r0 + d.r1) * 0.5f;
            float trackW = (d.r1 - d.r0) * 0.44f;
            float sweep = d.a1 - d.a0;
            float start = d.a0 - 90f;
            rTmp.set(d.cx - rm, d.cy - rm, d.cx + rm, d.cy + rm);

            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(2f);
            S.setColor(0x669fd8ff);
            for (int i = 0; i <= 24; i++) {
                double a = Math.toRadians(d.a0 + sweep * i / 24f);
                float sx = (float) Math.sin(a), cy = -(float) Math.cos(a);
                c.drawLine(d.cx + sx * (d.r1 - 10), d.cy + cy * (d.r1 - 10),
                        d.cx + sx * (d.r1 - 3), d.cy + cy * (d.r1 - 3), S);
            }

            float v = Math.max(0.01f, Math.min(1f, x.toolNorm));
            S.setStrokeWidth(trackW);
            S.setShader(Theme.accentGrad(rTmp.left, rTmp.top, rTmp.right, rTmp.bottom));
            Theme.applyGlow(S, rotating ? 16f : 10f, Theme.primary(), 225);
            c.drawArc(rTmp, start, sweep * v, false, S);
            S.setShader(null);
            S.clearShadowLayer();

            double a = Math.toRadians(d.a0 + sweep * v);
            float tx = d.cx + (float) Math.sin(a) * rm;
            float ty = d.cy - (float) Math.cos(a) * rm;
            Q.setColor(Theme.alpha(Theme.primary(), 130));
            c.drawCircle(tx, ty, trackW * (rotating ? 1.0f : 0.78f), Q);
            Q.setColor(0xffffffff);
            c.drawCircle(tx, ty, trackW * 0.46f, Q);

            // Chin bridge tool readout (tap to cycle tool!)
            Skin.text(c, x.tool + " " + x.toolText, d.cx, d.cy + rm * 0.99f, 22f,
                    Theme.glow(), Paint.Align.CENTER, Theme.TF_MONO, true);
        }

        for (Skin.Btn b : sk.btns) {
            drawJewelGlyph(c, b, b == down, "play".equals(b.id), x);
        }
    }

    // ---- 3. Y2K LIQUID CHROME MINI-PILL ("pill")
    private void drawPillOverlay(Canvas c, Hud x) {
        if (sk.lcd != null) {
            RectF r = sk.lcd;
            c.save();
            clip.reset();
            clip.addRoundRect(r.left + 3, r.top + 3, r.right - 3, r.bottom - 3, r.height() * 0.5f, r.height() * 0.5f, Path.Direction.CW);
            c.clipPath(clip);

            Q.setShader(Theme.glassGrad(r.top, r.bottom));
            Q.setAlpha(220);
            c.drawRect(r, Q);
            Q.setAlpha(255);
            Q.setShader(null);

            if (x.dur > 0 && !x.live) {
                float f = Math.max(0f, Math.min(1f, x.pos / (float) x.dur));
                Q.setShader(Theme.accentGrad(r.left, r.bottom, r.right, r.bottom));
                c.drawRect(r.left, r.bottom - 7f, r.left + r.width() * f, r.bottom, Q);
                Q.setShader(null);
            }

            float pad = 16f;
            float artS = r.height() - pad * 2f;
            rArt.set(r.left + pad + 10f, r.top + pad, r.left + pad + 10f + artS, r.top + pad + artS);
            drawAlbumTile(c, rArt, sv.artRaw(), artS * 0.5f);

            float tx = rArt.right + 16f;
            float tr = r.right - 22f;
            float tw = Math.max(70f, tr - tx);
            c.save();
            c.clipRect(tx, r.top, tr, r.bottom);
            Skin.marquee(c, x.title, tx, r.centerY() - 10f, 26.5f, Theme.TEXT_MAIN, tw, Theme.TF_BOLD, true);
            String sub = x.artist.isEmpty() ? x.badge : x.artist;
            Skin.text(c, Skin.fit(sub, 18.5f, tw - 76f, Theme.TF_MEDIUM), tx, r.centerY() + 20f, 18.5f, Theme.glow(), Paint.Align.LEFT, Theme.TF_MEDIUM, false);

            // Compact live Y2K mini-bars on right side of Pill display
            int mb = 8;
            float mbW = 66f / mb;
            float mbBase = r.centerY() + 24f;
            Q.setStyle(Paint.Style.FILL);
            for (int i = 0; i < mb; i++) {
                float amp = x.playing ? Math.max(0.12f, x.bars[i * 4]) : 0.10f;
                float bh = amp * 24f;
                Q.setColor(Theme.lerp(Theme.primary(), Theme.secondary(), i / (float) mb));
                c.drawRoundRect(tr - 68f + i * mbW + 1f, mbBase - bh, tr - 68f + (i + 1) * mbW - 1f, mbBase, 2f, 2f, Q);
            }
            c.restore();

            Q.setShader(Theme.y2kGlossSheen(r.top, r.centerY()));
            c.drawRect(r.left, r.top, r.right, r.centerY(), Q);
            Q.setShader(null);
            c.restore();
        }

        for (Skin.Btn b : sk.btns) {
            drawJewelGlyph(c, b, b == down, "play".equals(b.id), x);
        }
    }

    private void drawAlbumTile(Canvas c, RectF r, Bitmap bmp, float rad) {
        c.save();
        artClip.reset();
        artClip.addRoundRect(r, rad, rad, Path.Direction.CW);
        c.clipPath(artClip);
        Q.setColor(0xff0b1729);
        c.drawRect(r, Q);
        if (bmp != null && !bmp.isRecycled()) {
            float k = Math.max(r.width() / bmp.getWidth(), r.height() / bmp.getHeight());
            float aw = bmp.getWidth() * k, ah = bmp.getHeight() * k;
            rTmp.set(r.centerX() - aw / 2f, r.centerY() - ah / 2f, r.centerX() + aw / 2f, r.centerY() + ah / 2f);
            c.drawBitmap(bmp, null, rTmp, B);
        }
        c.restore();
        S.setStrokeWidth(2f);
        S.setColor(Theme.alpha(Theme.primary(), 185));
        c.drawRoundRect(r, rad, rad, S);
    }

    /**
     * Draws ONLY crisp glowing glyphs & luminous feedback rings OVER the Python-raymarched
     * 3D Aqua / Emerald / Ruby candy-gel dome jewels so the real 3D lighting shines through!
     */
    private void drawJewelGlyph(Canvas c, Skin.Btn b, boolean pressed, boolean primary, Hud x) {
        float rr = b.r * (pressed ? 0.91f : 1f);
        if (pressed) {
            Q.setStyle(Paint.Style.FILL);
            Q.setColor(0x55ffffff);
            c.drawCircle(b.cx, b.cy, rr * 0.95f, Q);
        }
        if (pressed || (primary && x.playing)) {
            S.setStyle(Paint.Style.STROKE);
            S.setStrokeWidth(pressed ? 4.2f : 2.8f);
            int glowCol = pressed ? 0xffffffff : Theme.primary();
            S.setColor(Theme.alpha(glowCol, pressed ? 245 : (int) (135 + 110 * x.bass)));
            Theme.applyGlow(S, 11f, glowCol, 210);
            c.drawCircle(b.cx, b.cy, rr * 1.04f, S);
            S.clearShadowLayer();
        }
        // High-contrast crisp white vector icon with soft shadow floating inside the 3D gel jewel!
        Skin.glyph(c, b.id, b.cx, b.cy, rr * 0.90f, x.playing, 0xffffffff, x.repeat, true);
    }

    // ------------------------------------------------------------------ Touch handling
    private boolean onRotary(float x, float y) {
        if (sk.knob != null) return Math.hypot(x - sk.knob.cx, y - sk.knob.cy) <= sk.knob.r * 1.42f;
        if (sk.dial != null) {
            Skin.Dial d = sk.dial;
            float r = (float) Math.hypot(x - d.cx, y - d.cy);
            float a = Rotary.angle(d.cx, d.cy, x, y);
            return r >= d.r0 - 24 && r <= d.r1 + 24 && Math.abs(a) <= 142;
        }
        return false;
    }

    private float rotCx() { return sk.knob != null ? sk.knob.cx : sk.dial.cx; }
    private float rotCy() { return sk.knob != null ? sk.knob.cy : sk.dial.cy; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        if (pinch.isInProgress() || e.getPointerCount() > 1) {
            scaling = true; down = null; rotating = false; seek = false; rot.up();
            return true;
        }
        float sc = getWidth() / (float) sk.w;
        float x = e.getX() / sc;
        float y = e.getY() / sc;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scaling = false; drag = false; seek = false; rotating = false; rotMoved = false;
                rx = e.getRawX(); ry = e.getRawY();
                downDesignX = x; downDesignY = y;
                down = sk.hit(x, y);
                if (down != null && down == sk.knob) down = null;
                if (down == null && onRotary(x, y)) {
                    rotating = true;
                    rot.down(rotCx(), rotCy(), x, y);
                    lastTick = sv.toolNorm();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                } else if (down == null && sk.info != null && sk.info.contains(x, y) && !"orb".equals(skinName)) {
                    seek = true;
                    sv.seekFrac((x - sk.info.left) / sk.info.width());
                } else if (down != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scaling) return true;
                if (rotating) {
                    float deg = rot.move(rotCx(), rotCy(), x, y, 16f);
                    if (deg != 0f) {
                        rotMoved = true;
                        sv.adjustTool(deg / 270f);
                        float n = sv.toolNorm();
                        if (Math.abs(n - lastTick) >= 0.05f) {
                            lastTick = n;
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        invalidate();
                    }
                } else if (seek && sk.info != null) {
                    sv.seekFrac((x - sk.info.left) / sk.info.width());
                    invalidate();
                } else if (down == null || sk.hit(x, y) != down) {
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (drag || Math.hypot(dx, dy) > slop) {
                        drag = true;
                        down = null;
                        lp.x += (int) dx;
                        lp.y += (int) dy;
                        wm.updateViewLayout(this, lp);
                        rx = e.getRawX();
                        ry = e.getRawY();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (!scaling && !drag) {
                    if (down != null && sk.hit(x, y) == down) {
                        sv.popupAction(down.id);
                    } else if (rotating && !rotMoved && sk.knob != null) {
                        // Double-tap rotary jog wheel to reset current DSP tool to default!
                        long now = System.currentTimeMillis();
                        if (now - lastKnobTapMs < 340) {
                            lastKnobTapMs = 0;
                            sv.resetActiveTool();
                            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        } else {
                            lastKnobTapMs = now;
                        }
                    } else if (!rotating && !seek && "orb".equals(skinName) && sk.dial != null
                            && y > sk.dial.cy + (sk.dial.r0 + sk.dial.r1) * 0.41f
                            && Math.abs(x - sk.dial.cx) < 130f) {
                        // Tap Orb chin readout to cycle active rotary tool!
                        sv.popupAction("tool");
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    } else if (!rotating && !seek && sk.lcd != null && sk.lcd.contains(downDesignX, downDesignY)) {
                        long now = System.currentTimeMillis();
                        if (now - lastTapMs < 320) {
                            lastTapMs = 0;
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            sv.switchPopupStyle();
                            return true;
                        }
                        lastTapMs = now;
                    }
                }
                if (drag || scaling) keepOnScreen();
                down = null; rotating = false; rotMoved = false; seek = false; drag = false; rot.up();
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                down = null; rotating = false; seek = false; drag = false; rot.up();
                invalidate();
                return true;
        }
        return true;
    }
}
