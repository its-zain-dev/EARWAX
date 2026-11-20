package app.earwax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.DisplayCutout;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.OverScroller;
import androidx.media3.common.util.UnstableApi;
import java.io.InputStream;

/**
 * EARWAX 4.0 Main Interactive Early-2000s 3D Liquid-Chrome Stage.
 *  - Pure-Python Raymarched 3D Liquid-Chrome Cyber-Bust (Head + Anatomical Neck + Sculpted Chest)
 *  - Real-time 2.5D GyroParallax depth layering (Backdrop / Bust / Ear Pods / Levitating Aqua Crown Orbs)
 *  - Beat-reactive Ear Speaker Pods with 3-band cone excursion & expanding acoustic shockwave rings
 *  - 4 Levitating Glossy Y2K Aqua-Chrome Glass Orbs (Shuffle, Repeat, Sleep, Floating Popups)
 *  - Curved Chrome-Glass Forehead Visor (VisorRenderer) with 4 live visualizers & 1-tap Synced Lyrics
 *  - 5-Mode Chest Console (YouTube Music + Library / 10-Band EQ / Studio FX + Reliability / Queue / Synced Lyrics)
 *  - Low-RAM & low-CPU optimized for weak devices so background music NEVER stutters or stops!
 */
@UnstableApi
final class StageView extends View implements PlayerService.Listener {
    private static final float DW = 1080f, DH = 2100f;
    private static final int[] TOUR = {
            R.string.tour_0, R.string.tour_1, R.string.tour_2,
            R.string.tour_3, R.string.tour_4, R.string.tour_5, R.string.tour_6
    };

    private final Skin head;
    private final VisorRenderer visor = new VisorRenderer();
    private final ConsoleRenderer console;
    private final GyroParallax gyro;

    private final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint S = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint P = Skin.P;
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private final OverScroller sc;
    private final GestureDetector gd;

    private Bitmap bg, logo, mark, bgBake;
    private float s = 1f, ox, oy;
    private int insL, insT, insR, insB, w, h;
    private final long introT = System.currentTimeMillis();
    private boolean registered, tourChecked;
    private int vizMode;

    private static final int MAX_WAVES = 4;
    private final float[] waveAge = new float[MAX_WAVES];
    private float lastShock;
    private long lastFrameMs;

    private Skin.Btn down;
    private boolean seeking, listTouch, hdrTap, swipe, moved;
    private float downX, downY;
    private long lastTap;

