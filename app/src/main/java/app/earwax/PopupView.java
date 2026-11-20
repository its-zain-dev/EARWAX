package app.earwax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * EARWAX 4.5 High-Resolution 3D Floating Overlay Popup + Twin 3D Sliding Wing Drawers!
 *
 * Features:
 *  - Full Retina-HD Python-raymarched hardware shells (whale, orb, pill) decoded with zero downscaling!
 *  - Dedicated Top 3D Drawers Button on EVERY popup style: pressing it unfolds TWO 3D liquid-chrome
 *    wing panels out to the LEFT and RIGHT in true 3D perspective:
 *      • LEFT 3D WING (Context-Aware Mini-Page):
 *          - YT Mode: Miniature YouTube Music page with live overlay Search, Mood pills, scrollable
 *            results, instant tap-to-play & 1-tap Offline Download (↓ / % / ✓) on every song!
 *          - LIB Mode: Miniature Local / Offline / Favourites browser with quick filter!
 *          - RAD Mode: Miniature Live Internet Radio tuner!
 *      • RIGHT 3D WING (Smart Track Tools, Up-Next Queue & Synced Lyrics — NOT volume!):
 *          - TOOLS: 1-tap Download current YT song, Start YT Auto-Radio mix, Favourite, Shuffle,
 *            Repeat, Sleep timer, EQ Preset cycler, Theme switcher & Open Full App!
 *          - QUEUE: Live scrollable Up-Next queue (tap to jump, × to remove, SHUF)!
 *          - LYRICS: Live LRCLIB time-synced lyrics (tap any line to seek)!
 *  - Lightweight zero-blur Canvas rendering & event-driven idle sleep (0% CPU when paused!)
 */
@UnstableApi
final class PopupView extends View implements PlayerService.Listener {
    private static final float DRAWER_ROW = 76f;
    private static final float LYRIC_ROW = 54f;

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

    // HD 3D Twin Wing Drawer assets (drawer_l.png, drawer_r.png, drawer.json)
    private final Bitmap drawerL, drawerR;
    private final float dW, dH;
    private final RectF dScreen = new RectF();
    private final Camera cam3d = new Camera();
    private final Matrix mat3d = new Matrix();

    private final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint S = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint B = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final Path artClip = new Path();
    private final RectF rTmp = new RectF(), rArt = new RectF();

    // Layout rects in View pixel coordinates
    private final RectF coreRect = new RectF();
    private final RectF leftWingRect = new RectF();
    private final RectF rightWingRect = new RectF();

    // Twin 3D Drawer state
    private boolean drawersOpen = false;
    private float drawerAnim = 0f; // 0 = closed, 1 = fully unfolded in 3D
    private long lastAnimMs = 0L;
    private int savedCompactX, savedCompactY;

    // Left 3D Wing state (0 = YT Mini-Page, 1 = LIB, 2 = RAD)
    private int leftTab = 0;
    private int leftYtMood = 0;
    private int leftLibSub = 0; // 0=ALL, 1=OFFLINE, 2=FAVS
    private int leftRadGenre = 0;
    private String leftYtQuery = "";
    private String leftLibQuery = "";
    private ArrayList<Track> leftYtTracks = new ArrayList<>();
    private ArrayList<Track> leftRadTracks = new ArrayList<>();
    private boolean leftYtLoading, leftRadLoading;
    private String leftMsg = "";
    private float leftScroll = 0f;

    // Right 3D Wing state (0 = TOOLS, 1 = QUEUE, 2 = LYRICS)
    private int rightTab = 0;
    private float rightScroll = 0f;

    // Overlay search bar state
    private View searchOverlay;

