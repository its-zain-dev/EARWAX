package app.earwax;

import android.content.Context;
import android.graphics.*;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;
import java.util.List;

/**
 * The main screen: a chrome head (3-D artwork) whose forehead is the screen and whose ears are the
 * buttons, sitting on a body plate that holds the library list / equalizer / FX panel.
 * Everything is laid out in the artwork's own 1080-unit design space and scaled to the phone.
 */
final class StageView extends View {
    private static final float PY = 1050, TOT = 2100, ROW = 64, HDR = 72;
    private final Skin head, plate;
    private final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG), P = Skin.P;
    private final RectF pr;
    private Bitmap bg;
    private final OverScroller sc;
    private final GestureDetector gd;
    private float s = 1, ox, oy, scroll;
    private int mode;                       // 0 library, 1 equalizer, 2 FX
    private Skin.Btn down;
    private boolean seeking, listTouch;
    private int eqBand = -1, fxIdx = -1;
    private final Runnable tick = new Runnable() {
        public void run() { invalidate(); PlayerService v = PlayerService.I; postDelayed(this, v != null && v.pl.isPlaying() ? 45 : 250); }
    };

    StageView(Context c) {
        super(c);
        head = Skin.load(c, "head"); plate = Skin.load(c, "plate");
        pr = new RectF(plate.panel.left, plate.panel.top + PY, plate.panel.right, plate.panel.bottom + PY);
        sc = new OverScroller(c);
        try (java.io.InputStream in = c.getAssets().open("skins/bg.jpg")) { bg = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) { scroll = clampScroll(scroll + dy / s); invalidate(); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) { sc.fling(0, (int) scroll, 0, (int) (-vy / s), 0, 0, 0, (int) maxScroll(), 0, 0); postInvalidateOnAnimation(); return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { tapList((e.getX() - ox) / s, (e.getY() - oy) / s); return true; }
        });
        setBackgroundColor(0xff10141c);
    }

    void refresh() { invalidate(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { s = Math.min(w / 1080f, h / TOT); ox = (w - 1080 * s) / 2; oy = (h - TOT * s) / 2; }
    @Override public void computeScroll() { if (sc.computeScrollOffset()) { scroll = sc.getCurrY(); postInvalidateOnAnimation(); } }

    private float maxScroll() { PlayerService v = PlayerService.I; int n = v == null ? 0 : v.lib.size(); return Math.max(0, n * ROW - (pr.height() - HDR - 24)); }
    private float clampScroll(float v) { return Math.max(0, Math.min(maxScroll(), v)); }

    // ------------------------------------------------------------------ drawing
    /** Light haze: slow drifting mist behind the hardware, a faint veil in front of it. */
    private void fog(Canvas c, int w, int h) {
        long t = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            float ph = t / 9000f + i * 2.1f, cx = w * (0.5f + 0.35f * (float) Math.sin(ph)), cy = h * (0.22f + 0.28f * i + 0.05f * (float) Math.cos(ph * 1.3f)), r = w * (0.75f + 0.15f * i);
            Q.setStyle(Paint.Style.FILL); Q.setShader(new RadialGradient(cx, cy, r, 0x2499b8d8, 0x0099b8d8, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, h, Q);
        }
        Q.setShader(null);
    }

    private void mist(Canvas c, int w, int h) {
        Q.setStyle(Paint.Style.FILL);
        Q.setShader(new LinearGradient(0, h * 0.6f, 0, h, 0x00a8c4e0, 0x30a8c4e0, Shader.TileMode.CLAMP)); c.drawRect(0, h * 0.6f, w, h, Q);
        Q.setShader(new LinearGradient(0, 0, 0, h * 0.12f, 0x28a8c4e0, 0x00a8c4e0, Shader.TileMode.CLAMP)); c.drawRect(0, 0, w, h * 0.12f, Q);
        Q.setShader(null);
    }

    @Override protected void onDraw(Canvas c) {
        if (bg != null) c.drawBitmap(bg, null, new Rect(0, 0, getWidth(), getHeight()), P);
        fog(c, getWidth(), getHeight());
        c.save(); drawStage(c); c.restore();
        mist(c, getWidth(), getHeight());
    }

    private void drawStage(Canvas c) {
        PlayerService sv = PlayerService.I; if (sv == null) return;
        Hud x = sv.hud();
        c.translate(ox, oy); c.scale(s, s);
        c.drawBitmap(plate.bmp, 0, PY, P);
        c.drawBitmap(head.bmp, 0, 0, P);
        drawScreen(c, x);
        c.save(); c.clipRect(pr);
        if (mode == 0) drawList(c, sv); else if (mode == 1) drawEq(c, sv); else drawFx(c, sv);
        c.restore();
        for (Skin.Btn b : head.btns) {
            boolean on = (b.id.equals("list") && mode == 0) || (b.id.equals("eq") && mode == 1) || (b.id.equals("fx") && mode == 2)
                    || (b.id.equals("shuffle") && sv.shuffle()) || (b.id.equals("repeat") && sv.repeat() != 0)
                    || (b.id.equals("sleep") && sv.sleepMin > 0) || (b.id.equals("popup") && sv.pv != null);
            boolean cone = "prev,play,next,list,eq,fx".contains(b.id);
            Skin.glyph(c, b.id, b.cx, b.cy, b.r * (cone ? 1.0f : 0.62f), x.playing, cone ? Skin.LED : 0xff1a2a55);
            if (on || down == b) {
                Q.setStyle(Paint.Style.STROKE); Q.setStrokeWidth(down == b ? 9 : 6); Q.setColor(down == b ? 0xffffffff : 0xcc7fe6ff);
                c.drawCircle(b.cx, b.cy, b.r * (cone ? 0.9f : 0.78f), Q);
            }
        }
    }

    private void drawScreen(Canvas c, Hud x) {
        RectF r = head.screen; Path clip = new Path(); clip.addRoundRect(r, 30, 30, Path.Direction.CW);
        c.save(); c.clipPath(clip);
        Q.setStyle(Paint.Style.FILL); Q.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xff0b1a14, 0xff02080a, Shader.TileMode.CLAMP)); c.drawRect(r, Q); Q.setShader(null);
        int n = 32; float bw = (r.width() - 56) / n, top = r.top + 26, bot = r.top + r.height() * 0.46f, seg = (bot - top) / 16;
        for (int i = 0; i < n; i++) {
            int lit = (int) (x.bars[i] * 16);
            for (int k = 0; k < 16; k++) {
                Q.setColor(k >= lit ? 0xff0e2218 : k < 9 ? 0xff3be37a : k < 13 ? 0xffe8e04a : 0xffff4a3a);
                if (k < lit) { int gc = Q.getColor(); Q.setColor((gc & 0xffffff) | 0x38000000); c.drawRect(r.left + 24 + i * bw, bot - (k + 1) * seg - 2, r.left + 32 + (i + 1) * bw, bot - k * seg + 4, Q); Q.setColor(gc); }
                c.drawRect(r.left + 28 + i * bw + 2, bot - (k + 1) * seg + 2, r.left + 28 + (i + 1) * bw - 2, bot - k * seg, Q);
            }
        }
        float lx = r.left + 30, room = r.width() - 60;
        Skin.marquee(c, x.title.isEmpty() ? "EARWAX" : x.title, lx, r.top + r.height() * 0.60f, 34, Skin.LED, room);
        Skin.text(c, Skin.fit(x.artist, 26, room), lx, r.top + r.height() * 0.70f, 26, Skin.DIM, Paint.Align.LEFT);
        Skin.text(c, Skin.mmss(x.pos), lx, r.top + r.height() * 0.83f, 30, Skin.LED, Paint.Align.LEFT);
        Skin.text(c, Skin.mmss(x.dur), r.right - 30, r.top + r.height() * 0.83f, 30, Skin.DIM, Paint.Align.RIGHT);
        float gy = r.bottom - 22, f = x.dur > 0 ? Math.min(1, x.pos / (float) x.dur) : 0;
        Q.setStrokeCap(Paint.Cap.ROUND); Q.setStrokeWidth(10); Q.setStyle(Paint.Style.STROKE);
        Q.setColor(0xff17402a); c.drawLine(lx, gy, r.right - 30, gy, Q);
        Q.setColor(Skin.LED); c.drawLine(lx, gy, lx + room * f, gy, Q);
        Q.setStyle(Paint.Style.FILL); Q.setColor(0xffffffff); c.drawCircle(lx + room * f, gy, 14, Q);
        c.restore();
    }

    private void drawList(Canvas c, PlayerService sv) {
        List<Track> L = sv.lib;
        Skin.text(c, "LIBRARY  " + L.size() + " TRACKS", pr.left + 30, pr.top + 50, 34, Skin.DIM, Paint.Align.LEFT);
        if (L.isEmpty()) { Skin.text(c, "NO MUSIC FOUND - ALLOW STORAGE ACCESS", pr.centerX(), pr.centerY(), 30, Skin.LED, Paint.Align.CENTER); return; }
        float y0 = pr.top + HDR; int cur = sv.pl.getCurrentMediaItemIndex(), first = Math.max(0, (int) (scroll / ROW));
        c.save(); c.clipRect(pr.left + 10, y0, pr.right - 10, pr.bottom - 16);
        Q.setStyle(Paint.Style.FILL);
        for (int i = first; i < L.size(); i++) {
            float y = y0 + i * ROW - scroll; if (y > pr.bottom) break;
            Track t = L.get(i);
            if (i == cur) { Q.setColor(0x4440c070); c.drawRoundRect(pr.left + 16, y + 4, pr.right - 16, y + ROW - 4, 14, 14, Q); }
            Skin.text(c, String.format("%03d", i + 1), pr.left + 30, y + 42, 28, Skin.DIM, Paint.Align.LEFT);
            Skin.text(c, Skin.fit(t.label(), 32, pr.width() - 290), pr.left + 118, y + 42, 32, i == cur ? 0xffffffff : Skin.LED, Paint.Align.LEFT);
            Skin.text(c, Skin.mmss(t.dur), pr.right - 30, y + 42, 28, Skin.DIM, Paint.Align.RIGHT);
        }
        c.restore();
    }

    // ---- equalizer geometry
    private float eqX0() { return pr.left + 30; }
    private float eqCol() { return (pr.width() - 60) / 11f; }
    private float eqTop() { return pr.top + 90; }
    private float eqBot() { return pr.top + 410; }
    private RectF chip(int i) { float w = (pr.width() - 60 - 42) / 4f, x = pr.left + 30 + (i % 4) * (w + 14), y = pr.top + 450 + (i / 4) * 76; return new RectF(x, y, x + w, y + 62); }

    private void drawEq(Canvas c, PlayerService sv) {
        Skin.text(c, "EQUALIZER  " + sv.chanName(), pr.left + 30, pr.top + 50, 34, Skin.DIM, Paint.Align.LEFT);
        String[] lab = {"PRE", "60", "120", "250", "500", "1K", "2K", "4K", "8K", "12K", "16K"};
        float mid = (eqTop() + eqBot()) / 2, half = (eqBot() - eqTop()) / 2;
        for (int i = 0; i < 11; i++) {
            float cx = eqX0() + (i + .5f) * eqCol(), v = sv.eq(i), ky = mid - v / 15f * half;
            Q.setStyle(Paint.Style.FILL); Q.setColor(0xff06140d); c.drawRoundRect(cx - 13, eqTop(), cx + 13, eqBot(), 13, 13, Q);
            Q.setColor(v >= 0 ? 0xff3be37a : 0xffe8884a); c.drawRoundRect(cx - 9, Math.min(mid, ky), cx + 9, Math.max(mid, ky), 9, 9, Q);
            Q.setColor(0xffdfe6ee); c.drawRoundRect(cx - 24, ky - 12, cx + 24, ky + 12, 10, 10, Q);
            Skin.text(c, lab[i], cx, eqBot() + 44, 24, Skin.DIM, Paint.Align.CENTER);
        }
        for (int i = 0; i < 8; i++) {
            RectF r = chip(i); Q.setStyle(Paint.Style.FILL); Q.setColor(0xff0e2a1c); c.drawRoundRect(r, 18, 18, Q);
            Q.setStyle(Paint.Style.STROKE); Q.setStrokeWidth(3); Q.setColor(Skin.DIM); c.drawRoundRect(r, 18, 18, Q);
            Skin.text(c, PlayerService.PRESET_NAMES[i], r.centerX(), r.centerY() + 10, 28, Skin.LED, Paint.Align.CENTER);
        }
    }

    // ---- FX geometry
    private static final String[] FX = {"PITCH", "SPEED", "BASS", "TREBLE", "VOLUME"};
    private float fxY(int i) { return pr.top + 120 + i * 92; }
    private float fxVal(PlayerService v, int i) { switch (i) { case 0: return (v.pitch + 6) / 12; case 1: return (v.speed - .5f) / 1.5f; case 2: return (v.dsp.bass + 10) / 20; case 3: return (v.dsp.treble + 10) / 20; default: return v.vol; } }
    private String fxTxt(PlayerService v, int i) { switch (i) { case 0: return String.format("%+.1f st", v.pitch); case 1: return String.format("%.2fx", v.speed); case 2: return String.format("%+.0f dB", v.dsp.bass); case 3: return String.format("%+.0f dB", v.dsp.treble); default: return Math.round(v.vol * 100) + "%"; } }
    private RectF fxBtn(int i) { float w = (pr.width() - 90) / 2f, x = pr.left + 30 + i * (w + 30); return new RectF(x, pr.bottom - 110, x + w, pr.bottom - 36); }

    private void drawFx(Canvas c, PlayerService sv) {
        Skin.text(c, "SOUND FX", pr.left + 30, pr.top + 50, 34, Skin.DIM, Paint.Align.LEFT);
        for (int i = 0; i < 5; i++) {
            float y = fxY(i), x0 = pr.left + 250, x1 = pr.right - 40, f = fxVal(sv, i);
            Skin.text(c, FX[i], pr.left + 30, y - 6, 28, Skin.LED, Paint.Align.LEFT);
            Skin.text(c, fxTxt(sv, i), pr.left + 30, y + 28, 26, Skin.DIM, Paint.Align.LEFT);
            Q.setStyle(Paint.Style.STROKE); Q.setStrokeCap(Paint.Cap.ROUND); Q.setStrokeWidth(18);
            Q.setColor(0xff06140d); c.drawLine(x0, y, x1, y, Q); Q.setColor(0xff3be37a); c.drawLine(x0, y, x0 + (x1 - x0) * f, y, Q);
            Q.setStyle(Paint.Style.FILL); Q.setColor(0xffdfe6ee); c.drawCircle(x0 + (x1 - x0) * f, y, 22, Q);
        }
        String[] t = {sv.chanName(), "SLEEP " + (sv.sleepMin == 0 ? "OFF" : sv.sleepMin + "m")};
        for (int i = 0; i < 2; i++) {
            RectF r = fxBtn(i); Q.setStyle(Paint.Style.FILL); Q.setColor(0xff0e2a1c); c.drawRoundRect(r, 22, 22, Q);
            Q.setStyle(Paint.Style.STROKE); Q.setStrokeWidth(3); Q.setColor(Skin.DIM); c.drawRoundRect(r, 22, 22, Q);
            Skin.text(c, t[i], r.centerX(), r.centerY() + 10, 30, Skin.LED, Paint.Align.CENTER);
        }
    }

    // ------------------------------------------------------------------ touch
    private boolean inPanel(float x, float y) { return pr.contains(x, y); }

    private void tapList(float x, float y) {
        PlayerService sv = PlayerService.I; if (sv == null || y < pr.top + HDR || !inPanel(x, y)) return;
        sv.playIndex((int) ((y - (pr.top + HDR) + scroll) / ROW));
    }

    private void eqTouch(PlayerService sv, float x, float y, boolean first) {
        if (first) {
            for (int i = 0; i < 8; i++) if (chip(i).contains(x, y)) { sv.preset(i); return; }
            if (y < eqTop() - 20 || y > eqBot() + 20) return;
            eqBand = Math.max(0, Math.min(10, (int) ((x - eqX0()) / eqCol())));
        }
        if (eqBand < 0) return;
        float mid = (eqTop() + eqBot()) / 2, half = (eqBot() - eqTop()) / 2;
        sv.setEq(eqBand, (mid - y) / half * 15f);
    }

    private void fxTouch(PlayerService sv, float x, float y, boolean first) {
        if (first) {
            fxIdx = -1;
            for (int i = 0; i < 5; i++) if (Math.abs(y - fxY(i)) < 44) fxIdx = i;
            if (fxBtn(0).contains(x, y)) { sv.cycleChan(); return; }
            if (fxBtn(1).contains(x, y)) { sv.cycleSleep(); return; }
        }
        if (fxIdx < 0) return;
        float f = Math.max(0, Math.min(1, (x - (pr.left + 250)) / (pr.right - 40 - (pr.left + 250))));
        switch (fxIdx) { case 0: sv.setPitch(f * 12 - 6); break; case 1: sv.setSpeed(.5f + f * 1.5f); break; case 2: sv.setBass(f * 20 - 10); break; case 3: sv.setTreble(f * 20 - 10); break; default: sv.setVol(f); }
    }

    private void press(PlayerService sv, String id) {
        switch (id) {
            case "prev": sv.prev(); break; case "play": sv.toggle(); break; case "next": sv.next(); break;
            case "list": mode = 0; break; case "eq": mode = 1; break; case "fx": mode = 2; break;
            case "shuffle": sv.toggleShuffle(); break; case "repeat": sv.cycleRepeat(); break;
            case "sleep": sv.cycleSleep(); break; case "popup": sv.cyclePopup(); break;
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        PlayerService sv = PlayerService.I; if (sv == null) return true;
        float x = (e.getX() - ox) / s, y = (e.getY() - oy) / s;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sc.forceFinished(true); down = head.hit(x, y); seeking = false; listTouch = false; eqBand = -1; fxIdx = -1;
                RectF r = head.screen;
                if (down == null && r.contains(x, y) && y > r.bottom - r.height() * 0.3f) { seeking = true; sv.seekFrac((x - r.left - 30) / (r.width() - 60)); }
                else if (down == null && inPanel(x, y)) {
                    if (mode == 0) listTouch = true; else if (mode == 1) eqTouch(sv, x, y, true); else fxTouch(sv, x, y, true);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (seeking) sv.seekFrac((x - head.screen.left - 30) / (head.screen.width() - 60));
                else if (mode == 1 && eqBand >= 0) eqTouch(sv, x, y, false);
                else if (mode == 2 && fxIdx >= 0) fxTouch(sv, x, y, false);
                break;
            case MotionEvent.ACTION_UP:
                if (down != null && head.hit(x, y) == down) press(sv, down.id);
                down = null; seeking = false; eqBand = -1; fxIdx = -1;
                break;
        }
        if (listTouch) gd.onTouchEvent(e);
        invalidate();
        return true;
    }
}