    private int tour = -1;
    private StaticLayout tourTitle, tourBody;
    private final RectF skipBtn = new RectF(), nextBtn = new RectF(), bubble = new RectF();
    private final TextPaint tpTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tpBody = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tpUi = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            PlayerService v = PlayerService.I;
            boolean active = tour >= 0
                    || (v != null && (v.pl.isPlaying() || v.pl.getPlaybackState() == androidx.media3.common.Player.STATE_BUFFERING))
                    || !sc.isFinished()
                    || System.currentTimeMillis() - introT < 1850
                    || gyro.isEnabled();
            // 30 FPS when active (butter smooth, low CPU on weak devices), 350ms when idle
            postDelayed(this, active ? 33 : 350);
        }
    };

    StageView(Context c) {
        super(c);
        // Cap decode resolution cleanly to save precious RAM on 1.8-2 GB devices while keeping retina clarity
        int targetW = Math.min(820, c.getResources().getDisplayMetrics().widthPixels);
        head = Skin.load(c, "bust", targetW);
        console = new ConsoleRenderer(c, this, head.panel);
        gyro = new GyroParallax(c);
        gyro.setEnabled(Prefs.gyro(c));
        vizMode = Prefs.vizMode(c);
        sc = new OverScroller(c);

        try (InputStream in = c.getAssets().open("skins/bg.jpg")) { bg = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
        try (InputStream in = c.getAssets().open("skins/logo.png")) { logo = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
        try (InputStream in = c.getAssets().open("skins/mark.png")) { mark = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }

        tpTitle.setTypeface(Theme.TF_BOLD);
        tpBody.setTypeface(Theme.TF_REGULAR);
        tpUi.setTypeface(Theme.TF_BOLD);

        for (int i = 0; i < MAX_WAVES; i++) waveAge[i] = 2f;

        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                console.noteUserScroll();
                console.scroll = console.clampScroll(console.scroll + dy / s);
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                console.noteUserScroll();
                sc.fling(0, (int) console.scroll, 0, (int) (-vy / s), 0, 0, 0, (int) console.maxScroll(), 0, 0);
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                console.tapList(PlayerService.I, (e.getX() - ox) / s, (e.getY() - oy) / s);
                invalidate();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                console.longList(PlayerService.I, (e.getX() - ox) / s, (e.getY() - oy) / s);
            }
        });

        setBackgroundColor(Theme.BG_DEEP);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    void setMode(int m) {
        if (m >= 0 && m <= 4) {
            console.mode = m;
            if (PlayerService.I != null) {
                if (m == 4) PlayerService.I.loadLyricsForCurrent();
                else console.rebuild(PlayerService.I, false);
            }
            invalidate();
        }
    }

    boolean handleBack() {
        if (tour >= 0) { endTour(); return true; }
        if (console.handleBack(PlayerService.I)) { invalidate(); return true; }
        return false;
    }

    // ------------------------------------------------------------------ Lifecycle
    private void watch(boolean on) {
        if (on == registered) return;
        registered = on;
        if (on) gyro.start(); else gyro.stop();
        PlayerService.with(() -> {
            PlayerService.I.viewer(on);
            if (on) PlayerService.I.addListener(this);
            else PlayerService.I.removeListener(this);
        });
        removeCallbacks(tick);
        if (on) post(tick);
    }

    @Override public void onServiceUpdate() { postInvalidateOnAnimation(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (getWindowVisibility() == VISIBLE) watch(true); }
    @Override protected void onDetachedFromWindow() { watch(false); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); watch(v == VISIBLE); }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets in) {
        insL = in.getSystemWindowInsetLeft();
        insT = in.getSystemWindowInsetTop();
        insR = in.getSystemWindowInsetRight();
        insB = in.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = in.getDisplayCutout();
            if (dc != null) {
                insL = Math.max(insL, dc.getSafeInsetLeft());
                insT = Math.max(insT, dc.getSafeInsetTop());
                insR = Math.max(insR, dc.getSafeInsetRight());
                insB = Math.max(insB, dc.getSafeInsetBottom());
            }
        }
        layoutStage();
        invalidate();
        return in;
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw; h = nh;
        layoutStage();
        bakeBackdrop();
        if (tour >= 0) buildTour();
    }

    private void layoutStage() {
        float pad = dp(6);
        float aw = w - insL - insR - 2 * pad;
        float ah = h - insT - insB - 2 * pad;
        if (aw <= 0 || ah <= 0) return;
        s = Math.min(aw / DW, ah / DH);
        ox = insL + pad + (aw - DW * s) / 2f;
        oy = insT + pad + (ah - DH * s) / 2f;
    }

    @Override
    public void computeScroll() {
        if (sc.computeScrollOffset()) {
            console.scroll = sc.getCurrY();
            postInvalidateOnAnimation();
        }
    }

    private void bakeBackdrop() {
        if (bgBake != null) bgBake.recycle();
        int bw = Math.max(1, w / 2), bh = Math.max(1, h / 2);
        bgBake = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
        Canvas k = new Canvas(bgBake);
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (bg != null) {
            k.drawBitmap(bg, null, new RectF(0, 0, bw, bh), f);
        } else {
            f.setShader(new LinearGradient(0, 0, 0, bh, 0xff091326, 0xff04070e, Shader.TileMode.CLAMP));
            k.drawRect(0, 0, bw, bh, f);
            f.setShader(null);
        }
        f.setShader(new RadialGradient(bw * 0.22f, bh * 0.24f, bw * 0.85f, 0x2a33d6ff, 0x0033d6ff, Shader.TileMode.CLAMP));
        k.drawRect(0, 0, bw, bh, f);
        f.setShader(new RadialGradient(bw * 0.82f, bh * 0.32f, bw * 0.78f, 0x246b78ff, 0x006b78ff, Shader.TileMode.CLAMP));
        k.drawRect(0, 0, bw, bh, f);
    }

    // ------------------------------------------------------------------ Drawing
    @Override
    protected void onDraw(Canvas c) {
        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0 ? 0.033f : Math.min(0.08f, (now - lastFrameMs) / 1000f);
        lastFrameMs = now;

        Skin.resetPaints();
        Q.setShader(null); Q.setColorFilter(null); Q.setAlpha(255); Q.clearShadowLayer(); Q.setStyle(Paint.Style.FILL);
        S.setShader(null); S.setColorFilter(null); S.setAlpha(255); S.clearShadowLayer(); S.setStyle(Paint.Style.STROKE);

        if (bgBake != null && !bgBake.isRecycled()) {
            float bgShiftX = -gyro.x * dp(10);
            float bgShiftY = -gyro.y * dp(8);
            tmp.set(-dp(12) + bgShiftX, -dp(10) + bgShiftY, w + dp(12) + bgShiftX, h + dp(10) + bgShiftY);
            c.drawBitmap(bgBake, null, tmp, P);
        }

        c.save();
        drawStage(c, now, dt);
        c.restore();

        if (now - introT < 1600) {
            drawIntro(c, now - introT);
        } else if (!tourChecked) {
            tourChecked = true;
            if (!Prefs.tourSeen(getContext())) startTour();
        }

        if (tour >= 0) drawTour(c);
    }

    private static int coneBand(String id) {
        switch (id) {
            case "next": case "fx": return 0;
            case "play": case "eq": return 1;
            case "prev": case "list": return 2;
            default: return -1;
        }
    }

    private static boolean isCrownOrb(String id) {
        return "shuffle".equals(id) || "repeat".equals(id) || "sleep".equals(id) || "popup".equals(id);
    }

    private void drawStage(Canvas c, long now, float dt) {
        PlayerService sv = PlayerService.I;
        float pxBust = gyro.x * 7f;
        float pyBust = gyro.y * 5f;

        c.translate(ox, oy);
        c.scale(s, s);

        if (sv == null) {
            c.drawBitmap(head.bmp, null, head.full, P);
            return;
        }
        Hud x = sv.hud();

        if (x.shock > 0.88f && lastShock <= 0.88f) {
            for (int i = 0; i < MAX_WAVES; i++) {
                if (waveAge[i] >= 1f) { waveAge[i] = 0f; break; }
            }
        }
        lastShock = x.shock;
        for (int i = 0; i < MAX_WAVES; i++) {
            if (waveAge[i] < 1.2f) waveAge[i] += dt * 1.65f;
        }

        // A. Live Bass-Reactive Y2K Volumetric Studio Halo behind Bust
        float haloR = 480f + 95f * x.bass;
        Q.setShader(new RadialGradient(540f + pxBust * 1.4f, 520f + pyBust, haloR,
                Theme.alpha(x.accentColor, (int) (46 + 52 * x.bass)),
                0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(540f + pxBust * 1.4f, 520f + pyBust, haloR, Q);
        Q.setShader(null);

        // B. Expanding Acoustic Shockwaves from Ear Speaker Pods
        if (x.playing) {
            for (int i = 0; i < MAX_WAVES; i++) {
                float a = waveAge[i];
                if (a >= 1f) continue;
                float ease = 1f - (1f - a) * (1f - a);
                int alpha = (int) ((1f - a) * (1f - a) * 145f);
                S.setStrokeWidth(6f * (1f - a * 0.65f));
                S.setColor(Theme.alpha(i % 2 == 0 ? Theme.primary() : Theme.secondary(), alpha));
                for (Skin.Ear e : head.ears) {
                    float ex = e.left ? e.r.left + 32f : e.r.right - 32f;
                    float ey = e.r.centerY();
                    float rr = 65f + ease * 210f;
                    c.drawCircle(ex, ey, rr, S);
                }
            }
        }

        // C. Pure-Python Raymarched 3D Y2K Liquid-Chrome Bust
        c.save();
        c.translate(pxBust, pyBust);
        c.drawBitmap(head.bmp, null, head.full, P);

        // Live 2.5D gyro-driven chrome specular shimmer gliding across cranium & shoulders
        if (gyro.isEnabled() && (Math.abs(gyro.x) > 0.03f || Math.abs(gyro.y) > 0.03f)) {
            float sx = 540f - gyro.x * 160f;
            float sy = 250f - gyro.y * 65f;
            Q.setStyle(Paint.Style.FILL);
            Q.setShader(new RadialGradient(sx, sy, 230f, 0x26e8f8ff, 0x00000000, Shader.TileMode.CLAMP));
            c.drawCircle(sx, sy, 230f, Q);
            Q.setShader(null);
        }

        // Forehead Curved Chrome-Glass Visor
        visor.draw(c, head.screen, x, sv.artRaw(), vizMode, console.mode == 4, gyro.x);

        // Chest Obsidian-Glass Studio Console
        console.draw(c, sv, gyro);

        // Collarbone Neon Progress Energy Strip tucked cleanly above Chest Console
        if (head.panel != null) {
            float ny = head.panel.top - 50f;
            float nx0 = 370f, nx1 = 710f;
            float prog = (x.dur > 0 && !x.live) ? Math.max(0f, Math.min(1f, x.pos / (float) x.dur)) : (0.5f + 0.3f * x.bass);
            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(4.5f);
            S.setColor(0x3833d6ff);
            c.drawLine(nx0, ny, nx1, ny, S);
            S.setShader(Theme.accentGrad(nx0, ny, nx1, ny));
            Theme.applyGlow(S, 10f + 8f * x.bass, Theme.primary(), 185);
            c.drawLine(nx0, ny, nx0 + (nx1 - nx0) * prog, ny, S);
            S.setShader(null);
            S.clearShadowLayer();
        }
        c.restore();

        // D. Sculpted 3D Liquid-Chrome Ear Speaker Pods
        float earPx = gyro.x * 13f;
        float earPy = gyro.y * 9f;
        float shake = x.playing ? 3.2f * x.env[0] : 0f;
        for (Skin.Ear e : head.ears) {
            float dx = earPx + (e.left ? -shake : shake);
            c.save();
            c.translate(dx, earPy);
            if (x.playing && x.bass > 0.08f) {
                Q.setShader(new RadialGradient(e.r.centerX(), e.r.centerY(), e.r.height() * 0.56f,
                        Theme.alpha(e.left ? Theme.primary() : Theme.secondary(), (int) (75 * x.bass)),
                        0x00000000, Shader.TileMode.CLAMP));
                c.drawCircle(e.r.centerX(), e.r.centerY(), e.r.height() * 0.56f, Q);
                Q.setShader(null);
            }
            c.drawBitmap(e.bmp, null, e.r, P);
            c.restore();
        }

        // E. Six Metallic Speaker Cone Drivers + Crisp Vector Controls
        for (Skin.Btn b : head.btns) {
            int band = coneBand(b.id);
            if (band < 0) continue;
            boolean isLeft = b.cx < 540f;
            float dx = earPx + (isLeft ? -shake : shake);
            float bx = b.cx + dx;
            float by = b.cy + earPy;
            float env = x.playing ? x.env[band] : 0f;
            float press = (down == b) ? 0.90f : 1f;
            boolean active = ("list".equals(b.id) && (console.mode == 0 || console.mode == 3 || console.mode == 4))
                    || ("eq".equals(b.id) && console.mode == 1)
                    || ("fx".equals(b.id) && console.mode == 2)
                    || ("play".equals(b.id) && x.playing);

            if (head.cone != null) {
                float baseR = b.r * 0.88f;
                float half = baseR * (1f + 0.16f * env) * press;
                tmp.set(bx - half, by - half, bx + half, by + half);
                c.drawBitmap(head.cone, null, tmp, P);
            }

            float ringR = b.r * (0.90f + 0.11f * env) * press;
            if (active || env > 0.06f || down == b) {
                int rimCol = down == b ? 0xffffffff : (isLeft ? Theme.primary() : Theme.secondary());
                S.setStrokeWidth(down == b ? 5.5f : (active ? 4.2f : 2.8f));
                S.setColor(Theme.alpha(rimCol, down == b ? 245 : (active ? 225 : (int) (175 * env))));
                Theme.applyGlow(S, 12f, rimCol, 190);
                c.drawCircle(bx, by, ringR, S);
                S.clearShadowLayer();
            }

            int glyphCol = (active || down == b) ? 0xffffffff : Theme.glow();
            Skin.glyph(c, b.id, bx, by, b.r * (0.84f + 0.12f * env) * press, x.playing, glyphCol, x.repeat, true);
        }

        // F. Four Levitating Early-2000s Aqua-Chrome Glass Crown Orbs
        float orbPx = gyro.x * 19f;
        float orbPy = gyro.y * 13f;
        int orbIdx = 0;
        for (Skin.Btn b : head.btns) {
            if (!isCrownOrb(b.id)) continue;
            float bob = (float) Math.sin(now / 650.0 + orbIdx * 1.15) * 6.5f;
            float bx = b.cx + orbPx;
            float by = b.cy + orbPy + bob;
            float press = (down == b) ? 0.88f : 1f;
            boolean on = ("shuffle".equals(b.id) && x.shuffle)
                    || ("repeat".equals(b.id) && x.repeat != 0)
                    || ("sleep".equals(b.id) && (sv.sleepMin > 0 || sv.sleepEndOfTrack))
                    || ("popup".equals(b.id) && sv.pv != null);

            float rr = b.r * 0.94f * press;

            int auraCol = on ? Theme.primary() : Theme.secondary();
            Q.setShader(new RadialGradient(bx, by, rr * 1.55f,
                    Theme.alpha(auraCol, on ? 140 : 62), 0x00000000, Shader.TileMode.CLAMP));
            c.drawCircle(bx, by, rr * 1.55f, Q);
            Q.setShader(null);

            if (head.pearl != null) {
                tmp.set(bx - rr, by - rr, bx + rr, by + rr);
                c.drawBitmap(head.pearl, null, tmp, P);
            } else {
                Q.setShader(new RadialGradient(bx - rr * 0.3f, by - rr * 0.3f, rr * 1.25f,
                        0xff6fe6ff, 0xff112548, Shader.TileMode.CLAMP));
                c.drawCircle(bx, by, rr, Q);
                Q.setShader(null);
            }

            if (on || down == b) {
                S.setStrokeWidth(4.0f);
                S.setColor(down == b ? 0xffffffff : Theme.primary());
                Theme.applyGlow(S, 14f, Theme.primary(), 220);
                c.drawCircle(bx, by, rr * 0.96f, S);
                S.clearShadowLayer();
            }

            Skin.glyph(c, b.id, bx, by, rr * 0.96f, x.playing, 0xffffffff, x.repeat, true);

            if ("sleep".equals(b.id) && (sv.sleepMin > 0 || sv.sleepEndOfTrack)) {
                Skin.text(c, sv.sleepLabel(), bx, by + rr + 20f, 15f, Theme.primary(), Paint.Align.CENTER, Theme.TF_MONO, true);
            }
            orbIdx++;
        }
    }

    private void drawIntro(Canvas c, long t) {
        float a = t < 850 ? 1f : Math.max(0f, 1f - (t - 850) / 750f);
        if (a <= 0f) return;

        Q.setStyle(Paint.Style.FILL);
        Q.setShader(null);
        Q.setColor(Color.argb(Math.round(244f * a), 4, 8, 16));
        c.drawRect(0, 0, w, h, Q);

        float k = 0.92f + 0.08f * Math.min(1f, t / 600f);
        if (mark != null && mark.getWidth() > 0) {
            float ms = Math.min(w * 0.44f, dp(170)) * k;
            tmp.set(w / 2f - ms / 2f, h * 0.34f - ms / 2f, w / 2f + ms / 2f, h * 0.34f + ms / 2f);
            P.setAlpha(Math.round(255f * a));
            c.drawBitmap(mark, null, tmp, P);
            P.setAlpha(255);
        }

        if (logo != null && logo.getWidth() > 0) {
            float lw = w * 0.72f * k;
            float lh = lw * logo.getHeight() / logo.getWidth();
            tmp.set(w / 2f - lw / 2f, h * 0.52f - lh / 2f, w / 2f + lw / 2f, h * 0.52f + lh / 2f);
            P.setAlpha(Math.round(255f * a));
            c.drawBitmap(logo, null, tmp, P);
            P.setAlpha(255);
        }

        tpUi.setColor(Theme.alpha(Theme.primary(), Math.round(235f * a)));
        tpUi.setTextSize(dp(13));
        tpUi.setTextAlign(Paint.Align.CENTER);
        c.drawText(getContext().getString(R.string.tagline).toUpperCase(), w / 2f, h * 0.59f + dp(14), tpUi);
    }

    // ------------------------------------------------------------------ Guided Tour
    private void startTour() { tour = 0; buildTour(); invalidate(); }
    private void endTour() { tour = -1; Prefs.tourSeen(getContext(), true); invalidate(); }

    private RectF union(String... ids) {
        RectF u = null;
        for (String id : ids) {
            Skin.Btn b = head.find(id);
            if (b == null) continue;
            RectF r = new RectF(b.cx - b.r, b.cy - b.r, b.cx + b.r, b.cy + b.r);
            if (u == null) u = r; else u.union(r);
        }
        return u;
    }

    private RectF tourTarget() {
        switch (tour) {
            case 1: return union("prev", "play", "next");
            case 2: return union("list", "eq", "fx");
            case 3: return union("shuffle", "repeat", "sleep", "popup");
            case 4: return head.screen;
            case 5: return head.panel;
            case 6: return union("popup");
            default: return null;
        }
    }

    private void buildTour() {
        String all = getContext().getString(TOUR[tour]);
        int nl = all.indexOf('\n');
        String t = nl < 0 ? all : all.substring(0, nl);
        String b = nl < 0 ? " " : all.substring(nl + 1);
        int bw = (int) (Math.min(w - dp(32), dp(345)) - dp(38));
        if (bw < 100) return;
        tpTitle.setTextSize(dp(17));
        tpTitle.setColor(Theme.primary());
        tpBody.setTextSize(dp(14));
        tpBody.setColor(Theme.TEXT_MAIN);
        tourTitle = StaticLayout.Builder.obtain(t, 0, t.length(), tpTitle, bw)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        tourBody = StaticLayout.Builder.obtain(b, 0, b.length(), tpBody, bw)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.15f).build();
    }

    private void drawTour(Canvas c) {
        if (tourTitle == null || tourBody == null) return;
        RectF t = tourTarget();
        float m = dp(16), pad = dp(18);
        path.reset();
        path.addRect(0, 0, w, h, Path.Direction.CW);
        path.setFillType(Path.FillType.EVEN_ODD);
        if (t != null) {
            tmp.set(ox + t.left * s - dp(10), oy + t.top * s - dp(10),
                    ox + t.right * s + dp(10), oy + t.bottom * s + dp(10));
            path.addRoundRect(tmp, dp(24), dp(24), Path.Direction.CW);
        }
        Q.setStyle(Paint.Style.FILL);
        Q.setColor(0xd8040811);
        c.drawPath(path, Q);

        if (t != null) {
            S.setStyle(Paint.Style.STROKE);
            S.setStrokeWidth(dp(2.5f));
            int pulse = (int) (145 + 110 * Math.sin(System.currentTimeMillis() / 220.0));
            S.setColor(Theme.alpha(Theme.primary(), pulse));
            c.drawRoundRect(tmp, dp(24), dp(24), S);
        }

        float bw = Math.min(w - 2 * m, dp(345));
        float bh = pad * 2 + tourTitle.getHeight() + dp(8) + tourBody.getHeight() + dp(56);
        float bx = (w - bw) / 2f;
        float by;
        if (t == null) by = (h - bh) / 2f;
        else if (tmp.bottom + dp(14) + bh < h - m) by = tmp.bottom + dp(14);
        else by = Math.max(m, tmp.top - dp(14) - bh);

        bubble.set(bx, by, bx + bw, by + bh);
        Q.setColor(0xf40b1628);
        c.drawRoundRect(bubble, dp(22), dp(22), Q);
        S.setStrokeWidth(dp(1.8f));
        S.setColor(Theme.alpha(Theme.primary(), 185));
        c.drawRoundRect(bubble, dp(22), dp(22), S);

        c.save();
        c.translate(bx + pad, by + pad);
        tourTitle.draw(c);
        c.translate(0, tourTitle.getHeight() + dp(8));
        tourBody.draw(c);
        c.restore();

        float ry = by + bh - dp(16) - dp(36);
        skipBtn.set(bx + pad - dp(6), ry, bx + pad + dp(80), ry + dp(36));
        nextBtn.set(bx + bw - pad - dp(108), ry, bx + bw - pad + dp(6), ry + dp(36));

        tpUi.setTextSize(dp(13));
        tpUi.setColor(Theme.TEXT_SUB);
        tpUi.setTextAlign(Paint.Align.CENTER);
        c.drawText(getContext().getString(R.string.tour_skip), skipBtn.centerX(), skipBtn.centerY() + dp(5), tpUi);
        c.drawText((tour + 1) + "/" + TOUR.length, bubble.centerX(), skipBtn.centerY() + dp(5), tpUi);

        Q.setShader(Theme.aquaPillGrad(nextBtn.top, nextBtn.bottom, Theme.primary()));
        c.drawRoundRect(nextBtn, dp(18), dp(18), Q);
        Q.setShader(null);
        tpUi.setColor(0xff04101d);
        c.drawText(getContext().getString(tour == TOUR.length - 1 ? R.string.tour_done : R.string.tour_next),
                nextBtn.centerX(), nextBtn.centerY() + dp(5), tpUi);
    }

    private boolean tourTouch(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            if (nextBtn.contains(x, y)) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (tour >= TOUR.length - 1) endTour();
                else { tour++; buildTour(); invalidate(); }
            } else if (skipBtn.contains(x, y)) {
                endTour();
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ Touch handling
    private void pressButton(PlayerService sv, String id) {
        switch (id) {
            case "prev": sv.prev(); break;
            case "play": sv.toggle(); break;
            case "next": sv.next(); break;
            case "list":
                console.mode = (console.mode == 0) ? 3 : 0;
                console.rebuild(sv, false);
                break;
            case "eq": console.mode = 1; break;
            case "fx": console.mode = 2; break;
            case "shuffle": sv.toggleShuffle(); break;
            case "repeat": sv.cycleRepeat(); break;
            case "sleep": sv.cycleSleep(); break;
            case "popup": sv.cyclePopup(); break;
        }
    }

    private void visorTap(PlayerService sv, float x, float y) {
        if (visor.hitTour(x, y)) { startTour(); return; }
        if (visor.hitLyrics(x, y)) {
            if (console.mode == 4) {
                console.mode = 0;
                console.rebuild(sv, false);
            } else {
                console.mode = 4;
                console.scroll = 0f;
                sv.loadLyricsForCurrent();
            }
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
        if (visor.hitHeart(x, y)) {
            sv.toggleFav(sv.current());
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTap < 310) {
            sv.toggle();
            lastTap = 0;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }
        lastTap = now;
        if (visor.hitVizOrArt(x, y)) {
            vizMode = (vizMode + 1) % VisorRenderer.VIZ_COUNT;
            Prefs.vizMode(getContext(), vizMode);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        PlayerService sv = PlayerService.I;
        if (sv == null) return true;
        if (tour >= 0) return tourTouch(e);

        float x = (e.getX() - ox) / s;
        float y = (e.getY() - oy) / s;
        RectF scr = head.screen;
        RectF pnl = head.panel;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sc.forceFinished(true);
                moved = false;
                downX = x; downY = y;
                seeking = false; listTouch = false; hdrTap = false; swipe = false;
                console.eqBand = -1; console.fxIdx = -1;
                down = head.hit(x, y);
                if (down != null) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                } else if (scr != null && scr.contains(x, y)) {
                    if (visor.hitSeek(x, y)) {
                        seeking = true;
                        float pad = scr.width() * 0.052f;
                        sv.seekFrac((x - scr.left - pad) / (scr.width() - 2f * pad));
                    } else {
                        swipe = true;
                    }
                } else if (pnl != null && pnl.contains(x, y)) {
                    if (console.mode == 0 || console.mode == 3 || console.mode == 4) {
                        if (y < console.listTop()) hdrTap = true;
                        else listTouch = true;
                    } else if (console.mode == 1) {
                        console.eqTouch(sv, x, y, true);
                    } else {
                        console.fxTouch(sv, gyro, x, y, true);
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(x - downX, y - downY) > 16f) moved = true;
                if (seeking && scr != null) {
                    float pad = scr.width() * 0.052f;
                    sv.seekFrac((x - scr.left - pad) / (scr.width() - 2f * pad));
                } else if (console.mode == 1 && console.eqBand >= 0) {
                    console.eqTouch(sv, x, y, false);
                } else if (console.mode == 2 && console.fxIdx >= 0) {
                    console.fxTouch(sv, gyro, x, y, false);
                }
                break;

            case MotionEvent.ACTION_UP:
                if (down != null && head.hit(x, y) == down) {
                    pressButton(sv, down.id);
                } else if (swipe) {
                    float dx = x - downX, dy = y - downY;
                    if (Math.abs(dx) > 110f && Math.abs(dy) < 110f) {
                        if (dx < 0) sv.next(); else sv.prev();
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    } else if (!moved) {
                        visorTap(sv, x, y);
                    }
                } else if (hdrTap && !moved) {
                    console.headerTap(sv, x, y);
                }
                down = null; seeking = false; console.eqBand = -1; console.fxIdx = -1;
                break;

            case MotionEvent.ACTION_CANCEL:
                down = null; seeking = false; console.eqBand = -1; console.fxIdx = -1;
                break;
        }

        if (listTouch) gd.onTouchEvent(e);
        invalidate();
        return true;
    }
}