    // Touch state
    private Skin.Btn down;
    private boolean drag, seek, rotating, scaling, rotMoved, tickScheduled;
    private int wingScrollMode = 0; // 0=none, 1=leftWing, 2=rightWing
    private float rx, ry, downRawX, downRawY, downDesignX, downDesignY, lastWingY, lastTick;
    private boolean wingMoved;
    private long lastTapMs, lastKnobTapMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            tickScheduled = false;
            stepDrawerAnim();
            invalidate();
            scheduleNextTickIfNeeded();
        }
    };

    PopupView(PlayerService s, String name) {
        super(s);
        sv = s;
        skinName = name;
        dm = s.getResources().getDisplayMetrics();
        // Comfortably larger default popup sizes!
        baseDp = "orb".equals(name) ? 248f : ("pill".equals(name) ? 324f : 356f);
        scale = Math.max(0.65f, Math.min(1.85f, Prefs.popupScale(s, name)));
        // Decode at FULL native HD resolution (0 = no downscaling!)
        sk = Skin.load(s, name, 0);
        aspect = sk.h / (float) sk.w;
        slop = ViewConfiguration.get(s).getScaledTouchSlop();

        // Load HD 3D Twin Wing Drawer skins
        drawerL = Skin.loadBitmap(s, "skins/drawer_l.png", 0);
        drawerR = Skin.loadBitmap(s, "skins/drawer_r.png", 0);
        JSONObject dj = Skin.loadJson(s, "skins/drawer.json");
        if (dj != null) {
            dW = (float) dj.optDouble("w", 592.0);
            dH = (float) dj.optDouble("h", 732.0);
            JSONObject ds = dj.optJSONObject("screen");
            if (ds != null) {
                float sx = (float) ds.optDouble("x", 92.0);
                float sy = (float) ds.optDouble("y", 92.0);
                dScreen.set(sx, sy, sx + (float) ds.optDouble("w", 408.0), sy + (float) ds.optDouble("h", 548.0));
            } else {
                dScreen.set(76f, 86f, dW - 76f, dH - 86f);
            }
        } else {
            dW = 592f;
            dH = 732f;
            dScreen.set(76f, 86f, dW - 76f, dH - 86f);
        }

        int wp = (int) (baseDp * scale * dm.density);
        lp = new WindowManager.LayoutParams(
                wp, (int) (wp * aspect),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.popupX(s, name, (int) (14 * dm.density));
        lp.y = Prefs.popupY(s, name, (int) (110 * dm.density));
        savedCompactX = lp.x;
        savedCompactY = lp.y;

        pinch = new ScaleGestureDetector(s, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (!drawersOpen) resize(scale * d.getScaleFactor());
                return true;
            }
        });

        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        wm.addView(this, lp);
        s.viewer(true);
        s.addListener(this);
        wakeLoop();
    }

    private boolean isAnimatingDrawers() {
        float target = drawersOpen ? 1f : 0f;
        return Math.abs(drawerAnim - target) > 0.005f;
    }

    private void stepDrawerAnim() {
        float target = drawersOpen ? 1f : 0f;
        if (Math.abs(drawerAnim - target) <= 0.005f) {
            if (drawerAnim != target) {
                drawerAnim = target;
                if (!drawersOpen) applyWindowGeometry();
            }
            lastAnimMs = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        float dt = lastAnimMs == 0L ? 0.032f : Math.min(0.06f, (now - lastAnimMs) / 1000f);
        lastAnimMs = now;
        float speed = 5.2f; // crisp ~190ms 3D unfold/fold
        if (drawerAnim < target) drawerAnim = Math.min(target, drawerAnim + dt * speed);
        else drawerAnim = Math.max(target, drawerAnim - dt * speed);
        if (!drawersOpen && drawerAnim <= 0.005f) {
            drawerAnim = 0f;
            applyWindowGeometry();
        }
    }

    private void scheduleNextTickIfNeeded() {
        if (tickScheduled) return;
        boolean busy = sv.pl.isPlaying() || rotating || down != null || seek || isAnimatingDrawers();
        if (busy) {
            tickScheduled = true;
            postDelayed(tick, isAnimatingDrawers() ? 16 : 36);
        }
    }

    private void wakeLoop() {
        postInvalidateOnAnimation();
        scheduleNextTickIfNeeded();
    }

    @Override public void onServiceUpdate() { wakeLoop(); }

    void dismiss() {
        closeSearchOverlay();
        removeCallbacks(tick);
        tickScheduled = false;
        sv.removeListener(this);
        Prefs.popupPos(sv, skinName, drawersOpen ? savedCompactX : lp.x, drawersOpen ? savedCompactY : lp.y);
        Prefs.popupScale(sv, skinName, scale);
        try { wm.removeView(this); } catch (Exception ignored) { }
        sv.viewer(false);
        sk.release();
        if (drawerL != null && !drawerL.isRecycled()) drawerL.recycle();
        if (drawerR != null && !drawerR.isRecycled()) drawerR.recycle();
    }

    // ------------------------------------------------------------------ 3D Drawers Toggle & Geometry
    private void toggleDrawers() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (!drawersOpen) {
            savedCompactX = lp.x;
            savedCompactY = lp.y;
            drawersOpen = true;
            // Context-aware auto-selection of Left 3D Panel!
            Track cur = sv.current();
            if (cur == null || cur.isYouTube() || cur.isOfflineYtm()) {
                leftTab = 0;
                if (leftYtTracks.isEmpty() && !leftYtLoading) loadLeftYtMood(leftYtMood);
            } else if (cur.isRadio()) {
                leftTab = 2;
                if (leftRadTracks.isEmpty() && !leftRadLoading) loadLeftRadio(leftRadGenre);
            } else {
                leftTab = 1;
            }
            if (rightTab == 2) sv.loadLyricsForCurrent();
            applyWindowGeometry();
        } else {
            closeSearchOverlay();
            drawersOpen = false;
        }
        lastAnimMs = System.currentTimeMillis();
        wakeLoop();
    }

    private void applyWindowGeometry() {
        int coreW = Math.min(dm.widthPixels - (int) (12 * dm.density), (int) (baseDp * scale * dm.density));
        int coreH = Math.max(1, (int) (coreW * aspect));

        if (drawersOpen || drawerAnim > 0.01f) {
            int expW = Math.min(dm.widthPixels - (int) (8 * dm.density), Math.max(coreW, (int) (392 * dm.density)));
            int gap = (int) (6 * dm.density);
            int wingW = (expW - gap) / 2;
            int wingH = (int) (wingW * (dH / dW));
            // Cap wing height cleanly so popup + 3D wings never exceed 78% of screen height
            int maxWingH = (int) (dm.heightPixels * 0.44f);
            if (wingH > maxWingH) wingH = maxWingH;
            int topOverlap = (int) (10 * dm.density);
            int expH = coreH + wingH - topOverlap;

            lp.width = expW;
            lp.height = expH;
            lp.x = Math.max((int) (4 * dm.density), (dm.widthPixels - expW) / 2);
            lp.y = Math.max((int) (24 * dm.density), Math.min(dm.heightPixels - expH - (int) (16 * dm.density), savedCompactY));
        } else {
            lp.width = coreW;
            lp.height = coreH;
            lp.x = savedCompactX;
            lp.y = savedCompactY;
        }
        try { wm.updateViewLayout(this, lp); } catch (Exception ignored) { }
    }

    private void computeSubRects() {
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        int coreW = Math.min(vw, (int) (baseDp * scale * dm.density));
        int coreH = Math.max(1, (int) (coreW * aspect));
        if (!drawersOpen && drawerAnim <= 0.01f) {
            coreRect.set(0, 0, vw, vh);
            leftWingRect.setEmpty();
            rightWingRect.setEmpty();
            return;
        }
        float cx = (vw - coreW) * 0.5f;
        coreRect.set(cx, 0, cx + coreW, Math.min(vh, coreH));

        float topOverlap = 10 * dm.density;
        float wingTop = Math.max(coreRect.bottom - topOverlap, coreRect.height() * 0.72f);
        float wingH = Math.max(10f, vh - wingTop);
        float gap = 6 * dm.density;
        float wingW = (vw - gap) * 0.5f;

        // Smoothly slide left wing outward to the LEFT and right wing outward to the RIGHT!
        float ease = 1f - (1f - drawerAnim) * (1f - drawerAnim) * (1f - drawerAnim);
        float slideOffset = (1f - ease) * (wingW * 0.75f);

        leftWingRect.set(slideOffset, wingTop, slideOffset + wingW, wingTop + wingH);
        rightWingRect.set(vw - wingW - slideOffset, wingTop, vw - slideOffset, wingTop + wingH);
    }

    private void resize(float k) {
        k = Math.max(0.65f, Math.min(1.85f, k));
        if (Math.abs(k - scale) < 0.004f) return;
        float cx = lp.x + lp.width / 2f, cy = lp.y + lp.height / 2f;
        scale = k;
        lp.width = (int) (baseDp * scale * dm.density);
        lp.height = (int) (lp.width * aspect);
        lp.x = (int) (cx - lp.width / 2f);
        lp.y = (int) (cy - lp.height / 2f);
        savedCompactX = lp.x;
        savedCompactY = lp.y;
        wm.updateViewLayout(this, lp);
    }

    private void keepOnScreen() {
        int margin = (int) (6 * dm.density);
        int snapDist = (int) (22 * dm.density);
        if (!drawersOpen) {
            if (Math.abs(lp.x - margin) < snapDist) lp.x = margin;
            else if (Math.abs((lp.x + lp.width) - (dm.widthPixels - margin)) < snapDist)
                lp.x = dm.widthPixels - margin - lp.width;
            lp.x = Math.max(-lp.width / 4, Math.min(dm.widthPixels - lp.width * 3 / 4, lp.x));
            lp.y = Math.max(margin, Math.min(dm.heightPixels - lp.height / 2, lp.y));
            savedCompactX = lp.x;
            savedCompactY = lp.y;
        } else {
            lp.x = Math.max(0, Math.min(dm.widthPixels - lp.width, lp.x));
            lp.y = Math.max(margin, Math.min(dm.heightPixels - lp.height / 2, lp.y));
            savedCompactY = lp.y;
        }
        wm.updateViewLayout(this, lp);
    }

    // ------------------------------------------------------------------ Data Loaders for Left 3D Wing
    private void loadLeftYtMood(int m) {
        leftYtMood = m;
        leftYtQuery = "";
        leftYtLoading = true;
        leftMsg = "Loading " + YtMusic.MOODS[m] + "...";
        leftScroll = 0f;
        invalidate();
        YtMusic.discover(m, (list, err) -> {
            leftYtLoading = false;
            leftYtTracks = new ArrayList<>(list);
            leftMsg = err == null ? "" : err;
            invalidate();
        });
    }

    private void searchLeftYt(String q) {
        leftYtQuery = q;
        Prefs.addSearchHistory(sv, q);
        leftYtLoading = true;
        leftMsg = "Searching \"" + q + "\"...";
        leftYtTracks = new ArrayList<>();
        leftScroll = 0f;
        invalidate();
        YtMusic.search(q, true, (list, err) -> {
            leftYtLoading = false;
            leftYtTracks = new ArrayList<>(list);
            leftMsg = err == null ? "" : err;
            invalidate();
        });
    }

    private void loadLeftRadio(int g) {
        leftRadGenre = g;
        leftRadLoading = true;
        leftMsg = "Tuning Radio...";
        leftScroll = 0f;
        invalidate();
        Radio.genre(g, (list, err) -> {
            leftRadLoading = false;
            leftRadTracks = new ArrayList<>(list);
            leftMsg = err == null ? "" : err;
            invalidate();
        });
    }

    private List<Track> leftVisibleTracks() {
        if (leftTab == 0) return leftYtTracks;
        if (leftTab == 2) return leftRadTracks.isEmpty() ? Radio.curated() : leftRadTracks;
        List<Track> src = leftLibSub == 1 ? sv.offlineList : (leftLibSub == 2 ? sv.favList : sv.lib);
        if (leftLibQuery.isEmpty()) return src;
        String q = leftLibQuery.toLowerCase(Locale.ROOT);
        ArrayList<Track> out = new ArrayList<>();
        for (Track t : src) {
            if (t.title.toLowerCase(Locale.ROOT).contains(q) || t.artist.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Drawing
    @Override
    protected void onDraw(Canvas c) {
        if (sk.bmp == null || sk.bmp.isRecycled()) return;
        computeSubRects();

        Skin.resetPaints();
        Q.setShader(null); Q.setAlpha(255); Q.clearShadowLayer(); Q.setStyle(Paint.Style.FILL);
        S.setShader(null); S.setAlpha(255); S.clearShadowLayer(); S.setStyle(Paint.Style.STROKE);

        Hud x = sv.hud();

        // 1. Draw Twin 3D Unfolding Wing Drawers (behind lower rim of popup core!)
        if (drawerAnim > 0.01f && !leftWingRect.isEmpty() && !rightWingRect.isEmpty()) {
            draw3DWing(c, true, leftWingRect, x);
            draw3DWing(c, false, rightWingRect, x);
        }

        // 2. Draw Main HD 3D Liquid-Chrome Popup Core
        float sc = coreRect.width() / (float) sk.w;
        c.save();
        c.translate(coreRect.left, coreRect.top);
        c.scale(sc, sc);
        c.drawBitmap(sk.bmp, null, sk.full, Skin.P);

        if ("orb".equals(skinName)) drawOrbOverlay(c, x);
        else if ("pill".equals(skinName)) drawPillOverlay(c, x);
        else drawCyberDeckOverlay(c, x);

        c.restore();
    }

    /** Renders Left or Right 3D Liquid-Chrome Wing Drawer with authentic 3D perspective fold! */
    private void draw3DWing(Canvas c, boolean isLeft, RectF rect, Hud x) {
        float ease = 1f - (1f - drawerAnim) * (1f - drawerAnim) * (1f - drawerAnim);
        int alpha = Math.max(0, Math.min(255, Math.round(255f * ease)));
        // Subtle 3D Y-axis cockpit angle: wings swing smoothly out in 3D space!
        float rotY = isLeft ? (5f + 34f * (1f - ease)) : -(5f + 34f * (1f - ease));

        c.save();
        cam3d.save();
        cam3d.setLocation(0f, 0f, -18f);
        cam3d.rotateY(rotY);
        cam3d.getMatrix(mat3d);
        cam3d.restore();

        float pivX = isLeft ? rect.right : rect.left;
        float pivY = rect.centerY();
        mat3d.preTranslate(-pivX, -pivY);
        mat3d.postTranslate(pivX, pivY);
        c.concat(mat3d);

        float scX = rect.width() / dW;
        float scY = rect.height() / dH;
        c.translate(rect.left, rect.top);
        c.scale(scX, scY);

        Bitmap wingBmp = isLeft ? drawerL : drawerR;
        if (wingBmp != null && !wingBmp.isRecycled()) {
            B.setAlpha(alpha);
            rTmp.set(0, 0, dW, dH);
            c.drawBitmap(wingBmp, null, rTmp, B);
            B.setAlpha(255);
        } else {
            Q.setColor(Theme.alpha(0xff10223b, alpha));
            c.drawRoundRect(0, 0, dW, dH, 32f, 32f, Q);
        }

        if (ease > 0.35f) {
            c.save();
            clip.reset();
            clip.addRoundRect(dScreen.left + 3, dScreen.top + 3, dScreen.right - 3, dScreen.bottom - 3, 34f, 34f, Path.Direction.CW);
            c.clipPath(clip);
            Q.setColor(0xea071121);
            c.drawRect(dScreen, Q);

            if (isLeft) drawLeftWingContent(c, dScreen, x);
            else drawRightWingContent(c, dScreen, x);

            c.restore();
        }
        c.restore();
    }

    // ---- LEFT 3D WING: Miniature YouTube Music / Library / Radio Page!
    private void drawLeftWingContent(Canvas c, RectF r, Hud x) {
        float pad = 16f;
        float lx = r.left + pad, rx = r.right - pad, w = rx - lx;
        float y = r.top + 16f;

        // 1. Source Tabs: [YT] [LIB] [RAD]
        String[] tabs = {"YT", "LIB", "RAD"};
        float tw = (w - 12f) / 3f;
        for (int i = 0; i < 3; i++) {
            float x0 = lx + i * (tw + 6f);
            boolean on = (i == leftTab);
            rTmp.set(x0, y, x0 + tw, y + 44f);
            int accent = (i == 0) ? Theme.YT_BADGE : Theme.primary();
            Q.setColor(on ? accent : 0xff102036);
            c.drawRoundRect(rTmp, 12f, 12f, Q);
            S.setStrokeWidth(1.5f);
            S.setColor(on ? 0xffffffff : Theme.alpha(Theme.CHROME_MID, 110));
            c.drawRoundRect(rTmp, 12f, 12f, S);
            Skin.text(c, tabs[i], rTmp.centerX(), rTmp.centerY() + 7f, 21f,
                    on ? 0xff03101d : Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_BOLD, false);
        }
        y += 54f;

        // 2. Search Bar (YT or LIB) / Radio Genre Header
        if (leftTab == 0 || leftTab == 1) {
            rTmp.set(lx, y, rx, y + 48f);
            Q.setColor(0xff0b182c);
            c.drawRoundRect(rTmp, 14f, 14f, Q);
            S.setStrokeWidth(1.8f);
            S.setColor(leftTab == 0 ? Theme.YT_BADGE : Theme.primary());
            c.drawRoundRect(rTmp, 14f, 14f, S);
            String curQ = leftTab == 0 ? leftYtQuery : leftLibQuery;
            String ph = curQ.isEmpty() ? (leftTab == 0 ? "🔍 Search YT Music..." : "🔍 Filter library...") : ("🔍 " + curQ + "  ×");
            Skin.text(c, Skin.fit(ph, 20f, w - 24f, Theme.TF_BOLD),
                    lx + 14f, rTmp.centerY() + 7f, 20f,
                    curQ.isEmpty() ? Theme.TEXT_SUB : Theme.glow(), Paint.Align.LEFT, Theme.TF_BOLD, false);
            y += 58f;
        }

        // 3. Quick Context Pills
        if (leftTab == 0) {
            String[] moods = {"TREND", "CHILL", "BASS", "ARABIC"};
            int[] moodMap = {0, 1, 2, 7};
            float mw = (w - 12f) / 4f;
            for (int i = 0; i < 4; i++) {
                float mx = lx + i * (mw + 4f);
                boolean hot = leftYtQuery.isEmpty() && leftYtMood == moodMap[i];
                rTmp.set(mx, y, mx + mw, y + 36f);
                Q.setColor(hot ? Theme.alpha(Theme.primary(), 75) : 0xff0c182a);
                c.drawRoundRect(rTmp, 10f, 10f, Q);
                if (hot) {
                    S.setStrokeWidth(1.4f); S.setColor(Theme.primary());
                    c.drawRoundRect(rTmp, 10f, 10f, S);
                }
                Skin.text(c, moods[i], rTmp.centerX(), rTmp.centerY() + 6f, 15f,
                        hot ? 0xffffffff : Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
            }
            y += 44f;
        } else if (leftTab == 1) {
            String[] subs = {"SONGS", "OFFLINE", "FAVS"};
            float mw = (w - 8f) / 3f;
            for (int i = 0; i < 3; i++) {
                float mx = lx + i * (mw + 4f);
                boolean hot = leftLibSub == i;
                rTmp.set(mx, y, mx + mw, y + 36f);
                Q.setColor(hot ? Theme.alpha(Theme.primary(), 75) : 0xff0c182a);
                c.drawRoundRect(rTmp, 10f, 10f, Q);
                Skin.text(c, subs[i], rTmp.centerX(), rTmp.centerY() + 6f, 15.5f,
                        hot ? 0xffffffff : Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
            }
            y += 44f;
        } else {
            String[] rgs = {"TOP", "CHILL", "ELECTRO", "ARABIC"};
            int[] rgMap = {0, 1, 2, 7};
            float mw = (w - 12f) / 4f;
            for (int i = 0; i < 4; i++) {
                float mx = lx + i * (mw + 4f);
                boolean hot = leftRadGenre == rgMap[i];
                rTmp.set(mx, y, mx + mw, y + 36f);
                Q.setColor(hot ? Theme.alpha(Theme.secondary(), 80) : 0xff0c182a);
                c.drawRoundRect(rTmp, 10f, 10f, Q);
                Skin.text(c, rgs[i], rTmp.centerX(), rTmp.centerY() + 6f, 14.5f,
                        hot ? 0xffffffff : Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
            }
            y += 44f;
        }

        // 4. Scrollable Mini Track List with 1-Tap Download button on YT tracks!
        List<Track> list = leftVisibleTracks();
        float listTop = y;
        float listBot = r.bottom - 12f;
        float listH = Math.max(40f, listBot - listTop);
        float maxSc = Math.max(0f, list.size() * DRAWER_ROW - listH);
        leftScroll = Math.max(0f, Math.min(maxSc, leftScroll));

        if (list.isEmpty()) {
            String empty = (leftYtLoading || leftRadLoading) ? leftMsg
                    : (leftTab == 0 ? (leftMsg.isEmpty() ? "Tap Search to find songs" : leftMsg) : "No tracks here");
            Skin.text(c, Skin.fit(empty, 19f, w, Theme.TF_MEDIUM),
                    r.centerX(), listTop + listH * 0.45f, 19f, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MEDIUM, false);
            return;
        }

        Track cur = sv.current();
        c.save();
        c.clipRect(lx - 4, listTop, rx + 4, listBot);
        int first = Math.max(0, (int) (leftScroll / DRAWER_ROW));
        for (int i = first; i < list.size(); i++) {
            float ry = listTop + i * DRAWER_ROW - leftScroll;
            if (ry > listBot) break;
            Track t = list.get(i);
            boolean on = cur != null && cur.key().equals(t.key());

            rTmp.set(lx, ry + 3, rx, ry + DRAWER_ROW - 4);
            Q.setColor(on ? Theme.alpha(Theme.primary(), 46) : 0x880b182c);
            c.drawRoundRect(rTmp, 12f, 12f, Q);
            if (on) {
                S.setStrokeWidth(1.6f); S.setColor(Theme.primary());
                c.drawRoundRect(rTmp, 12f, 12f, S);
            }

            boolean showDl = t.isYouTube();
            float rightReserve = showDl ? 72f : 14f;
            float avail = Math.max(60f, w - 16f - rightReserve);

            Skin.text(c, Skin.fit(t.title, 20.5f, avail, Theme.TF_BOLD),
                    lx + 12f, ry + 33f, 20.5f, on ? Theme.glow() : Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, false);
            Skin.text(c, Skin.fit(t.subLabel(), 15.5f, avail, Theme.TF_MEDIUM),
                    lx + 12f, ry + 57f, 15.5f, Theme.TEXT_SUB, Paint.Align.LEFT, Theme.TF_MEDIUM, false);

            if (showDl) {
                boolean off = sv.isOffline(t);
                int pct = Downloader.progress(t.ytId);
                boolean dl = pct >= 0;
                rTmp.set(rx - 62f, ry + 14f, rx - 8f, ry + DRAWER_ROW - 15f);
                Q.setColor(off ? Theme.alpha(Theme.OFF_BADGE, 55) : (dl ? Theme.alpha(Theme.primary(), 60) : 0xff132744));
                c.drawRoundRect(rTmp, 11f, 11f, Q);
                S.setStrokeWidth(1.4f);
                S.setColor(off ? Theme.OFF_BADGE : Theme.primary());
                c.drawRoundRect(rTmp, 11f, 11f, S);
                Skin.text(c, off ? "✓" : (dl ? pct + "%" : "↓"), rTmp.centerX(), rTmp.centerY() + 7f,
                        dl ? 14.5f : 21f, off ? Theme.OFF_BADGE : Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, false);
            }
        }
        c.restore();
    }

    // ---- RIGHT 3D WING: Smart Track Control Tools (NOT volume!) + Queue + Synced Lyrics!
    private void drawRightWingContent(Canvas c, RectF r, Hud x) {
        float pad = 16f;
        float lx = r.left + pad, rx = r.right - pad, w = rx - lx;
        float y = r.top + 16f;

        // 1. Sub-tabs: [TOOLS] [QUEUE] [LYRICS]
        String[] tabs = {"TOOLS", "QUEUE", "LYRICS"};
        float tw = (w - 12f) / 3f;
        for (int i = 0; i < 3; i++) {
            float x0 = lx + i * (tw + 6f);
            boolean on = (i == rightTab);
            rTmp.set(x0, y, x0 + tw, y + 44f);
            Q.setColor(on ? Theme.primary() : 0xff102036);
            c.drawRoundRect(rTmp, 12f, 12f, Q);
            S.setStrokeWidth(1.5f);
            S.setColor(on ? 0xffffffff : Theme.alpha(Theme.CHROME_MID, 110));
            c.drawRoundRect(rTmp, 12f, 12f, S);
            Skin.text(c, tabs[i], rTmp.centerX(), rTmp.centerY() + 7f, 19.5f,
                    on ? 0xff03101d : Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_BOLD, false);
        }
        y += 56f;

        if (rightTab == 0) {
            drawRightTools(c, lx, y, rx, r.bottom - 12f, x);
        } else if (rightTab == 1) {
            drawRightQueue(c, lx, y, rx, r.bottom - 12f);
        } else {
            drawRightLyrics(c, lx, y, rx, r.bottom - 12f);
        }
    }

    private void drawRightTools(Canvas c, float lx, float top, float rx, float bot, Hud x) {
        float w = rx - lx;
        Track cur = sv.current();
        float y = top;

        // Context Header Card: Currently Playing Song + Source
        rTmp.set(lx, y, rx, y + 72f);
        Q.setColor(0xff0d1d34);
        c.drawRoundRect(rTmp, 14f, 14f, Q);
        S.setStrokeWidth(1.4f); S.setColor(Theme.alpha(Theme.primary(), 120));
        c.drawRoundRect(rTmp, 14f, 14f, S);
        Skin.text(c, Skin.fit(x.title, 20f, w - 22f, Theme.TF_BOLD),
                lx + 11f, y + 30f, 20f, Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, false);
        Skin.text(c, Skin.fit(x.badge.isEmpty() ? "EARWAX 4.5" : x.badge, 15.5f, w - 22f, Theme.TF_MONO),
                lx + 11f, y + 55f, 15.5f, Theme.primary(), Paint.Align.LEFT, Theme.TF_MONO, false);
        y += 82f;

        // Context-aware primary YouTube Music / Track actions (Download + Auto-Radio!)
        boolean isYt = cur != null && (cur.isYouTube() || cur.isOfflineYtm());
        float btnH = 56f, gap = 10f;
        float halfW = (w - gap) * 0.5f;

        if (isYt) {
            boolean off = sv.isOffline(cur);
            int pct = Downloader.progress(cur.ytId);
            String dlLbl = off ? "✓ SAVED OFFLINE" : (pct >= 0 ? ("DOWNLOADING " + pct + "%") : "↓ DOWNLOAD SONG");
            drawToolTile(c, lx, y, rx, y + btnH, dlLbl, off || pct >= 0, Theme.OFF_BADGE);
            y += btnH + gap;

            drawToolTile(c, lx, y, lx + halfW, y + btnH, "◎ YT RADIO", false, Theme.YT_BADGE);
            drawToolTile(c, lx + halfW + gap, y, rx, y + btnH, x.fav ? "♥ FAVOURITED" : "♡ FAVOURITE", x.fav, Theme.HEART_ON);
            y += btnH + gap;
        } else {
            drawToolTile(c, lx, y, lx + halfW, y + btnH, x.fav ? "♥ FAVOURITED" : "♡ FAVOURITE", x.fav, Theme.HEART_ON);
            drawToolTile(c, lx + halfW + gap, y, rx, y + btnH, "LRC LYRICS", false, Theme.primary());
            y += btnH + gap;
        }

        // Functional playback & DSP tools (NOT volume!)
        drawToolTile(c, lx, y, lx + halfW, y + btnH, x.shuffle ? "⇄ SHUF: ON" : "⇄ SHUFFLE", x.shuffle, Theme.primary());
        String repLbl = x.repeat == 1 ? "↻ REP: ONE" : (x.repeat == 2 ? "↻ REP: ALL" : "↻ REPEAT");
        drawToolTile(c, lx + halfW + gap, y, rx, y + btnH, repLbl, x.repeat != 0, Theme.primary());
        y += btnH + gap;

        String eqName = (sv.presetIdx >= 0 && sv.presetIdx < PlayerService.PRESET_NAMES.length)
                ? PlayerService.PRESET_NAMES[sv.presetIdx] : "CUSTOM";
        drawToolTile(c, lx, y, lx + halfW, y + btnH, "EQ: " + eqName, sv.dsp.eqEnabled, Theme.secondary());
        drawToolTile(c, lx + halfW + gap, y, rx, y + btnH, "☾ " + sv.sleepLabel(), sv.sleepMin > 0 || sv.sleepEndOfTrack, Theme.primary());
        y += btnH + gap;

        drawToolTile(c, lx, y, lx + halfW, y + btnH, "◈ " + Theme.get().name, false, Theme.primary());
        drawToolTile(c, lx + halfW + gap, y, rx, y + btnH, "⬈ FULL APP", false, Theme.glow());
    }

    private void drawToolTile(Canvas c, float l, float t, float r, float b, String lbl, boolean hot, int col) {
        rTmp.set(l, t, r, b);
        Q.setColor(hot ? Theme.alpha(col, 68) : 0xff102037);
        c.drawRoundRect(rTmp, 14f, 14f, Q);
        S.setStrokeWidth(hot ? 2f : 1.3f);
        S.setColor(hot ? col : Theme.alpha(Theme.CHROME_MID, 115));
        c.drawRoundRect(rTmp, 14f, 14f, S);
        Skin.text(c, Skin.fit(lbl, 17.5f, (r - l) - 14f, Theme.TF_BOLD),
                rTmp.centerX(), rTmp.centerY() + 6.5f, 17.5f,
                hot ? 0xffffffff : Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_BOLD, false);
    }

    private void drawRightQueue(Canvas c, float lx, float top, float rx, float bot) {
        float w = rx - lx;
        // Header with track count + SHUF chip
        Skin.text(c, "UP NEXT (" + sv.queue.size() + ")", lx + 4f, top + 28f, 19f, Theme.glow(), Paint.Align.LEFT, Theme.TF_BOLD, false);
        rTmp.set(rx - 102f, top + 2f, rx, top + 38f);
        Q.setColor(0xff132744);
        c.drawRoundRect(rTmp, 10f, 10f, Q);
        S.setStrokeWidth(1.3f); S.setColor(Theme.primary());
        c.drawRoundRect(rTmp, 10f, 10f, S);
        Skin.text(c, "⇄ SHUF", rTmp.centerX(), rTmp.centerY() + 6f, 16f, Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_BOLD, false);

        float listTop = top + 46f;
        float listH = Math.max(40f, bot - listTop);
        float maxSc = Math.max(0f, sv.queue.size() * DRAWER_ROW - listH);
        rightScroll = Math.max(0f, Math.min(maxSc, rightScroll));

        if (sv.queue.isEmpty()) {
            Skin.text(c, "Queue is empty", (lx + rx) * 0.5f, listTop + listH * 0.45f, 19f, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MEDIUM, false);
            return;
        }

        int curQ = sv.currentQueueIndex();
        c.save();
        c.clipRect(lx - 4, listTop, rx + 4, bot);
        int first = Math.max(0, (int) (rightScroll / DRAWER_ROW));
        for (int i = first; i < sv.queue.size(); i++) {
            float ry = listTop + i * DRAWER_ROW - rightScroll;
            if (ry > bot) break;
            Track t = sv.queue.get(i);
            boolean on = (i == curQ);
            rTmp.set(lx, ry + 3, rx, ry + DRAWER_ROW - 4);
            Q.setColor(on ? Theme.alpha(Theme.primary(), 48) : 0x880b182c);
            c.drawRoundRect(rTmp, 12f, 12f, Q);
            if (on) {
                S.setStrokeWidth(1.5f); S.setColor(Theme.primary());
                c.drawRoundRect(rTmp, 12f, 12f, S);
            }
            float avail = Math.max(60f, w - 68f);
            Skin.text(c, Skin.fit(t.title, 20f, avail, Theme.TF_BOLD),
                    lx + 12f, ry + 32f, 20f, on ? Theme.glow() : Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, false);
            Skin.text(c, Skin.fit(t.subLabel(), 15f, avail, Theme.TF_MEDIUM),
                    lx + 12f, ry + 56f, 15f, Theme.TEXT_SUB, Paint.Align.LEFT, Theme.TF_MEDIUM, false);
            Skin.text(c, "×", rx - 24f, ry + 45f, 26f, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
        }
        c.restore();
    }

    private void drawRightLyrics(Canvas c, float lx, float top, float rx, float bot) {
        float w = rx - lx;
        float listH = Math.max(40f, bot - top);
        Lyrics.Sheet sh = sv.currentLyrics;
        if (sh == null || sh.lines.isEmpty()) {
            String msg = sv.lyricsLoading ? "Loading synced lyrics..."
                    : (sv.lyricsMsg.isEmpty() ? "Tap to fetch lyrics" : sv.lyricsMsg);
            Skin.text(c, Skin.fit(msg, 18.5f, w, Theme.TF_MEDIUM),
                    (lx + rx) * 0.5f, top + listH * 0.45f, 18.5f, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MEDIUM, false);
            return;
        }
        long pos = sv.pl != null ? sv.pl.getCurrentPosition() : 0L;
        int active = sh.synced ? sh.activeIndex(pos) : -1;
        float maxSc = Math.max(0f, sh.lines.size() * LYRIC_ROW - listH + 40f);
        if (sh.synced && active >= 0 && wingScrollMode != 2) {
            float target = Math.max(0f, Math.min(maxSc, active * LYRIC_ROW - listH * 0.35f));
            rightScroll += (target - rightScroll) * 0.22f;
        }
        rightScroll = Math.max(0f, Math.min(maxSc, rightScroll));

        c.save();
        c.clipRect(lx - 4, top, rx + 4, bot);
        int first = Math.max(0, (int) (rightScroll / LYRIC_ROW));
        for (int i = first; i < sh.lines.size(); i++) {
            float ry = top + i * LYRIC_ROW - rightScroll;
            if (ry > bot) break;
            Lyrics.Line line = sh.lines.get(i);
            boolean hot = (i == active);
            if (hot) {
                rTmp.set(lx, ry + 2, rx, ry + LYRIC_ROW - 4);
                Q.setColor(Theme.alpha(Theme.primary(), 45));
                c.drawRoundRect(rTmp, 10f, 10f, Q);
            }
            Skin.text(c, Skin.fit(line.text, hot ? 20f : 17.5f, w - 12f, hot ? Theme.TF_BOLD : Theme.TF_MEDIUM),
                    (lx + rx) * 0.5f, ry + LYRIC_ROW * 0.62f, hot ? 20f : 17.5f,
                    hot ? 0xffffffff : Theme.TEXT_SUB, Paint.Align.CENTER, hot ? Theme.TF_BOLD : Theme.TF_MEDIUM, false);
        }
        c.restore();
    }

    // ---- 1. Y2K LIQUID CHROME CYBER DECK ("whale")
    private void drawCyberDeckOverlay(Canvas c, Hud x) {
        if (sk.lcd != null) {
            RectF r = sk.lcd;
            c.save();
            clip.reset();
            clip.addRoundRect(r.left + 4, r.top + 4, r.right - 4, r.bottom - 4, 48f, 48f, Path.Direction.CW);
            c.clipPath(clip);

            Q.setColor(0xe6071224);
            c.drawRect(r, Q);

            float pad = 18f;
            float artS = r.height() - pad * 2f;
            rArt.set(r.left + pad, r.top + pad, r.left + pad + artS, r.top + pad + artS);
            drawAlbumTile(c, rArt, sv.artRaw(), 18f);

            float tx = rArt.right + 16f;
            float rr = r.right - pad;
            float tw = Math.max(60f, rr - tx);

            c.save();
            c.clipRect(tx, r.top, rr, r.bottom);
            Skin.marquee(c, x.title, tx, r.top + 46f, 29f, Theme.TEXT_MAIN, tw, Theme.TF_BOLD, false);
            String sub = (x.artist.isEmpty() ? "EARWAX 4.5" : x.artist) + (x.badge.isEmpty() ? "" : ("  •  " + x.badge));
            Skin.text(c, Skin.fit(sub, 17.5f, tw, Theme.TF_MEDIUM), tx, r.top + 74f, 17.5f, Theme.glow(), Paint.Align.LEFT, Theme.TF_MEDIUM, false);
            c.restore();

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

            float sy = r.bottom - 36f;
            float frac = (x.dur > 0 && !x.live) ? Math.max(0f, Math.min(1f, x.pos / (float) x.dur)) : 0f;
            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(7f);
            S.setColor(0xff132742);
            c.drawLine(tx, sy, rr, sy, S);
            if (frac > 0.002f) {
                S.setColor(Theme.primary());
                c.drawLine(tx, sy, tx + tw * frac, sy, S);
            }
            Q.setColor(0xffffffff);
            c.drawCircle(tx + tw * frac, sy, 8f, Q);

            Skin.text(c, Skin.mmss(x.pos), tx, r.bottom - 10f, 16f, Theme.glow(), Paint.Align.LEFT, Theme.TF_MONO, false);
            Skin.text(c, x.live ? "LIVE" : Skin.mmss(x.dur), rr, r.bottom - 10f, 16f, Theme.TEXT_SUB, Paint.Align.RIGHT, Theme.TF_MONO, false);

            Q.setColor(0x16ffffff);
            c.drawRect(r.left, r.top, r.right, r.top + r.height() * 0.44f, Q);
            c.restore();
        }

        if (sk.knob != null) {
            float kx = sk.knob.cx, ky = sk.knob.cy, kr = sk.knob.r;
            Skin.text(c, x.tool, kx, ky - kr - 18f, 18.5f, Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, false);

            rTmp.set(kx - kr * 1.14f, ky - kr * 1.14f, kx + kr * 1.14f, ky + kr * 1.14f);
            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(7.5f);
            S.setColor(0x40102844);
            c.drawArc(rTmp, -225, 270, false, S);

            float v = Math.max(0.01f, Math.min(1f, x.toolNorm));
            S.setColor(Theme.primary());
            c.drawArc(rTmp, -225, 270f * v, false, S);

            double a = Math.toRadians(-135.0 + 270.0 * v);
            float sx = (float) Math.sin(a), cy = -(float) Math.cos(a);
            S.setStrokeWidth(5f);
            S.setColor(rotating ? 0xffffffff : 0xff0d2340);
            c.drawLine(kx + sx * kr * 0.30f, ky + cy * kr * 0.30f, kx + sx * kr * 0.72f, ky + cy * kr * 0.72f, S);
            Q.setColor(Theme.primary());
            c.drawCircle(kx + sx * kr * 0.68f, ky + cy * kr * 0.68f, 8.5f, Q);
            Q.setColor(0xffffffff);
            c.drawCircle(kx + sx * kr * 0.68f, ky + cy * kr * 0.68f, 4f, Q);

            Skin.text(c, x.toolText, kx, ky + kr + 25f, 18.5f, Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_MONO, false);
        }

        if (sk.meter != null) {
            RectF m = sk.meter;
            float v = Math.max(0.05f, Math.min(1f, x.toolNorm));
            float top = m.bottom - (m.height() - 8f) * v;
            Q.setColor(Theme.primary());
            rTmp.set(m.left + 4, top, m.right - 4, m.bottom - 4);
            c.drawRoundRect(rTmp, m.width() * 0.4f, m.width() * 0.4f, Q);
        }

        for (Skin.Btn b : sk.btns) {
            if (b == sk.knob) continue;
            boolean pr = (b == down);
            if ("drawers".equals(b.id)) {
                drawTopDrawerPill(c, b, pr, 72f, 24f);
            } else if (b.id.startsWith("t_")) {
                int tIdx = "t_vol".equals(b.id) ? 0 : ("t_pitch".equals(b.id) ? 1 : 2);
                boolean active = (sv.tool == tIdx);
                String lbl = tIdx == 0 ? "VOL" : (tIdx == 1 ? "PITCH" : "SPEED");
                float hw = b.r * 1.44f, hh = b.r * 0.72f;
                rTmp.set(b.cx - hw, b.cy - hh, b.cx + hw, b.cy + hh);
                if (active || pr) {
                    Q.setColor(Theme.primary());
                    c.drawRoundRect(rTmp, hh, hh, Q);
                    S.setStrokeWidth(2f);
                    S.setColor(Theme.glow());
                    c.drawRoundRect(rTmp, hh, hh, S);
                }
                Skin.text(c, lbl, b.cx, b.cy + 6.5f, 17.5f,
                        active || pr ? 0xff03101d : Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, false);
            } else {
                drawJewelGlyph(c, b, pr, "play".equals(b.id), x);
            }
        }
    }

    // ---- 2. Y2K CHROME SONIC ORB ("orb")
    private void drawOrbOverlay(Canvas c, Hud x) {
        if (sk.lcd != null) {
            float cx = sk.lcd.centerX(), cy = sk.lcd.centerY(), r = sk.lcd.width() * 0.5f;

            if (x.dur > 0 && !x.live) {
                rTmp.set(cx - r - 6, cy - r - 6, cx + r + 6, cy + r + 6);
                S.setStrokeWidth(5f);
                S.setStrokeCap(Paint.Cap.ROUND);
                S.setColor(0x40122c4a);
                c.drawArc(rTmp, 0, 360, false, S);
                S.setColor(Theme.primary());
                c.drawArc(rTmp, -90, 360f * Math.min(1f, x.pos / (float) x.dur), false, S);
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
                    cx, cy - r * 0.56f, 17f, Theme.primary(), Paint.Align.CENTER, Theme.TF_BOLD, false);
            Skin.text(c, Skin.mmss(x.pos), cx, cy - r * 0.04f, 54f, Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_MONO, false);

            if (sk.info != null) {
                c.save();
                c.clipRect(sk.info);
                Skin.marquee(c, x.title, sk.info.left, sk.info.centerY() + 7f, 21f, Theme.glow(), sk.info.width(), Theme.TF_BOLD, false);
                c.restore();
            }

            if (sk.spec != null) {
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

            Q.setColor(0x16ffffff);
            c.drawRect(cx - r, cy - r, cx + r, cy, Q);
            c.restore();
        }

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
            S.setColor(Theme.primary());
            c.drawArc(rTmp, start, sweep * v, false, S);

            double a = Math.toRadians(d.a0 + sweep * v);
            float tx = d.cx + (float) Math.sin(a) * rm;
            float ty = d.cy - (float) Math.cos(a) * rm;
            Q.setColor(Theme.alpha(Theme.primary(), 145));
            c.drawCircle(tx, ty, trackW * (rotating ? 0.95f : 0.78f), Q);
            Q.setColor(0xffffffff);
            c.drawCircle(tx, ty, trackW * 0.46f, Q);

            Skin.text(c, x.tool + " " + x.toolText, d.cx, d.cy + rm * 0.99f, 22f,
                    Theme.glow(), Paint.Align.CENTER, Theme.TF_MONO, false);
        }

        for (Skin.Btn b : sk.btns) {
            drawJewelGlyph(c, b, b == down, "play".equals(b.id) || ("drawers".equals(b.id) && drawersOpen), x);
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

            Q.setColor(0xe6071224);
            c.drawRect(r, Q);

            if (x.dur > 0 && !x.live) {
                float f = Math.max(0f, Math.min(1f, x.pos / (float) x.dur));
                Q.setColor(Theme.primary());
                c.drawRect(r.left, r.bottom - 7f, r.left + r.width() * f, r.bottom, Q);
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
            Skin.marquee(c, x.title, tx, r.centerY() - 10f, 26.5f, Theme.TEXT_MAIN, tw, Theme.TF_BOLD, false);
            String sub = x.artist.isEmpty() ? x.badge : x.artist;
            Skin.text(c, Skin.fit(sub, 18.5f, tw - 76f, Theme.TF_MEDIUM), tx, r.centerY() + 20f, 18.5f, Theme.glow(), Paint.Align.LEFT, Theme.TF_MEDIUM, false);

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

            Q.setColor(0x16ffffff);
            c.drawRect(r.left, r.top, r.right, r.centerY(), Q);
            c.restore();
        }

        for (Skin.Btn b : sk.btns) {
            if ("drawers".equals(b.id)) {
                drawTopDrawerPill(c, b, b == down, 58f, 21f);
            } else {
                drawJewelGlyph(c, b, b == down, "play".equals(b.id), x);
            }
        }
    }

    private void drawTopDrawerPill(Canvas c, Skin.Btn b, boolean pressed, float hw, float hh) {
        if (drawersOpen || pressed) {
            rTmp.set(b.cx - hw, b.cy - hh, b.cx + hw, b.cy + hh);
            S.setStyle(Paint.Style.STROKE);
            S.setStrokeWidth(pressed ? 4.0f : 3.0f);
            S.setColor(pressed ? 0xffffffff : Theme.primary());
            c.drawRoundRect(rTmp, hh, hh, S);
        }
        String label = drawersOpen ? "▼ 3D" : "◄ 3D ►";
        Skin.text(c, label, b.cx, b.cy + 7f, 20f, 0xffffffff, Paint.Align.CENTER, Theme.TF_BOLD, false);
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
            S.setColor(Theme.alpha(glowCol, pressed ? 245 : 210));
            c.drawCircle(b.cx, b.cy, rr * 1.04f, S);
        }
        Skin.glyph(c, b.id, b.cx, b.cy, rr * 0.90f, x.playing, 0xffffffff, x.repeat, false);
    }

    // ------------------------------------------------------------------ Floating Overlay Search Dialog
    private void openOverlaySearch(final boolean isYt) {
        closeSearchOverlay();
        Context themed = new ContextThemeWrapper(sv, android.R.style.Theme_DeviceDefault);

        LinearLayout outer = new LinearLayout(themed);
        outer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xf410223d, 0xf6071122});
        bg.setCornerRadius(18 * dm.density);
        bg.setStroke((int) (1.8f * dm.density), isYt ? Theme.YT_BADGE : Theme.primary());
        outer.setBackground(bg);
        int p = (int) (14 * dm.density);
        outer.setPadding(p, p, p, p);

        TextView title = new TextView(themed);
        title.setText(isYt ? "Search YouTube Music" : "Filter Library");
        title.setTextColor(Theme.TEXT_MAIN);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Theme.TF_BOLD);
        outer.addView(title);

        EditText et = new EditText(themed);
        et.setSingleLine(true);
        et.setHint(isYt ? "Song, artist, remix..." : "Track or artist...");
        et.setText(isYt ? leftYtQuery : leftLibQuery);
        et.setSelection(et.getText().length());
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        et.setTextColor(Color.WHITE);
        et.setHintTextColor(Theme.TEXT_SUB);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setColor(0xff06101f);
        fbg.setCornerRadius(12 * dm.density);
        fbg.setStroke((int) (1.2f * dm.density), Theme.primary());
        et.setBackground(fbg);
        int fp = (int) (10 * dm.density);
        et.setPadding(fp, fp, fp, fp);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = (int) (8 * dm.density);
        elp.bottomMargin = (int) (8 * dm.density);
        outer.addView(et, elp);

        Runnable submit = () -> {
            String q = et.getText().toString().trim();
            closeSearchOverlay();
            if (isYt) {
                if (q.isEmpty()) loadLeftYtMood(leftYtMood);
                else searchLeftYt(q);
            } else {
                leftLibQuery = q;
                leftScroll = 0f;
                invalidate();
            }
        };

        et.setOnEditorActionListener((v, act, ev) -> {
            if (act == EditorInfo.IME_ACTION_SEARCH || act == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submit.run();
                return true;
            }
            return false;
        });

        // Quick search history chips for YT Music
        if (isYt) {
            ArrayList<String> hist = Prefs.searchHistory(sv);
            if (!hist.isEmpty()) {
                HorizontalScrollView hsv = new HorizontalScrollView(themed);
                hsv.setHorizontalScrollBarEnabled(false);
                LinearLayout chips = new LinearLayout(themed);
                chips.setOrientation(LinearLayout.HORIZONTAL);
                for (String s : hist) {
                    TextView ch = new TextView(themed);
                    ch.setText(s);
                    ch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    ch.setTextColor(Theme.glow());
                    ch.setTypeface(Theme.TF_BOLD);
                    GradientDrawable cbg = new GradientDrawable();
                    cbg.setColor(0xff132744);
                    cbg.setCornerRadius(12 * dm.density);
                    ch.setBackground(cbg);
                    ch.setPadding((int) (10 * dm.density), (int) (5 * dm.density), (int) (10 * dm.density), (int) (5 * dm.density));
                    ch.setOnClickListener(v -> {
                        closeSearchOverlay();
                        searchLeftYt(s);
                    });
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    clp.rightMargin = (int) (6 * dm.density);
                    chips.addView(ch, clp);
                }
                hsv.addView(chips);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                hlp.bottomMargin = (int) (8 * dm.density);
                outer.addView(hsv, hlp);
            }
        }

        LinearLayout btns = new LinearLayout(themed);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);

        TextView bCancel = makeOverlayBtn(themed, "CANCEL", false);
        bCancel.setOnClickListener(v -> closeSearchOverlay());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.rightMargin = (int) (8 * dm.density);
        btns.addView(bCancel, blp);

        TextView bGo = makeOverlayBtn(themed, "SEARCH", true);
        bGo.setOnClickListener(v -> submit.run());
        btns.addView(bGo);
        outer.addView(btns);

        WindowManager.LayoutParams slp = new WindowManager.LayoutParams(
                (int) (dm.widthPixels * 0.88f),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        slp.dimAmount = 0.55f;
        slp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        slp.y = (int) (64 * dm.density);
        slp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;

        searchOverlay = outer;
        try {
            wm.addView(searchOverlay, slp);
            et.requestFocus();
            et.post(() -> {
                InputMethodManager imm = (InputMethodManager) sv.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
            });
        } catch (Exception e) {
            searchOverlay = null;
        }
    }

    private TextView makeOverlayBtn(Context c, String label, boolean primary) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(Theme.TF_BOLD);
        tv.setTextColor(primary ? 0xff03101d : Theme.TEXT_MAIN);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? Theme.primary() : 0xff172a46);
        g.setCornerRadius(12 * dm.density);
        tv.setBackground(g);
        tv.setPadding((int) (14 * dm.density), (int) (8 * dm.density), (int) (14 * dm.density), (int) (8 * dm.density));
        return tv;
    }

    private void closeSearchOverlay() {
        if (searchOverlay != null) {
            try { wm.removeView(searchOverlay); } catch (Exception ignored) { }
            searchOverlay = null;
        }
    }

    // ------------------------------------------------------------------ Wing Touch Handling
    private void tapLeftWing(float dx, float dy) {
        if (!dScreen.contains(dx, dy)) return;
        float pad = 16f;
        float lx = dScreen.left + pad, rx = dScreen.right - pad, w = rx - lx;
        float y = dScreen.top + 16f;

        // 1. Top source tabs [YT | LIB | RAD]
        if (dy >= y && dy <= y + 48f) {
            int i = (int) ((dx - lx) / (w / 3f));
            if (i >= 0 && i < 3) {
                leftTab = i;
                leftScroll = 0f;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (i == 0 && leftYtTracks.isEmpty() && !leftYtLoading) loadLeftYtMood(leftYtMood);
                if (i == 2 && leftRadTracks.isEmpty() && !leftRadLoading) loadLeftRadio(leftRadGenre);
                invalidate();
            }
            return;
        }
        y += 54f;

        // 2. Search Bar (YT or LIB)
        if (leftTab == 0 || leftTab == 1) {
            if (dy >= y && dy <= y + 52f) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                String curQ = leftTab == 0 ? leftYtQuery : leftLibQuery;
                if (!curQ.isEmpty() && dx > rx - 56f) {
                    if (leftTab == 0) loadLeftYtMood(leftYtMood);
                    else { leftLibQuery = ""; leftScroll = 0f; invalidate(); }
                } else {
                    openOverlaySearch(leftTab == 0);
                }
                return;
            }
            y += 58f;
        }

        // 3. Context Pills
        if (dy >= y && dy <= y + 40f) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (leftTab == 0) {
                int[] moodMap = {0, 1, 2, 7};
                int idx = Math.max(0, Math.min(3, (int) ((dx - lx) / (w / 4f))));
                loadLeftYtMood(moodMap[idx]);
            } else if (leftTab == 1) {
                leftLibSub = Math.max(0, Math.min(2, (int) ((dx - lx) / (w / 3f))));
                leftScroll = 0f;
                invalidate();
            } else {
                int[] rgMap = {0, 1, 2, 7};
                int idx = Math.max(0, Math.min(3, (int) ((dx - lx) / (w / 4f))));
                loadLeftRadio(rgMap[idx]);
            }
            return;
        }
        y += 44f;

        // 4. Mini Track List tap
        List<Track> list = leftVisibleTracks();
        if (dy < y || list.isEmpty()) return;
        int idx = (int) ((dy - y + leftScroll) / DRAWER_ROW);
        if (idx < 0 || idx >= list.size()) return;
        Track t = list.get(idx);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

        if (t.isYouTube() && dx >= rx - 68f) {
            if (sv.isOffline(t)) {
                Toast.makeText(sv, "Already saved offline", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(sv, "Downloading: " + t.title, Toast.LENGTH_SHORT).show();
                sv.downloadTrack(t, (off, err) -> {
                    Toast.makeText(sv, err != null ? err : ("Saved offline: " + t.title), Toast.LENGTH_SHORT).show();
                    invalidate();
                });
            }
            return;
        }
        String key = leftTab == 0 ? ("pop_yt|" + leftYtQuery + "|" + leftYtMood)
                : (leftTab == 1 ? ("pop_lib|" + leftLibSub + "|" + leftLibQuery) : ("pop_rad|" + leftRadGenre));
        sv.playList(list, idx, key);
        invalidate();
    }

    private void tapRightWing(float dx, float dy) {
        if (!dScreen.contains(dx, dy)) return;
        float pad = 16f;
        float lx = dScreen.left + pad, rx = dScreen.right - pad, w = rx - lx;
        float y = dScreen.top + 16f;

        // 1. Top sub-tabs [TOOLS | QUEUE | LYRICS]
        if (dy >= y && dy <= y + 48f) {
            int i = (int) ((dx - lx) / (w / 3f));
            if (i >= 0 && i < 3) {
                rightTab = i;
                rightScroll = 0f;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (i == 2) sv.loadLyricsForCurrent();
                invalidate();
            }
            return;
        }
        y += 56f;

        if (rightTab == 0) {
            tapRightTools(dx, dy, lx, y, rx);
        } else if (rightTab == 1) {
            if (dy < y + 44f) {
                if (dx >= rx - 110f) {
                    sv.shuffleQueueNow();
                    Toast.makeText(sv, "Queue shuffled", Toast.LENGTH_SHORT).show();
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    invalidate();
                }
                return;
            }
            int idx = (int) ((dy - (y + 46f) + rightScroll) / DRAWER_ROW);
            if (idx >= 0 && idx < sv.queue.size()) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (dx >= rx - 54f) sv.removeFromQueue(idx);
                else sv.jumpToQueue(idx);
                invalidate();
            }
        } else {
            Lyrics.Sheet sh = sv.currentLyrics;
            if (sh == null || sh.lines.isEmpty()) {
                sv.lyricsKey = "";
                sv.loadLyricsForCurrent();
                return;
            }
            if (sh.synced) {
                int idx = (int) ((dy - y + rightScroll) / LYRIC_ROW);
                if (idx >= 0 && idx < sh.lines.size()) {
                    long ms = sh.lines.get(idx).timeMs;
                    if (ms >= 0) {
                        sv.seekToMs(ms);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
            }
        }
    }

    private void tapRightTools(float dx, float dy, float lx, float top, float rx) {
        float w = rx - lx;
        float gap = 10f, halfW = (w - gap) * 0.5f, btnH = 56f;
        float y = top + 82f;
        boolean rightCol = dx > lx + halfW + gap * 0.5f;
        Track cur = sv.current();
        boolean isYt = cur != null && (cur.isYouTube() || cur.isOfflineYtm());

        if (dy < y) return;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

        if (isYt) {
            if (dy < y + btnH) {
                if (sv.isOffline(cur)) {
                    Toast.makeText(sv, "Already saved offline", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(sv, "Downloading: " + cur.title, Toast.LENGTH_SHORT).show();
                    sv.downloadTrack(cur, (off, err) -> {
                        Toast.makeText(sv, err != null ? err : ("Saved offline: " + cur.title), Toast.LENGTH_SHORT).show();
                        invalidate();
                    });
                }
                return;
            }
            y += btnH + gap;
            if (dy < y + btnH) {
                if (!rightCol) {
                    sv.playYtWithRadio(cur);
                    Toast.makeText(sv, "Starting YT Auto-Radio…", Toast.LENGTH_SHORT).show();
                } else {
                    sv.toggleFav(cur);
                }
                invalidate();
                return;
            }
            y += btnH + gap;
        } else {
            if (dy < y + btnH) {
                if (!rightCol) sv.toggleFav(cur);
                else { rightTab = 2; sv.loadLyricsForCurrent(); }
                invalidate();
                return;
            }
            y += btnH + gap;
        }

        if (dy < y + btnH) {
            if (!rightCol) sv.toggleShuffle();
            else sv.cycleRepeat();
            invalidate();
            return;
        }
        y += btnH + gap;

        if (dy < y + btnH) {
            if (!rightCol) sv.cyclePreset();
            else sv.cycleSleep();
            invalidate();
            return;
        }
        y += btnH + gap;

        if (dy < y + btnH) {
            if (!rightCol) sv.selectTheme((Theme.index() + 1) % Theme.PALETTES.length);
            else sv.openMainApp(-1);
            invalidate();
        }
    }

    // ------------------------------------------------------------------ Core Popup Touch Handling
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

    private Skin.Btn hitCoreButton(float x, float y) {
        // Generous hit area for top "drawers" pill button on whale/pill!
        Skin.Btn dr = sk.find("drawers");
        if (dr != null && !"orb".equals(skinName)) {
            if (Math.abs(x - dr.cx) <= 84f && Math.abs(y - dr.cy) <= 36f) return dr;
        }
        return sk.hit(x, y);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        computeSubRects();
        if (!drawersOpen) {
            pinch.onTouchEvent(e);
            if (pinch.isInProgress() || e.getPointerCount() > 1) {
                scaling = true; down = null; rotating = false; seek = false; rot.up();
                return true;
            }
        }

        float vx = e.getX(), vy = e.getY();
        float sc = coreRect.width() > 0 ? (coreRect.width() / (float) sk.w) : 1f;
        float x = (vx - coreRect.left) / sc;
        float y = (vy - coreRect.top) / sc;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scaling = false; drag = false; seek = false; rotating = false; rotMoved = false;
                wingScrollMode = 0; wingMoved = false;
                rx = e.getRawX(); ry = e.getRawY();
                downRawX = rx; downRawY = ry;
                downDesignX = x; downDesignY = y;
                lastWingY = vy;

                // Check if touch landed inside Left or Right 3D Wing Drawer!
                if (drawersOpen && drawerAnim > 0.5f && leftWingRect.contains(vx, vy)) {
                    wingScrollMode = 1;
                    return true;
                }
                if (drawersOpen && drawerAnim > 0.5f && rightWingRect.contains(vx, vy)) {
                    wingScrollMode = 2;
                    return true;
                }

                down = hitCoreButton(x, y);
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
                wakeLoop();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scaling) return true;
                if (wingScrollMode == 1 || wingScrollMode == 2) {
                    float dy = lastWingY - vy;
                    lastWingY = vy;
                    if (Math.hypot(e.getRawX() - downRawX, e.getRawY() - downRawY) > slop) {
                        wingMoved = true;
                    }
                    if (wingMoved) {
                        float scaleY = dH / Math.max(1f, wingScrollMode == 1 ? leftWingRect.height() : rightWingRect.height());
                        if (wingScrollMode == 1) leftScroll += dy * scaleY;
                        else rightScroll += dy * scaleY;
                        invalidate();
                    }
                    return true;
                }

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
                } else if (down == null || hitCoreButton(x, y) != down) {
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
                if (wingScrollMode == 1) {
                    if (!wingMoved && leftWingRect.contains(vx, vy)) {
                        float dxW = (vx - leftWingRect.left) * (dW / leftWingRect.width());
                        float dyW = (vy - leftWingRect.top) * (dH / leftWingRect.height());
                        tapLeftWing(dxW, dyW);
                    }
                    wingScrollMode = 0; wingMoved = false;
                    return true;
                }
                if (wingScrollMode == 2) {
                    if (!wingMoved && rightWingRect.contains(vx, vy)) {
                        float dxW = (vx - rightWingRect.left) * (dW / rightWingRect.width());
                        float dyW = (vy - rightWingRect.top) * (dH / rightWingRect.height());
                        tapRightWing(dxW, dyW);
                    }
                    wingScrollMode = 0; wingMoved = false;
                    return true;
                }

                if (!scaling && !drag) {
                    if (down != null && hitCoreButton(x, y) == down) {
                        if ("drawers".equals(down.id)) {
                            toggleDrawers();
                        } else {
                            sv.popupAction(down.id);
                        }
                    } else if (rotating && !rotMoved && sk.knob != null) {
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
                wakeLoop();
                return true;

            case MotionEvent.ACTION_CANCEL:
                down = null; rotating = false; seek = false; drag = false; wingScrollMode = 0; wingMoved = false; rot.up();
                invalidate();
                return true;
        }
        return true;
    }
}
