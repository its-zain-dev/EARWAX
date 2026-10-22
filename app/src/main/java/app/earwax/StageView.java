package app.earwax;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.*;
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
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.OverScroller;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;

/**
 * The main screen: one chrome bust (head + neck + chest, lit as a single object). The forehead is the screen, the ears are
 * speaker pods that vibrate with the bass, the chest holds the library / equalizer / FX panel.
 * Everything is laid out in the artwork's own 1080 x 2100 design space and scaled into the safe area of the phone.
 */
final class StageView extends View {
    private static final float ROW = 64, TAB_H = 64, SUB_H = 56, PADX = 44, PADY = 34, DW = 1080, DH = 2100;
    private static final String[] TABS = {"ALL", "ALBUMS", "ARTISTS", "FAVS", "RECENT", "RADIO"};
    private static final String[] FX = {"PITCH", "SPEED", "BASS", "TREBLE", "VOLUME"};
    private static final int[] TOUR = {R.string.tour_0, R.string.tour_1, R.string.tour_2, R.string.tour_3, R.string.tour_4, R.string.tour_5, R.string.tour_6};

    private static final class Row { String a = "", b = "", grp; Track t; int ti; boolean group; }

    private final Skin head;
    private final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG), P = Skin.P;
    private final Path path = new Path();
    private final RectF pr, pc, tmp = new RectF();
    private final OverScroller sc;
    private final GestureDetector gd;
    private float shake;      // current ear-pod shake in design px (set by drawEars, also moves the cones' glyphs)
    private final Shader scrSh;
    private Bitmap bg, logo, bgBake;
    private float s = 1, ox, oy, scroll;
    private int insL, insT, insR, insB, w, h;
    private final long introT = System.currentTimeMillis();
    private boolean registered, tourChecked;

    // interaction state
    private int mode;                        // 0 library, 1 equalizer, 2 FX
    private Skin.Btn down;
    private boolean seeking, listTouch, hdrTap, swipe, moved;
    private float downX, downY;
    private long lastTap;
    private int eqBand = -1, fxIdx = -1;

    // library view state
    private int tab; private String grp; private String query = "";
    private ArrayList<Row> rows = new ArrayList<>(); private ArrayList<Track> vt = new ArrayList<>(); private String vkey = "";
    private int sLib = -1, sFav = -1, sRec = -1;
    private ArrayList<Track> radioRes; private String radioMsg = ""; private boolean radioLoading;
    private final ArrayList<Track> saved;

    // guided tour
    private int tour = -1; private StaticLayout tourTitle, tourBody;
    private final RectF skipBtn = new RectF(), nextBtn = new RectF(), bubble = new RectF();
    private final TextPaint tpTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG), tpBody = new TextPaint(Paint.ANTI_ALIAS_FLAG), tpUi = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Runnable tick = new Runnable() {
        public void run() {
            invalidate(); PlayerService v = PlayerService.I;
            boolean busy = tour >= 0 || (v != null && v.pl.isPlaying()) || !sc.isFinished() || System.currentTimeMillis() - introT < 2000;
            postDelayed(this, busy ? 33 : 250);
        }
    };

    StageView(Context c) {
        super(c);
        head = Skin.load(c, "bust", Math.min(1080, c.getResources().getDisplayMetrics().widthPixels));
        pr = new RectF(head.panel); pc = new RectF(pr.left + PADX, pr.top + PADY, pr.right - PADX, pr.bottom - PADY);
        scrSh = new LinearGradient(0, head.screen.top, 0, head.screen.bottom, 0xff0b1a14, 0xff02080a, Shader.TileMode.CLAMP);
        sc = new OverScroller(c); saved = Prefs.streams(c);
        try (java.io.InputStream in = c.getAssets().open("skins/bg.jpg")) { bg = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
        try (java.io.InputStream in = c.getAssets().open("skins/logo.png")) { logo = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
        tpTitle.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); tpBody.setTypeface(Typeface.DEFAULT); tpUi.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) { scroll = clampScroll(scroll + dy / s); invalidate(); return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) { sc.fling(0, (int) scroll, 0, (int) (-vy / s), 0, 0, 0, (int) maxScroll(), 0, 0); postInvalidateOnAnimation(); return true; }
            @Override public boolean onSingleTapUp(MotionEvent e) { tapList((e.getX() - ox) / s, (e.getY() - oy) / s); return true; }
            @Override public void onLongPress(MotionEvent e) { longList((e.getX() - ox) / s, (e.getY() - oy) / s); }
        });
        setBackgroundColor(0xff10141c);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // ------------------------------------------------------------------ lifecycle
    private void watch(boolean on) {
        if (on == registered) return; registered = on;
        PlayerService.with(() -> PlayerService.I.viewer(on));       // the FFT only runs while a screen that shows it is visible
        removeCallbacks(tick); if (on) post(tick);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (getWindowVisibility() == VISIBLE) watch(true); }
    @Override protected void onDetachedFromWindow() { watch(false); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int v) { super.onWindowVisibilityChanged(v); watch(v == VISIBLE); }

    @Override public WindowInsets onApplyWindowInsets(WindowInsets in) {
        insL = in.getSystemWindowInsetLeft(); insT = in.getSystemWindowInsetTop(); insR = in.getSystemWindowInsetRight(); insB = in.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = in.getDisplayCutout();
            if (dc != null) { insL = Math.max(insL, dc.getSafeInsetLeft()); insT = Math.max(insT, dc.getSafeInsetTop()); insR = Math.max(insR, dc.getSafeInsetRight()); insB = Math.max(insB, dc.getSafeInsetBottom()); }
        }
        layoutStage(); invalidate();
        return in;
    }

    @Override protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw; h = nh; layoutStage();
        bakeBackdrop();
        if (tour >= 0) buildTour();
    }

    /** Fit the artwork into the safe area (system bars + cutout) with a margin, so nothing touches the screen edge. */
    private void layoutStage() {
        float pad = dp(12), aw = w - insL - insR - 2 * pad, ah = h - insT - insB - 2 * pad;
        if (aw <= 0 || ah <= 0) return;
        s = Math.min(aw / DW, ah / DH);
        ox = insL + pad + (aw - DW * s) / 2f; oy = insT + pad + (ah - DH * s) / 2f;
    }

    @Override public void computeScroll() { if (sc.computeScrollOffset()) { scroll = sc.getCurrY(); postInvalidateOnAnimation(); } }

    // ------------------------------------------------------------------ library model
    private float listTop() { return pc.top + TAB_H + SUB_H; }
    private float listH() { return pc.bottom - listTop(); }
    private float maxScroll() { return Math.max(0, rows.size() * ROW - listH()); }
    private float clampScroll(float v) { return Math.max(0, Math.min(maxScroll(), v)); }

    private static boolean match(Track t, String q) {
        return q.isEmpty() || t.title.toLowerCase(Locale.ROOT).contains(q) || t.artist.toLowerCase(Locale.ROOT).contains(q) || t.album.toLowerCase(Locale.ROOT).contains(q);
    }
    private static String gkey(Track t, boolean albums) { String k = albums ? t.album : t.artist; return k.isEmpty() ? "UNKNOWN" : k; }
    private static String n3(int i) { return i < 10 ? "00" + i : i < 100 ? "0" + i : Integer.toString(i); }

    private void rebuild(PlayerService sv, boolean keepScroll) {
        rows = new ArrayList<>(); vt = new ArrayList<>(); String q = query.toLowerCase(Locale.ROOT);
        sLib = sv.libVer; sFav = sv.favVer; sRec = sv.recVer;
        switch (tab) {
            case 0: for (Track t : sv.lib) if (match(t, q)) vt.add(t); vkey = "all|" + q; break;
            case 1: case 2: {
                boolean al = tab == 1;
                if (grp == null) {
                    HashMap<String, int[]> cnt = new HashMap<>();
                    for (Track t : sv.lib) if (match(t, q)) { String k = gkey(t, al); int[] n = cnt.get(k); if (n == null) { n = new int[1]; cnt.put(k, n); } n[0]++; }
                    ArrayList<String> keys = new ArrayList<>(cnt.keySet()); Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
                    for (String k : keys) { Row r = new Row(); r.group = true; r.grp = k; r.a = k; r.b = cnt.get(k)[0] + " TRK"; rows.add(r); }
                } else {
                    for (Track t : sv.lib) if (gkey(t, al).equals(grp) && match(t, q)) vt.add(t);
                    vkey = (al ? "alb|" : "art|") + grp + "|" + q;
                }
                break;
            }
            case 3: for (Track t : sv.lib) if (sv.fav(t) && match(t, q)) vt.add(t); vkey = "fav|" + sv.favVer + "|" + q; break;
            case 4: {
                HashMap<Long, Track> byId = new HashMap<>(); for (Track t : sv.lib) byId.put(t.id, t);
                for (Long id : sv.recents) { Track t = byId.get(id); if (t != null && match(t, q)) vt.add(t); }
                vkey = "rec|" + sv.recVer + "|" + q; break;
            }
            default:
                vt.addAll(saved);
                if (radioRes != null) for (Track t : radioRes) if (!isSaved(t)) vt.add(t);
                vkey = "radio";
        }
        for (int i = 0; i < vt.size(); i++) { Row r = new Row(); r.t = vt.get(i); r.ti = i; rows.add(r); }
        scroll = keepScroll ? clampScroll(scroll) : 0;
    }

    private boolean isSaved(Track t) { for (Track k : saved) if (k.url.equals(t.url)) return true; return false; }

    private void selectTab(PlayerService sv, int i) {
        tab = i; grp = null; if (i != 5) query = ""; scroll = 0; rebuild(sv, false);
        if (i == 5 && radioRes == null && !radioLoading) loadTop();
    }

    private void radioDone(java.util.List<Track> l, String e) {
        radioLoading = false; radioRes = new ArrayList<>(l); radioMsg = e == null ? "" : e;
        PlayerService v = PlayerService.I; if (v != null && tab == 5) rebuild(v, false); invalidate();
    }
    private void loadTop() { radioLoading = true; radioMsg = "LOADING..."; Radio.top(this::radioDone); }

    // ------------------------------------------------------------------ drawing
    @Override protected void onDraw(Canvas c) {
        long now = System.currentTimeMillis();
        Skin.resetPaints(); Q.setShader(null); Q.setColorFilter(null); Q.setXfermode(null); Q.setAlpha(255); Q.clearShadowLayer(); Q.setStyle(Paint.Style.FILL);
        if (bgBake != null) { tmp.set(0, 0, w, h); c.drawBitmap(bgBake, null, tmp, P); }
        c.save(); drawStage(c, now); c.restore();
        if (now - introT < 1800) drawIntro(c, now - introT);
        else if (!tourChecked) { tourChecked = true; if (!Prefs.tourSeen(getContext())) startTour(); }
        if (tour >= 0) drawTour(c);
    }

    /** Draw the startup splash and fade it out over the initial stage. */
    private void drawIntro(Canvas c, long t) {
        float a = t < 1000 ? 1f : Math.max(0f, 1f - (t - 1000) / 800f);
        if (a <= 0f) return;

        Q.setStyle(Paint.Style.FILL);
        Q.setShader(null);
        Q.setColorFilter(null);
        Q.setColor(Color.argb(Math.round(235f * a), 10, 15, 24));
        c.drawRect(0, 0, w, h, Q);

        if (logo != null && logo.getWidth() > 0) {
            float lw = w * 0.74f;
            float lh = lw * logo.getHeight() / logo.getWidth();
            float k = 0.94f + 0.06f * Math.min(1f, t / 700f);

            tmp.set(
                w / 2f - lw * k / 2f,
                h * 0.42f - lh * k / 2f,
                w / 2f + lw * k / 2f,
                h * 0.42f + lh * k / 2f
            );

            P.setShader(null);
            P.setColorFilter(null);
            P.setAlpha(Math.round(255f * a));
            c.drawBitmap(logo, null, tmp, P);
            P.setAlpha(255);
        }

        tpUi.setColor(Color.argb(Math.round(255f * a), 159, 180, 204));
        tpUi.setTextSize(dp(14));
        tpUi.setTextAlign(Paint.Align.CENTER);
        c.drawText(
            getContext().getString(R.string.tagline),
            w / 2f,
            h * 0.42f + dp(52),
            tpUi
        );
    }

    /**
     * Background + haze, drawn once into a half-size bitmap whenever the view is (re)sized. The haze used to drift with the clock
     * and was repainted at 4-30 fps depending on what was happening, so lights and colours visibly changed whenever a button was
     * pressed or a window was switched. Now it is perfectly still, and it costs one bitmap draw per frame.
     */
    private void bakeBackdrop() {
        if (bgBake != null) bgBake.recycle();
        int bw = Math.max(1, w / 2), bh = Math.max(1, h / 2);
        bgBake = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas k = new Canvas(bgBake); Paint f = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (bg != null) k.drawBitmap(bg, null, new RectF(0, 0, bw, bh), f);
        for (int i = 0; i < 3; i++) {
            float ph = i * 2.1f, cx = bw * (0.5f + 0.35f * (float) Math.sin(ph)), cy = bh * (0.22f + 0.28f * i + 0.05f * (float) Math.cos(ph * 1.3f)), r = bw * (0.75f + 0.15f * i);
            f.setShader(new RadialGradient(cx, cy, r, 0x2499b8d8, 0x0099b8d8, Shader.TileMode.CLAMP)); k.drawCircle(cx, cy, r, f);
        }
        f.setShader(new LinearGradient(0, bh * 0.6f, 0, bh, 0x00a8c4e0, 0x30a8c4e0, Shader.TileMode.CLAMP)); k.drawRect(0, bh * 0.6f, bw, bh, f);
        f.setShader(new LinearGradient(0, 0, 0, bh * 0.12f, 0x28a8c4e0, 0x00a8c4e0, Shader.TileMode.CLAMP)); k.drawRect(0, 0, bw, bh * 0.12f, f);
    }

    /** Which frequency band pumps a cone: bottom = low (woofer), middle = mid, top = high (tweeter). -1 = not a cone. */
    private static int coneBand(String id) {
        switch (id) {
            case "next": case "fx": return 0;
            case "play": case "eq": return 1;
            case "prev": case "list": return 2;
            default: return -1;
        }
    }

    /**
     * Ear pods are rigid: they only shake a hair on strong bass hits. The real motion is in the six speaker cones, each pumping
     * with its own band (see Hud.beat), so the ears move with the song instead of wobbling all the time.
     */
    private void drawEars(Canvas c, Hud x, long now) {
        shake = x.playing ? 2.2f * x.env[0] * ((now / 33 & 1) == 0 ? 1f : -1f) : 0f;
        for (Skin.Ear e : head.ears) { c.save(); c.translate(e.left ? -shake : shake, 0); c.drawBitmap(e.bmp, null, e.r, P); c.restore(); }
        if (head.cone == null) return;
        for (Skin.Btn b : head.btns) {
            int band = coneBand(b.id); if (band < 0) continue;
            float half = head.coneW / 2 * (1f + 0.16f * (x.playing ? x.env[band] : 0f)), dx = b.cx < 540 ? -shake : shake;
            tmp.set(b.cx + dx - half, b.cy - half, b.cx + dx + half, b.cy + half); c.drawBitmap(head.cone, null, tmp, P);
        }
    }

    private void drawStage(Canvas c, long now) {
        PlayerService sv = PlayerService.I;
        c.translate(ox, oy); c.scale(s, s);
        if (sv == null) { c.drawBitmap(head.bmp, null, head.full, P); return; }
        Hud x = sv.hud();
        drawEars(c, x, now);
        c.drawBitmap(head.bmp, null, head.full, P);
        drawScreen(c, x);
        if (sLib != sv.libVer || (tab == 3 && sFav != sv.favVer) || (tab == 4 && sRec != sv.recVer)) rebuild(sv, true);
        c.save(); path.reset(); path.addRoundRect(pr.left + 5, pr.top + 5, pr.right - 5, pr.bottom - 5, 58, 58, Path.Direction.CW); c.clipPath(path);
        if (mode == 0) drawList(c, sv); else if (mode == 1) drawEq(c, sv); else drawFx(c, sv);
        c.restore();
        for (Skin.Btn b : head.btns) {
            boolean cone = b.id.equals("prev") || b.id.equals("play") || b.id.equals("next") || b.id.equals("list") || b.id.equals("eq") || b.id.equals("fx");
            boolean on = (b.id.equals("list") && mode == 0) || (b.id.equals("eq") && mode == 1) || (b.id.equals("fx") && mode == 2)
                    || (b.id.equals("shuffle") && sv.shuffle()) || (b.id.equals("repeat") && sv.repeat() != 0)
                    || (b.id.equals("sleep") && sv.sleepMin > 0) || (b.id.equals("popup") && sv.pv != null);
            int band = coneBand(b.id); float e = band >= 0 && x.playing ? x.env[band] : 0f;
            float bx = cone ? b.cx + (b.cx < 540 ? -shake : shake) : b.cx, press = down == b ? 0.92f : 1f, cr = head.coneW / 2;
            if (cone && e > 0.05f) { Q.setStyle(Paint.Style.FILL); Q.setColor(((int) (55 * e) << 24) | 0x7fd8ff); c.drawCircle(bx, b.cy, cr * 0.95f, Q); }   // soft glow on the hit
            Skin.glyph(c, b.id, bx, b.cy, b.r * (cone ? 1.0f + 0.16f * e : 0.62f) * press, x.playing, cone ? Skin.LED : 0xfffff0ec);
            if (on || down == b) {
                Q.setStyle(Paint.Style.STROKE); Q.setStrokeWidth(down == b ? 5 : 4); Q.setColor(down == b ? 0xaaffffff : 0xb07fe6ff);
                c.drawCircle(bx, b.cy, cone ? cr * 1.08f : b.r * 0.78f, Q);
            }
        }
    }

    private void drawScreen(Canvas c, Hud x) {
        RectF r = head.screen;
        c.save(); path.reset(); path.addRoundRect(r.left + 4, r.top + 4, r.right - 4, r.bottom - 4, 26, 26, Path.Direction.CW); c.clipPath(path);
        Q.setStyle(Paint.Style.FILL); Q.setShader(scrSh); c.drawRect(r, Q); Q.setShader(null);
        float l = r.left + 26, rr = r.right - 26, room = rr - l;
        Skin.text(c, "?", r.left + 40, r.top + 44, 30, Skin.DIM, Paint.Align.CENTER);                                    // replay the tour
        Skin.heart(c, r.right - 40, r.top + 34, 11, x.fav, x.fav ? 0xffff5a78 : Skin.DIM);                               // favourite
        String st = x.buffering ? "BUFFERING" : x.live ? "LIVE" : x.playing ? "PLAYING" : "PAUSED";
        Skin.text(c, st, r.centerX(), r.top + 42, 20, Skin.DIM, Paint.Align.CENTER);
        int n = 32; float bw = room / n, bot = r.top + 120, seg = 62f / 12;
        Q.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            int lit = (int) (Math.max(0f, Math.min(1f, x.bars[i])) * 12);
            for (int k = 0; k < 12; k++) {
                Q.setColor(k >= lit ? 0xff0e2218 : k < 7 ? 0xff3be37a : k < 10 ? 0xffe8e04a : 0xffff4a3a);
                c.drawRect(l + i * bw + 1.5f, bot - (k + 1) * seg + 1.5f, l + (i + 1) * bw - 1.5f, bot - k * seg, Q);
            }
        }
        Skin.marquee(c, x.title.isEmpty() ? "EARWAX" : x.title, l, r.top + 156, 30, Skin.LED, room);
        Skin.text(c, Skin.fit(x.artist, 22, room), l, r.top + 184, 22, Skin.DIM, Paint.Align.LEFT);
        Skin.text(c, Skin.mmss(x.pos), l, r.top + 218, 26, Skin.LED, Paint.Align.LEFT);
        Skin.text(c, x.live ? "LIVE" : Skin.mmss(x.dur), rr, r.top + 218, 26, Skin.DIM, Paint.Align.RIGHT);
        float gy = r.bottom - 24, f = x.dur > 0 && !x.live ? Math.min(1, x.pos / (float) x.dur) : 0;
        Q.setStrokeCap(Paint.Cap.ROUND); Q.setStrokeWidth(9); Q.setStyle(Paint.Style.STROKE);
        Q.setColor(0xff17402a); c.drawLine(l, gy, rr, gy, Q);
        Q.setColor(Skin.LED); c.drawLine(l, gy, l + room * f, gy, Q);
        Q.setStyle(Paint.Style.FILL); Q.setColor(0xffffffff); c.drawCircle(l + room * f, gy, 12, Q);
        c.restore();
    }

    private RectF chipSearch() { float y = pc.top + TAB_H; tmp.set(pc.right - 150, y + 6, pc.right, y + SUB_H - 6); return tmp; }
    private RectF chipUrl() { float y = pc.top + TAB_H; tmp.set(pc.right - 150 - 14 - 120, y + 6, pc.right - 164, y + SUB_H - 6); return tmp; }

    private void chip(Canvas c, RectF r, String t, boolean hot) {
        Q.setStyle(Paint.Style.FILL); Q.setColor(hot ? 0xff1d5a3a : 0xff0e2a1c); c.drawRoundRect(r, 16, 16, Q);
        Q.setStyle(Paint.Style.STROKE); Q.setStrokeWidth(2.5f); Q.setColor(Skin.DIM); c.drawRoundRect(r, 16, 16, Q);
        Skin.text(c, t, r.centerX(), r.centerY() + 7, 20, Skin.LED, Paint.Align.CENTER);
    }

    private void drawList(Canvas c, PlayerService sv) {
        float tw = pc.width() / TABS.length, top = pc.top;
        for (int i = 0; i < TABS.length; i++) {
            float x0 = pc.left + i * tw;
            if (i == tab) { Q.setStyle(Paint.Style.FILL); Q.setColor(0x4440c070); tmp.set(x0 + 3, top + 4, x0 + tw - 3, top + TAB_H - 8); c.drawRoundRect(tmp, 14, 14, Q); }
            Skin.text(c, TABS[i], x0 + tw / 2, top + 40, 21, i == tab ? 0xffffffff : Skin.DIM, Paint.Align.CENTER);
        }
        float sy = top + TAB_H;
        String lead = grp != null ? "< " + grp : TABS[tab] + "  " + (grp == null && (tab == 1 || tab == 2) ? rows.size() : vt.size());
        Skin.text(c, Skin.fit(lead, 24, pc.width() - (tab == 5 ? 330 : 190)), pc.left + 4, sy + 37, 24, grp != null ? Skin.LED : Skin.DIM, Paint.Align.LEFT);
        chip(c, chipSearch(), query.isEmpty() ? "SEARCH" : "X " + Skin.fit(query, 20, 90), !query.isEmpty());
        if (tab == 5) chip(c, chipUrl(), "+ URL", false);

        float y0 = listTop();
        if (rows.isEmpty()) { Skin.text(c, Skin.fit(emptyText(sv), 26, pc.width()), pc.centerX(), y0 + listH() / 2, 26, Skin.DIM, Paint.Align.CENTER); return; }
        Track cur = sv.current(); int first = Math.max(0, (int) (scroll / ROW));
        c.save(); c.clipRect(pc.left - 14, y0, pc.right + 14, pc.bottom);
        Q.setStyle(Paint.Style.FILL);
        for (int i = first; i < rows.size(); i++) {
            float y = y0 + i * ROW - scroll; if (y > pc.bottom) break;
            Row r = rows.get(i);
            if (r.group) {
                Skin.text(c, Skin.fit(r.a, 30, pc.width() - 220), pc.left + 4, y + 42, 30, Skin.LED, Paint.Align.LEFT);
                Skin.text(c, r.b, pc.right - 36, y + 42, 24, Skin.DIM, Paint.Align.RIGHT); Skin.text(c, ">", pc.right - 4, y + 42, 28, Skin.DIM, Paint.Align.RIGHT);
                continue;
            }
            Track t = r.t; boolean on = cur != null && cur.key().equals(t.key());
            if (on) { Q.setStyle(Paint.Style.FILL); Q.setColor(0x4440c070); tmp.set(pc.left - 8, y + 4, pc.right + 8, y + ROW - 4); c.drawRoundRect(tmp, 14, 14, Q); }
            Skin.text(c, t.stream() ? (isSaved(t) ? "*" : "~") : n3(r.ti + 1), pc.left + 4, y + 42, 26, Skin.DIM, Paint.Align.LEFT);
            Skin.text(c, Skin.fit(t.stream() ? t.title : t.label(), 30, pc.width() - 78 - (t.stream() ? 190 : 130)), pc.left + 78, y + 42, 30, on ? 0xffffffff : Skin.LED, Paint.Align.LEFT);
            if (t.stream()) Skin.text(c, Skin.fit(t.artist, 22, 180), pc.right - 2, y + 42, 22, Skin.DIM, Paint.Align.RIGHT);
            else {
                if (sv.fav(t)) Skin.heart(c, pc.right - 104, y + 33, 9, true, 0xffff5a78);
                Skin.text(c, Skin.mmss(t.dur), pc.right - 2, y + 42, 26, Skin.DIM, Paint.Align.RIGHT);
            }
        }
        c.restore();
    }

    private String emptyText(PlayerService sv) {
        if (!query.isEmpty()) return "NO MATCHES";
        switch (tab) {
            case 0: case 1: case 2: return sv.lib.isEmpty() ? "NO MUSIC FOUND - ALLOW STORAGE ACCESS" : "EMPTY";
            case 3: return "NO FAVOURITES - LONG-PRESS A TRACK";
            case 4: return "NOTHING PLAYED YET";
            default: return radioMsg.isEmpty() ? "NO STATIONS" : radioMsg;
        }
    }

    // ---- equalizer geometry
    private float eqCol() { return pc.width() / 11f; }
    private float eqTop() { return pc.top + 84; }
    private float eqBot() { return pc.top + 372; }
    private RectF chipPreset(int i) { float cw = (pc.width() - 42) / 4f, x = pc.left + (i % 4) * (cw + 14), y = pc.top + 418 + (i / 4) * 70; tmp.set(x, y, x + cw, y + 58); return tmp; }

    private void drawEq(Canvas c, PlayerService sv) {
        Skin.text(c, "EQUALIZER  " + sv.chanName(), pc.left + 4, pc.top + 42, 30, Skin.DIM, Paint.Align.LEFT);
        String[] lab = {"PRE", "60", "120", "250", "500", "1K", "2K", "4K", "8K", "12K", "16K"};
        float mid = (eqTop() + eqBot()) / 2, half = (eqBot() - eqTop()) / 2;
        Q.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 11; i++) {
            float cx = pc.left + (i + .5f) * eqCol(), v = sv.eq(i), ky = mid - v / 15f * half;
            Q.setColor(0xff06140d); tmp.set(cx - 13, eqTop(), cx + 13, eqBot()); c.drawRoundRect(tmp, 13, 13, Q);
            Q.setColor(v >= 0 ? 0xff3be37a : 0xffe8884a); tmp.set(cx - 9, Math.min(mid, ky), cx + 9, Math.max(mid, ky)); c.drawRoundRect(tmp, 9, 9, Q);
            Q.setColor(0xffdfe6ee); tmp.set(cx - 22, ky - 12, cx + 22, ky + 12); c.drawRoundRect(tmp, 10, 10, Q);
            Skin.text(c, lab[i], cx, eqBot() + 40, 22, Skin.DIM, Paint.Align.CENTER);
        }
        for (int i = 0; i < 8; i++) chip(c, chipPreset(i), PlayerService.PRESET_NAMES[i], false);
    }

    // ---- FX geometry
    private float fxY(int i) { return pc.top + 112 + i * 84; }
    private float fxX0() { return pc.left + 236; }
    private float fxX1() { return pc.right - 26; }
    private float fxVal(PlayerService v, int i) { switch (i) { case 0: return (v.pitch + 6) / 12; case 1: return (v.speed - .5f) / 1.5f; case 2: return (v.dsp.bass + 10) / 20; case 3: return (v.dsp.treble + 10) / 20; default: return v.vol; } }
    private String fxTxt(PlayerService v, int i) { switch (i) { case 0: return String.format(Locale.US, "%+.1f st", v.pitch); case 1: return String.format(Locale.US, "%.2fx", v.speed); case 2: return String.format(Locale.US, "%+.0f dB", v.dsp.bass); case 3: return String.format(Locale.US, "%+.0f dB", v.dsp.treble); default: return Math.round(v.vol * 100) + "%"; } }
    private RectF fxBtn(int i) { float bw = (pc.width() - 30) / 2f, x = pc.left + i * (bw + 30); tmp.set(x, pc.bottom - 84, x + bw, pc.bottom - 14); return tmp; }

    private void drawFx(Canvas c, PlayerService sv) {
        Skin.text(c, "SOUND FX", pc.left + 4, pc.top + 42, 30, Skin.DIM, Paint.Align.LEFT);
        for (int i = 0; i < 5; i++) {
            float y = fxY(i), x0 = fxX0(), x1 = fxX1(), f = Math.max(0f, Math.min(1f, fxVal(sv, i)));
            Skin.text(c, FX[i], pc.left + 4, y - 4, 26, Skin.LED, Paint.Align.LEFT);
            Skin.text(c, fxTxt(sv, i), pc.left + 4, y + 26, 24, Skin.DIM, Paint.Align.LEFT);
            Q.setStyle(Paint.Style.STROKE); Q.setStrokeCap(Paint.Cap.ROUND); Q.setStrokeWidth(18);
            Q.setColor(0xff06140d); c.drawLine(x0, y, x1, y, Q); Q.setColor(0xff3be37a); c.drawLine(x0, y, x0 + (x1 - x0) * f, y, Q);
            Q.setStyle(Paint.Style.FILL); Q.setColor(0xffdfe6ee); c.drawCircle(x0 + (x1 - x0) * f, y, 21, Q);
        }
        chip(c, fxBtn(0), sv.chanName(), false); chip(c, fxBtn(1), "SLEEP " + (sv.sleepMin == 0 ? "OFF" : sv.sleepMin + "m"), sv.sleepMin > 0);
    }

    // ------------------------------------------------------------------ dialogs
    private AlertDialog.Builder dlg(int title) { return new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title); }

    private void input(int title, int hint, int type, final java.util.function.Consumer<String> ok) {
        final EditText et = new EditText(getContext()); et.setSingleLine(true); et.setHint(hint); et.setInputType(type);
        FrameLayout box = new FrameLayout(getContext()); int p = (int) dp(22); box.setPadding(p, p / 2, p, 0); box.addView(et);
        AlertDialog d = dlg(title).setView(box).setNegativeButton(R.string.dlg_cancel, null).setPositiveButton(R.string.dlg_ok, (di, i) -> ok.accept(et.getText().toString().trim())).create();
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void promptSearch(final PlayerService sv) {
        input(tab == 5 ? R.string.dlg_radio_title : R.string.dlg_search_title, R.string.dlg_search_hint, android.text.InputType.TYPE_CLASS_TEXT, q -> {
            if (tab == 5) {
                if (q.isEmpty()) { loadTop(); return; }
                radioLoading = true; radioMsg = "LOADING..."; radioRes = new ArrayList<>(); rebuild(sv, false); invalidate();
                Radio.search(q, this::radioDone);
            } else { query = q; if (tab == 1 || tab == 2) grp = null; rebuild(sv, false); invalidate(); }
        });
    }

    private void promptUrl(final PlayerService sv) {
        input(R.string.dlg_url_title, R.string.dlg_url_hint, android.text.InputType.TYPE_TEXT_VARIATION_URI | android.text.InputType.TYPE_CLASS_TEXT, raw -> {
            if (raw.isEmpty()) return;
            final String u = raw.contains("://") ? raw : "http://" + raw;
            Radio.resolve(u, (l, e) -> {
                if (e != null || l.isEmpty()) { Toast.makeText(getContext(), R.string.bad_link, Toast.LENGTH_SHORT).show(); return; }
                Track t = l.get(0); if (!isSaved(t)) { saved.add(0, t); Prefs.streams(getContext(), saved); }
                sv.playStream(t); if (tab == 5) rebuild(sv, false); invalidate();
            });
        });
    }

    // ------------------------------------------------------------------ guided tour
    private void startTour() { tour = 0; buildTour(); invalidate(); }
    private void endTour() { tour = -1; Prefs.tourSeen(getContext(), true); invalidate(); }

    private RectF union(String... ids) {
        RectF u = null;
        for (String id : ids) { Skin.Btn b = head.find(id); if (b == null) continue; RectF r = new RectF(b.cx - b.r, b.cy - b.r, b.cx + b.r, b.cy + b.r); if (u == null) u = r; else u.union(r); }
        return u;
    }
    private RectF tourTarget() {
        switch (tour) {
            case 1: return union("prev", "play", "next"); case 2: return union("list", "eq", "fx"); case 3: return union("shuffle", "repeat", "sleep", "popup");
            case 4: return head.screen; case 5: return pr; case 6: return union("popup"); default: return null;
        }
    }
    private void buildTour() {
        String all = getContext().getString(TOUR[tour]); int nl = all.indexOf('\n'); String t = nl < 0 ? all : all.substring(0, nl), b = nl < 0 ? " " : all.substring(nl + 1);
        int bw = (int) (Math.min(w - dp(32), dp(340)) - dp(36));
        if (bw < 100) return;
        tpTitle.setTextSize(dp(17)); tpTitle.setColor(0xff9bffa8); tpBody.setTextSize(dp(14)); tpBody.setColor(0xfff0f4fa);
        tourTitle = StaticLayout.Builder.obtain(t, 0, t.length(), tpTitle, bw).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        tourBody = StaticLayout.Builder.obtain(b, 0, b.length(), tpBody, bw).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.12f).build();
    }

    private void drawTour(Canvas c) {
        if (tourTitle == null || tourBody == null) return;
        RectF t = tourTarget(); float m = dp(16), pad = dp(18);
        path.reset(); path.addRect(0, 0, w, h, Path.Direction.CW); path.setFillType(Path.FillType.EVEN_ODD);
        if (t != null) { tmp.set(ox + t.left * s - dp(8), oy + t.top * s - dp(8), ox + t.right * s + dp(8), oy + t.bottom * s + dp(8)); path.addRoundRect(tmp, dp(22), dp(22), Path.Direction.CW); }
        Q.setStyle(Paint.Style.FILL); Q.setColor(0xd0070b12); c.drawPath(path, Q);
        if (t != null) { Q.setStyle(Paint.Style.STROKE); Q.setStrokeWidth(dp(2.5f)); Q.setColor((((int) (140 + 115 * Math.sin(System.currentTimeMillis() / 220.0))) << 24) | 0xffffff); c.drawRoundRect(tmp, dp(22), dp(22), Q); }
        float bw = Math.min(w - 2 * m, dp(340)), bh = pad * 2 + tourTitle.getHeight() + dp(8) + tourBody.getHeight() + dp(56);
        float bx = (w - bw) / 2f, by;
        if (t == null) by = (h - bh) / 2f; else if (tmp.bottom + dp(14) + bh < h - m) by = tmp.bottom + dp(14); else by = Math.max(m, tmp.top - dp(14) - bh);
        bubble.set(bx, by, bx + bw, by + bh);
        Q.setStyle(Paint.Style.FILL); Q.setColor(0xf2141c2a); c.drawRoundRect(bubble, dp(20), dp(20), Q);
        Q.setStyle(Paint.Style.STROKE); Q.setStrokeWidth(dp(1.5f)); Q.setColor(0xff8fa3bf); c.drawRoundRect(bubble, dp(20), dp(20), Q);
        c.save(); c.translate(bx + pad, by + pad); tourTitle.draw(c); c.translate(0, tourTitle.getHeight() + dp(8)); tourBody.draw(c); c.restore();
        float ry = by + bh - dp(16) - dp(34);
        skipBtn.set(bx + pad - dp(6), ry, bx + pad + dp(80), ry + dp(34)); nextBtn.set(bx + bw - pad - dp(104), ry, bx + bw - pad + dp(6), ry + dp(34));
        tpUi.setTextSize(dp(13)); tpUi.setColor(0xffb7c4d6); tpUi.setTextAlign(Paint.Align.CENTER);
        c.drawText(getContext().getString(R.string.tour_skip), skipBtn.centerX(), skipBtn.centerY() + dp(5), tpUi);
        c.drawText((tour + 1) + "/" + TOUR.length, bubble.centerX(), skipBtn.centerY() + dp(5), tpUi);
        Q.setStyle(Paint.Style.FILL); Q.setColor(0xff1d7a4a); c.drawRoundRect(nextBtn, dp(17), dp(17), Q);
        tpUi.setColor(0xffffffff);
        c.drawText(getContext().getString(tour == TOUR.length - 1 ? R.string.tour_done : R.string.tour_next), nextBtn.centerX(), nextBtn.centerY() + dp(5), tpUi);
    }

    private boolean tourTouch(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = e.getX(), y = e.getY();
            if (nextBtn.contains(x, y)) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); if (tour >= TOUR.length - 1) endTour(); else { tour++; buildTour(); invalidate(); } }
            else if (skipBtn.contains(x, y)) endTour();
        }
        return true;
    }

    // ------------------------------------------------------------------ touch
    private void tapList(float x, float y) {
        PlayerService sv = PlayerService.I; if (sv == null || y < listTop() || !pr.contains(x, y)) return;
        int i = (int) ((y - listTop() + scroll) / ROW); if (i < 0 || i >= rows.size()) return;
        Row r = rows.get(i);
        if (r.group) { grp = r.grp; rebuild(sv, false); }
        else if (r.t.stream()) sv.playStream(r.t);
        else sv.playList(vt, r.ti, vkey);
    }

    private void longList(float x, float y) {
        PlayerService sv = PlayerService.I; if (sv == null || y < listTop() || !pr.contains(x, y)) return;
        int i = (int) ((y - listTop() + scroll) / ROW); if (i < 0 || i >= rows.size()) return;
        Row r = rows.get(i); if (r.group) return;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (r.t.stream()) {
            if (isSaved(r.t)) { for (int k = saved.size() - 1; k >= 0; k--) if (saved.get(k).url.equals(r.t.url)) saved.remove(k); } else saved.add(0, r.t);
            Prefs.streams(getContext(), saved); rebuild(sv, true);
        } else { sv.toggleFav(r.t); if (tab == 3) rebuild(sv, true); }
        invalidate();
    }

    private void headerTap(PlayerService sv, float x, float y) {
        if (y < pc.top + TAB_H) {
            int i = (int) ((x - pc.left) / (pc.width() / TABS.length));
            if (i >= 0 && i < TABS.length) { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); selectTab(sv, i); }
            return;
        }
        if (chipSearch().contains(x, y)) { if (!query.isEmpty() && tab != 5) { query = ""; rebuild(sv, false); } else promptSearch(sv); }
        else if (tab == 5 && chipUrl().contains(x, y)) promptUrl(sv);
        else if (grp != null && x < pc.left + 360) { grp = null; rebuild(sv, false); }
    }

    private void eqTouch(PlayerService sv, float x, float y, boolean first) {
        if (first) {
            for (int i = 0; i < 8; i++) if (chipPreset(i).contains(x, y)) { sv.preset(i); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }
            if (y < eqTop() - 20 || y > eqBot() + 20) return;
            eqBand = Math.max(0, Math.min(10, (int) ((x - pc.left) / eqCol())));
        }
        if (eqBand < 0) return;
        float mid = (eqTop() + eqBot()) / 2, half = (eqBot() - eqTop()) / 2;
        sv.setEq(eqBand, (mid - y) / half * 15f);
    }

    private void fxTouch(PlayerService sv, float x, float y, boolean first) {
        if (first) {
            fxIdx = -1;
            for (int i = 0; i < 5; i++) if (Math.abs(y - fxY(i)) < 42) fxIdx = i;
            if (fxBtn(0).contains(x, y)) { sv.cycleChan(); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }
            if (fxBtn(1).contains(x, y)) { sv.cycleSleep(); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }
        }
        if (fxIdx < 0) return;
        float f = Math.max(0, Math.min(1, (x - fxX0()) / (fxX1() - fxX0())));
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

    private void screenTap(PlayerService sv, float x, float y) {
        RectF r = head.screen; long now = System.currentTimeMillis();
        if (Math.hypot(x - (r.left + 40), y - (r.top + 34)) < 34) { startTour(); return; }
        if (Math.hypot(x - (r.right - 40), y - (r.top + 34)) < 38) { sv.toggleFav(sv.current()); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); return; }
        if (now - lastTap < 320) { sv.toggle(); lastTap = 0; } else lastTap = now;      // double tap = play / pause
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        PlayerService sv = PlayerService.I; if (sv == null) return true;
        if (tour >= 0) return tourTouch(e);
        float x = (e.getX() - ox) / s, y = (e.getY() - oy) / s; RectF r = head.screen;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sc.forceFinished(true); moved = false; downX = x; downY = y; seeking = false; listTouch = false; hdrTap = false; swipe = false; eqBand = -1; fxIdx = -1;
                down = head.hit(x, y);
                if (down != null) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                else if (r.contains(x, y)) { if (y > r.bottom - r.height() * 0.28f) { seeking = true; sv.seekFrac((x - r.left - 26) / (r.width() - 52)); } else swipe = true; }
                else if (pr.contains(x, y)) {
                    if (mode == 0) { if (y < listTop()) hdrTap = true; else listTouch = true; }
                    else if (mode == 1) eqTouch(sv, x, y, true); else fxTouch(sv, x, y, true);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(x - downX, y - downY) > 16) moved = true;
                if (seeking) sv.seekFrac((x - r.left - 26) / (r.width() - 52));
                else if (mode == 1 && eqBand >= 0) eqTouch(sv, x, y, false);
                else if (mode == 2 && fxIdx >= 0) fxTouch(sv, x, y, false);
                break;
            case MotionEvent.ACTION_UP:
                if (down != null && head.hit(x, y) == down) press(sv, down.id);
                else if (swipe) {
                    float dx = x - downX, dy = y - downY;
                    if (Math.abs(dx) > 130 && Math.abs(dy) < 110) { if (dx < 0) sv.next(); else sv.prev(); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }
                    else if (!moved) screenTap(sv, x, y);
                } else if (hdrTap && !moved) headerTap(sv, x, y);
                down = null; seeking = false; eqBand = -1; fxIdx = -1;
                break;
            case MotionEvent.ACTION_CANCEL: down = null; seeking = false; eqBand = -1; fxIdx = -1; break;
        }
        if (listTouch) gd.onTouchEvent(e);
        invalidate();
        return true;
    }
}
