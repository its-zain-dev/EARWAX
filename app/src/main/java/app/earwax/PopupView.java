package app.earwax;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.OverScroller;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * EARWAX 4.6 Razor-Sharp, Zero-Lag 3D Floating Overlay Popup + Independent Twin Wing Drawers!
 *
 * Key upgrades:
 *  - True 1:1 native display-pixel rendering for ALL buttons, icons, borders & text (zero Canvas.scale() blur
 *    and zero permanent Camera.rotateY() projective pixelation!)
 *  - Independent compact WindowManager overlay windows for Core + Left Drawer + Right Drawer:
 *      * Empty space never steals clicks/scrolls from other apps/windows
 *      * Drawers are 100% event-driven (0fps idle!) with native OverScroller fling scrolling
 *      * Core popup runs a lean 20fps tick ONLY while playing (0fps when paused) -> zero lag in other windows!
 *  - Audio Quality cycler + Download at selected quality built directly into Right Wing Tools
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

    // Independent Wing Drawer Windows (zero dead-space hitblocking, zero cross-window lag!)
    private WingPanelView leftWingView, rightWingView;
    private WindowManager.LayoutParams leftWingLp, rightWingLp;
    private ValueAnimator wingAnim;
    private float wingProgress = 0f;
    private boolean wingsAttached = false;
    private boolean drawersOpen = false;
    private int lastActiveLyricIdx = -1;

    private final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint S = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint B = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Path clip = new Path();
    private final Path artClip = new Path();
    private final RectF rTmp = new RectF(), rArt = new RectF(), rDst = new RectF();

    // Left Wing state (0 = YT Mini-Page, 1 = LIB, 2 = RAD)
    private int leftTab = 0;
    private int leftYtMood = 0;
    private int leftLibSub = 0; // 0=ALL, 1=OFFLINE, 2=FAVS
    private int leftRadGenre = 0;
    private String leftYtQuery = "";
    private String leftLibQuery = "";
    private ArrayList<Track> leftYtTracks = new ArrayList<>();
    private ArrayList<Track> leftRadTracks = new ArrayList<>();
    private List<Track> leftCachedList = new ArrayList<>();
    private boolean leftListDirty = true;
    private int seenLibVer = -1, seenFavVer = -1, seenOffVer = -1;
    private boolean leftYtLoading, leftRadLoading;
    private String leftMsg = "";

    // Right Wing state (0 = TOOLS, 1 = QUEUE, 2 = LYRICS)
    private int rightTab = 0;

    // Overlay search bar state
    private View searchOverlay;

    // Touch state for Core popup
    private Skin.Btn down;
    private boolean drag, seek, rotating, scaling, rotMoved, tickScheduled;
    private float rx, ry, downDesignX, downDesignY, lastTick;
    private long lastTapMs, lastKnobTapMs;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            tickScheduled = false;
            invalidate();
            // Only wake Right Wing during playback if viewing synced lyrics and the active line actually changed!
            if (drawersOpen && rightWingView != null && rightTab == 2) {
                Lyrics.Sheet sh = sv.currentLyrics;
                if (sh != null && sh.synced && sv.pl != null) {
                    int idx = sh.activeIndex(sv.pl.getCurrentPosition());
                    if (idx != lastActiveLyricIdx) {
                        lastActiveLyricIdx = idx;
                        rightWingView.scrollToLyric(idx);
                        rightWingView.invalidate();
                    }
                }
            }
            scheduleNextTickIfNeeded();
        }
    };

    PopupView(PlayerService s, String name) {
        super(s);
        sv = s;
        skinName = name;
        dm = s.getResources().getDisplayMetrics();
        baseDp = "orb".equals(name) ? 248f : ("pill".equals(name) ? 324f : 356f);
        scale = Math.max(0.65f, Math.min(1.85f, Prefs.popupScale(s, name)));
        // Decode popup & drawer skins to native device screen width (razor-sharp 1:1 without oversizing VRAM!)
        int popMaxW = Math.min(1440, Math.max(720, dm.widthPixels));
        int drawerMaxW = Math.min(888, Math.max(480, Math.round(dm.widthPixels * 0.72f)));
        sk = Skin.load(s, name, popMaxW);
        aspect = sk.h / (float) sk.w;
        slop = ViewConfiguration.get(s).getScaledTouchSlop();

        drawerL = Skin.loadBitmap(s, "skins/drawer_l.png", drawerMaxW);
        drawerR = Skin.loadBitmap(s, "skins/drawer_r.png", drawerMaxW);
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

        int wp = Math.min(dm.widthPixels - (int) (12 * dm.density), (int) (baseDp * scale * dm.density));
        lp = new WindowManager.LayoutParams(
                wp, Math.max(1, (int) (wp * aspect)),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.popupX(s, name, (int) (14 * dm.density));
        lp.y = Prefs.popupY(s, name, (int) (110 * dm.density));

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
        wakeLoop();
    }

    private void scheduleNextTickIfNeeded() {
        if (tickScheduled) return;
        boolean busy = sv.pl.isPlaying() || rotating || down != null || seek;
        if (busy) {
            tickScheduled = true;
            // Lean 20fps (50ms) visualizer refresh on compact core window only -> zero lag in other apps!
            postDelayed(tick, (rotating || seek) ? 20 : 50);
        }
    }

    private void wakeLoop() {
        postInvalidateOnAnimation();
        scheduleNextTickIfNeeded();
    }

    @Override
    public void onServiceUpdate() {
        wakeLoop();
        if (drawersOpen) {
            if (sv.libVer != seenLibVer || sv.favVer != seenFavVer || sv.offVer != seenOffVer) {
                leftListDirty = true;
            }
            if (leftWingView != null) leftWingView.invalidate();
            if (rightWingView != null) rightWingView.invalidate();
        }
    }

    void dismiss() {
        closeSearchOverlay();
        detachWingsImmediate();
        removeCallbacks(tick);
        tickScheduled = false;
        sv.removeListener(this);
        Prefs.popupPos(sv, skinName, lp.x, lp.y);
        Prefs.popupScale(sv, skinName, scale);
        try { wm.removeView(this); } catch (Exception ignored) { }
        sv.viewer(false);
        sk.release();
        if (drawerL != null && !drawerL.isRecycled()) drawerL.recycle();
        if (drawerR != null && !drawerR.isRecycled()) drawerR.recycle();
    }

    // ------------------------------------------------------------------ Independent Twin Wing Windows
    private void toggleDrawers() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (!drawersOpen) {
            drawersOpen = true;
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
            leftListDirty = true;
            if (rightTab == 2) sv.loadLyricsForCurrent();
            attachWingsAnimated();
        } else {
            closeSearchOverlay();
            drawersOpen = false;
            detachWingsAnimated();
        }
        invalidate();
    }

    private int[] computeWingMetrics() {
        int margin = (int) (6 * dm.density);
        int gap = (int) (6 * dm.density);
        int maxSpan = dm.widthPixels - margin * 2;
        int spanW = Math.min(maxSpan, Math.max(lp.width, (int) (386 * dm.density)));
        int wingW = Math.max(120, (spanW - gap) / 2);
        int wingH = Math.min((int) (dm.heightPixels * 0.44f), (int) (wingW * (dH / dW)));

        // Position wings cleanly directly under (or above if near screen bottom) the compact core popup
        int centerX = lp.x + lp.width / 2;
        int groupLeft = Math.max(margin, Math.min(dm.widthPixels - margin - (wingW * 2 + gap), centerX - (wingW * 2 + gap) / 2));
        int overlap = (int) (6 * dm.density);
        int wingY = lp.y + lp.height - overlap;
        if (wingY + wingH > dm.heightPixels - (int) (18 * dm.density)) {
            int aboveY = lp.y - wingH + overlap;
            if (aboveY >= (int) (24 * dm.density)) {
                wingY = aboveY;
            } else {
                wingY = Math.max((int) (24 * dm.density), dm.heightPixels - wingH - (int) (18 * dm.density));
            }
        }
        return new int[]{groupLeft, groupLeft + wingW + gap, wingY, wingW, wingH};
    }

    private WindowManager.LayoutParams makeWingLp(int x, int y, int w, int h) {
        WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        wlp.gravity = Gravity.TOP | Gravity.START;
        wlp.x = x;
        wlp.y = y;
        wlp.windowAnimations = 0;
        return wlp;
    }

    private void invalidateWings() {
        if (leftWingView != null) leftWingView.invalidate();
        if (rightWingView != null) rightWingView.invalidate();
    }

    private void animateWingsTo(final float target, long baseDurationMs, final boolean detachOnEnd) {
        if (wingAnim != null) {
            ValueAnimator prev = wingAnim;
            wingAnim = null;
            prev.cancel();
        }
        float start = wingProgress;
        float dist = Math.abs(target - start);
        if (dist < 0.005f) {
            wingProgress = target;
            invalidateWings();
            if (detachOnEnd && !drawersOpen) detachWingsImmediate();
            return;
        }
        long dur = Math.max(60L, (long) (baseDurationMs * dist));
        final ValueAnimator anim = ValueAnimator.ofFloat(start, target);
        anim.setDuration(dur);
        anim.setInterpolator(detachOnEnd ? new AccelerateInterpolator(1.5f) : new DecelerateInterpolator(2.2f));
        anim.addUpdateListener(a -> {
            wingProgress = (Float) a.getAnimatedValue();
            invalidateWings();
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (wingAnim != anim) return;
                wingAnim = null;
                wingProgress = target;
                invalidateWings();
                if (detachOnEnd && !drawersOpen) {
                    detachWingsImmediate();
                }
            }
        });
        wingAnim = anim;
        anim.start();
    }

    private void attachWingsAnimated() {
        if (!wingsAttached || leftWingView == null || rightWingView == null) {
            detachWingsImmediate();
            int[] m = computeWingMetrics();
            leftWingView = new WingPanelView(sv, true);
            rightWingView = new WingPanelView(sv, false);
            leftWingLp = makeWingLp(m[0], m[2], m[3], m[4]);
            rightWingLp = makeWingLp(m[1], m[2], m[3], m[4]);
            wingProgress = 0f;
            try {
                wm.addView(leftWingView, leftWingLp);
                wm.addView(rightWingView, rightWingLp);
                wingsAttached = true;
            } catch (Exception e) {
                detachWingsImmediate();
                drawersOpen = false;
                return;
            }
        } else {
            syncWingPositions();
        }
        animateWingsTo(1f, 220L, false);
    }

    private void detachWingsAnimated() {
        if (!wingsAttached || leftWingView == null || rightWingView == null) {
            detachWingsImmediate();
            return;
        }
        animateWingsTo(0f, 160L, true);
    }

    private void detachWingsImmediate() {
        if (wingAnim != null) {
            ValueAnimator prev = wingAnim;
            wingAnim = null;
            prev.cancel();
        }
        wingProgress = 0f;
        wingsAttached = false;
        if (leftWingView != null) {
            try { wm.removeViewImmediate(leftWingView); } catch (Exception e) {
                try { wm.removeView(leftWingView); } catch (Exception ignored) { }
            }
            leftWingView = null;
            leftWingLp = null;
        }
        if (rightWingView != null) {
            try { wm.removeViewImmediate(rightWingView); } catch (Exception e) {
                try { wm.removeView(rightWingView); } catch (Exception ignored) { }
            }
            rightWingView = null;
            rightWingLp = null;
        }
    }

    private void syncWingPositions() {
        if (!wingsAttached || leftWingView == null || rightWingView == null) return;
        int[] m = computeWingMetrics();
        leftWingLp.x = m[0]; leftWingLp.y = m[2]; leftWingLp.width = m[3]; leftWingLp.height = m[4];
        rightWingLp.x = m[1]; rightWingLp.y = m[2]; rightWingLp.width = m[3]; rightWingLp.height = m[4];
        try {
            wm.updateViewLayout(leftWingView, leftWingLp);
            wm.updateViewLayout(rightWingView, rightWingLp);
        } catch (Exception ignored) { }
    }

    private void resize(float k) {
        k = Math.max(0.65f, Math.min(1.85f, k));
        if (Math.abs(k - scale) < 0.004f) return;
        float cx = lp.x + lp.width / 2f, cy = lp.y + lp.height / 2f;
        scale = k;
        lp.width = Math.min(dm.widthPixels - (int) (12 * dm.density), (int) (baseDp * scale * dm.density));
        lp.height = Math.max(1, (int) (lp.width * aspect));
        lp.x = (int) (cx - lp.width / 2f);
        lp.y = (int) (cy - lp.height / 2f);
        try { wm.updateViewLayout(this, lp); } catch (Exception ignored) { }
        syncWingPositions();
    }

    private void keepOnScreen() {
        int margin = (int) (6 * dm.density);
        int snapDist = (int) (22 * dm.density);
        if (Math.abs(lp.x - margin) < snapDist) lp.x = margin;
        else if (Math.abs((lp.x + lp.width) - (dm.widthPixels - margin)) < snapDist)
            lp.x = dm.widthPixels - margin - lp.width;
        lp.x = Math.max(-lp.width / 4, Math.min(dm.widthPixels - lp.width * 3 / 4, lp.x));
        lp.y = Math.max(margin, Math.min(dm.heightPixels - lp.height / 2, lp.y));
        try { wm.updateViewLayout(this, lp); } catch (Exception ignored) { }
        syncWingPositions();
    }

    // ------------------------------------------------------------------ Data Loaders for Left Wing
    private void loadLeftYtMood(int m) {
        leftYtMood = m;
        leftYtQuery = "";
        leftYtLoading = true;
        leftMsg = "Loading " + YtMusic.MOODS[m] + "...";
        leftListDirty = true;
        if (leftWingView != null) { leftWingView.resetScroll(); leftWingView.invalidate(); }
        YtMusic.discover(m, (list, err) -> {
            leftYtLoading = false;
            leftYtTracks = new ArrayList<>(list);
            leftMsg = err == null ? "" : err;
            leftListDirty = true;
            if (leftWingView != null) leftWingView.invalidate();
        });
    }

    private void searchLeftYt(String q) {
        leftYtQuery = q;
        Prefs.addSearchHistory(sv, q);
        leftYtLoading = true;
        leftMsg = "Searching \"" + q + "\"...";
        leftYtTracks = new ArrayList<>();
        leftListDirty = true;
        if (leftWingView != null) { leftWingView.resetScroll(); leftWingView.invalidate(); }
        YtMusic.search(q, true, (list, err) -> {
            leftYtLoading = false;
            leftYtTracks = new ArrayList<>(list);
            leftMsg = err == null ? "" : err;
            leftListDirty = true;
            if (leftWingView != null) leftWingView.invalidate();
        });
    }

    private void loadLeftRadio(int g) {
        leftRadGenre = g;
        leftRadLoading = true;
        leftMsg = "Tuning Radio...";
        leftListDirty = true;
        if (leftWingView != null) { leftWingView.resetScroll(); leftWingView.invalidate(); }
        Radio.genre(g, (list, err) -> {
            leftRadLoading = false;
            leftRadTracks = new ArrayList<>(list);
            leftMsg = err == null ? "" : err;
            leftListDirty = true;
            if (leftWingView != null) leftWingView.invalidate();
        });
    }

    private List<Track> leftVisibleTracks() {
        if (!leftListDirty && sv.libVer == seenLibVer && sv.favVer == seenFavVer && sv.offVer == seenOffVer) {
            return leftCachedList;
        }
        seenLibVer = sv.libVer;
        seenFavVer = sv.favVer;
        seenOffVer = sv.offVer;
        leftListDirty = false;
        if (leftTab == 0) {
            leftCachedList = leftYtTracks;
            return leftCachedList;
        }
        if (leftTab == 2) {
            leftCachedList = leftRadTracks.isEmpty() ? Radio.curated() : leftRadTracks;
            return leftCachedList;
        }
        List<Track> src = leftLibSub == 1 ? sv.offlineList : (leftLibSub == 2 ? sv.favList : sv.lib);
        if (leftLibQuery.isEmpty()) {
            leftCachedList = src;
            return leftCachedList;
        }
        String q = leftLibQuery.toLowerCase(Locale.ROOT);
        ArrayList<Track> out = new ArrayList<>();
        for (Track t : src) {
            if (t.title.toLowerCase(Locale.ROOT).contains(q) || t.artist.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(t);
            }
        }
        leftCachedList = out;
        return leftCachedList;
    }

    // ------------------------------------------------------------------ Core Popup Native-Pixel Drawing (ZERO Canvas.scale blur!)
    @Override
    protected void onDraw(Canvas c) {
        if (sk.bmp == null || sk.bmp.isRecycled()) return;
        int vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        Skin.resetPaints();
        Q.setShader(null); Q.setAlpha(255); Q.clearShadowLayer(); Q.setStyle(Paint.Style.FILL);
        S.setShader(null); S.setAlpha(255); S.clearShadowLayer(); S.setStyle(Paint.Style.STROKE);

        // 1. Draw HD raymarched chrome hardware shell with mipmap filtering at exact view bounds
        rDst.set(0f, 0f, vw, vh);
        c.drawBitmap(sk.bmp, null, rDst, Skin.P);

        // 2. Render all UI overlays directly in 1:1 native display pixels (sc = vw / sk.w) -> RAZOR SHARP!
        float sc = vw / (float) sk.w;
        Hud x = sv.hud();
        if ("orb".equals(skinName)) drawOrbOverlay(c, x, sc);
        else if ("pill".equals(skinName)) drawPillOverlay(c, x, sc);
        else drawCyberDeckOverlay(c, x, sc);
    }

    private float sw(float designPx, float sc) {
        return Math.max(1.25f, designPx * sc);
    }

    // ---- 1. Y2K LIQUID CHROME CYBER DECK ("whale") in 1:1 native display pixels
    private void drawCyberDeckOverlay(Canvas c, Hud x, float sc) {
        if (sk.lcd != null) {
            RectF dr = sk.lcd;
            float l = dr.left * sc, t = dr.top * sc, r = dr.right * sc, b = dr.bottom * sc;
            float h = b - t;
            c.save();
            clip.reset();
            clip.addRoundRect(l + 4f * sc, t + 4f * sc, r - 4f * sc, b - 4f * sc, 48f * sc, 48f * sc, Path.Direction.CW);
            c.clipPath(clip);

            Q.setColor(0xe6071224);
            c.drawRect(l, t, r, b, Q);

            float pad = 18f * sc;
            float artS = h - pad * 2f;
            rArt.set(l + pad, t + pad, l + pad + artS, t + pad + artS);
            drawAlbumTile(c, rArt, sv.artRaw(), 18f * sc, sc);

            float tx = rArt.right + 16f * sc;
            float rr = r - pad;
            float tw = Math.max(40f, rr - tx);

            c.save();
            c.clipRect(tx, t, rr, b);
            Skin.marquee(c, x.title, tx, t + 46f * sc, 29f * sc, Theme.TEXT_MAIN, tw, Theme.TF_BOLD, false);
            String sub = (x.artist.isEmpty() ? "EARWAX 4.6" : x.artist) + (x.badge.isEmpty() ? "" : ("  •  " + x.badge));
            Skin.text(c, Skin.fit(sub, 17.5f * sc, tw, Theme.TF_MEDIUM), tx, t + 74f * sc, 17.5f * sc, Theme.glow(), Paint.Align.LEFT, Theme.TF_MEDIUM, false);
            c.restore();

            float wy = t + 106f * sc;
            int bars = 24;
            float bw = tw / bars;
            Q.setStyle(Paint.Style.FILL);
            for (int i = 0; i < bars; i++) {
                float amp = x.playing ? Math.max(0.08f, x.bars[(i * 32) / bars]) : 0.06f;
                float hh = amp * 20f * sc;
                Q.setColor(Theme.lerp(Theme.primary(), Theme.secondary(), i / (float) bars));
                c.drawRoundRect(tx + i * bw + 1.2f * sc, wy - hh, tx + (i + 1) * bw - 1.2f * sc, wy + hh, 2f * sc, 2f * sc, Q);
            }

            float sy = b - 36f * sc;
            float frac = (x.dur > 0 && !x.live) ? Math.max(0f, Math.min(1f, x.pos / (float) x.dur)) : 0f;
            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(sw(7f, sc));
            S.setColor(0xff132742);
            c.drawLine(tx, sy, rr, sy, S);
            if (frac > 0.002f) {
                S.setColor(Theme.primary());
                c.drawLine(tx, sy, tx + tw * frac, sy, S);
            }
            Q.setColor(0xffffffff);
            c.drawCircle(tx + tw * frac, sy, 8f * sc, Q);

            Skin.text(c, Skin.mmss(x.pos), tx, b - 10f * sc, 16f * sc, Theme.glow(), Paint.Align.LEFT, Theme.TF_MONO, false);
            Skin.text(c, x.live ? "LIVE" : Skin.mmss(x.dur), rr, b - 10f * sc, 16f * sc, Theme.TEXT_SUB, Paint.Align.RIGHT, Theme.TF_MONO, false);

            Q.setColor(0x16ffffff);
            c.drawRect(l, t, r, t + h * 0.44f, Q);
            c.restore();
        }

        if (sk.knob != null) {
            float kx = sk.knob.cx * sc, ky = sk.knob.cy * sc, kr = sk.knob.r * sc;
            Skin.text(c, x.tool, kx, ky - kr - 18f * sc, 18.5f * sc, Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, false);

            rTmp.set(kx - kr * 1.14f, ky - kr * 1.14f, kx + kr * 1.14f, ky + kr * 1.14f);
            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(sw(7.5f, sc));
            S.setColor(0x40102844);
            c.drawArc(rTmp, -225, 270, false, S);

            float v = Math.max(0.01f, Math.min(1f, x.toolNorm));
            S.setColor(Theme.primary());
            c.drawArc(rTmp, -225, 270f * v, false, S);

            double a = Math.toRadians(-135.0 + 270.0 * v);
            float sx = (float) Math.sin(a), cy = -(float) Math.cos(a);
            S.setStrokeWidth(sw(5f, sc));
            S.setColor(rotating ? 0xffffffff : 0xff0d2340);
            c.drawLine(kx + sx * kr * 0.30f, ky + cy * kr * 0.30f, kx + sx * kr * 0.72f, ky + cy * kr * 0.72f, S);
            Q.setColor(Theme.primary());
            c.drawCircle(kx + sx * kr * 0.68f, ky + cy * kr * 0.68f, 8.5f * sc, Q);
            Q.setColor(0xffffffff);
            c.drawCircle(kx + sx * kr * 0.68f, ky + cy * kr * 0.68f, 4f * sc, Q);

            Skin.text(c, x.toolText, kx, ky + kr + 25f * sc, 18.5f * sc, Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_MONO, false);
        }

        if (sk.meter != null) {
            RectF m = sk.meter;
            float ml = m.left * sc, mt = m.top * sc, mr = m.right * sc, mb = m.bottom * sc;
            float v = Math.max(0.05f, Math.min(1f, x.toolNorm));
            float top = mb - ((mb - mt) - 8f * sc) * v;
            Q.setColor(Theme.primary());
            rTmp.set(ml + 4f * sc, top, mr - 4f * sc, mb - 4f * sc);
            c.drawRoundRect(rTmp, (mr - ml) * 0.4f, (mr - ml) * 0.4f, Q);
        }

        for (Skin.Btn b : sk.btns) {
            if (b == sk.knob) continue;
            boolean pr = (b == down);
            float bx = b.cx * sc, by = b.cy * sc, br = b.r * sc;
            if ("drawers".equals(b.id)) {
                drawTopDrawerPill(c, bx, by, pr, 72f * sc, 24f * sc, sc);
            } else if (b.id.startsWith("t_")) {
                int tIdx = "t_vol".equals(b.id) ? 0 : ("t_pitch".equals(b.id) ? 1 : 2);
                boolean active = (sv.tool == tIdx);
                String lbl = tIdx == 0 ? "VOL" : (tIdx == 1 ? "PITCH" : "SPEED");
                float hw = br * 1.44f, hh = br * 0.72f;
                rTmp.set(bx - hw, by - hh, bx + hw, by + hh);
                if (active || pr) {
                    Q.setColor(Theme.primary());
                    c.drawRoundRect(rTmp, hh, hh, Q);
                    S.setStrokeWidth(sw(2f, sc));
                    S.setColor(Theme.glow());
                    c.drawRoundRect(rTmp, hh, hh, S);
                }
                Skin.text(c, lbl, bx, by + 6.5f * sc, 17.5f * sc,
                        active || pr ? 0xff03101d : Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, false);
            } else {
                drawJewelGlyph(c, b.id, bx, by, br, pr, "play".equals(b.id), x, sc);
            }
        }
    }

    // ---- 2. Y2K CHROME SONIC ORB ("orb") in 1:1 native display pixels
    private void drawOrbOverlay(Canvas c, Hud x, float sc) {
        if (sk.lcd != null) {
            float cx = sk.lcd.centerX() * sc, cy = sk.lcd.centerY() * sc, r = sk.lcd.width() * 0.5f * sc;

            if (x.dur > 0 && !x.live) {
                rTmp.set(cx - r - 6f * sc, cy - r - 6f * sc, cx + r + 6f * sc, cy + r + 6f * sc);
                S.setStrokeWidth(sw(5f, sc));
                S.setStrokeCap(Paint.Cap.ROUND);
                S.setColor(0x40122c4a);
                c.drawArc(rTmp, 0, 360, false, S);
                S.setColor(Theme.primary());
                c.drawArc(rTmp, -90, 360f * Math.min(1f, x.pos / (float) x.dur), false, S);
            }

            c.save();
            clip.reset();
            clip.addCircle(cx, cy, r - 4f * sc, Path.Direction.CW);
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
                    cx, cy - r * 0.56f, 17f * sc, Theme.primary(), Paint.Align.CENTER, Theme.TF_BOLD, false);
            Skin.text(c, Skin.mmss(x.pos), cx, cy - r * 0.04f, 54f * sc, Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_MONO, false);

            if (sk.info != null) {
                float il = sk.info.left * sc, it = sk.info.top * sc, ir = sk.info.right * sc, ib = sk.info.bottom * sc;
                c.save();
                c.clipRect(il, it, ir, ib);
                Skin.marquee(c, x.title, il, (it + ib) * 0.5f + 7f * sc, 21f * sc, Theme.glow(), ir - il, Theme.TF_BOLD, false);
                c.restore();
            }

            if (sk.spec != null) {
                float sl = sk.spec.left * sc, st = sk.spec.top * sc, sr = sk.spec.right * sc, sb = sk.spec.bottom * sc;
                int bars = 18;
                float bw = (sr - sl) / bars;
                float my = (st + sb) * 0.5f;
                Q.setStyle(Paint.Style.FILL);
                for (int i = 0; i < bars; i++) {
                    float amp = x.playing ? Math.max(0.08f, x.bars[(i * 32) / bars]) : 0.06f;
                    float hh = amp * (sb - st) * 0.46f;
                    Q.setColor(Theme.lerp(Theme.primary(), Theme.secondary(), i / (float) bars));
                    c.drawRoundRect(sl + i * bw + 1.2f * sc, my - hh,
                            sl + (i + 1) * bw - 1.2f * sc, my + hh, 2f * sc, 2f * sc, Q);
                }
            }

            Q.setColor(0x16ffffff);
            c.drawRect(cx - r, cy - r, cx + r, cy, Q);
            c.restore();
        }

        if (sk.dial != null) {
            Skin.Dial d = sk.dial;
            float dcx = d.cx * sc, dcy = d.cy * sc, dr0 = d.r0 * sc, dr1 = d.r1 * sc;
            float rm = (dr0 + dr1) * 0.5f;
            float trackW = (dr1 - dr0) * 0.44f;
            float sweep = d.a1 - d.a0;
            float start = d.a0 - 90f;
            rTmp.set(dcx - rm, dcy - rm, dcx + rm, dcy + rm);

            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(sw(2f, sc));
            S.setColor(0x669fd8ff);
            for (int i = 0; i <= 24; i++) {
                double a = Math.toRadians(d.a0 + sweep * i / 24f);
                float sx = (float) Math.sin(a), cy = -(float) Math.cos(a);
                c.drawLine(dcx + sx * (dr1 - 10f * sc), dcy + cy * (dr1 - 10f * sc),
                        dcx + sx * (dr1 - 3f * sc), dcy + cy * (dr1 - 3f * sc), S);
            }

            float v = Math.max(0.01f, Math.min(1f, x.toolNorm));
            S.setStrokeWidth(trackW);
            S.setColor(Theme.primary());
            c.drawArc(rTmp, start, sweep * v, false, S);

            double a = Math.toRadians(d.a0 + sweep * v);
            float tx = dcx + (float) Math.sin(a) * rm;
            float ty = dcy - (float) Math.cos(a) * rm;
            Q.setColor(Theme.alpha(Theme.primary(), 145));
            c.drawCircle(tx, ty, trackW * (rotating ? 0.95f : 0.78f), Q);
            Q.setColor(0xffffffff);
            c.drawCircle(tx, ty, trackW * 0.46f, Q);

            Skin.text(c, x.tool + " " + x.toolText, dcx, dcy + rm * 0.99f, 22f * sc,
                    Theme.glow(), Paint.Align.CENTER, Theme.TF_MONO, false);
        }

        for (Skin.Btn b : sk.btns) {
            drawJewelGlyph(c, b.id, b.cx * sc, b.cy * sc, b.r * sc, b == down,
                    "play".equals(b.id) || ("drawers".equals(b.id) && drawersOpen), x, sc);
        }
    }

    // ---- 3. Y2K LIQUID CHROME MINI-PILL ("pill") in 1:1 native display pixels
    private void drawPillOverlay(Canvas c, Hud x, float sc) {
        if (sk.lcd != null) {
            RectF dr = sk.lcd;
            float l = dr.left * sc, t = dr.top * sc, r = dr.right * sc, b = dr.bottom * sc;
            float h = b - t, cy = (t + b) * 0.5f;
            c.save();
            clip.reset();
            clip.addRoundRect(l + 3f * sc, t + 3f * sc, r - 3f * sc, b - 3f * sc, h * 0.5f, h * 0.5f, Path.Direction.CW);
            c.clipPath(clip);

            Q.setColor(0xe6071224);
            c.drawRect(l, t, r, b, Q);

            if (x.dur > 0 && !x.live) {
                float f = Math.max(0f, Math.min(1f, x.pos / (float) x.dur));
                Q.setColor(Theme.primary());
                c.drawRect(l, b - 7f * sc, l + (r - l) * f, b, Q);
            }

            float pad = 16f * sc;
            float artS = h - pad * 2f;
            rArt.set(l + pad + 10f * sc, t + pad, l + pad + 10f * sc + artS, t + pad + artS);
            drawAlbumTile(c, rArt, sv.artRaw(), artS * 0.5f, sc);

            float tx = rArt.right + 16f * sc;
            float tr = r - 22f * sc;
            float tw = Math.max(40f, tr - tx);
            c.save();
            c.clipRect(tx, t, tr, b);
            Skin.marquee(c, x.title, tx, cy - 10f * sc, 26.5f * sc, Theme.TEXT_MAIN, tw, Theme.TF_BOLD, false);
            String sub = x.artist.isEmpty() ? x.badge : x.artist;
            Skin.text(c, Skin.fit(sub, 18.5f * sc, tw - 76f * sc, Theme.TF_MEDIUM), tx, cy + 20f * sc, 18.5f * sc, Theme.glow(), Paint.Align.LEFT, Theme.TF_MEDIUM, false);

            int mb = 8;
            float mbW = (66f * sc) / mb;
            float mbBase = cy + 24f * sc;
            Q.setStyle(Paint.Style.FILL);
            for (int i = 0; i < mb; i++) {
                float amp = x.playing ? Math.max(0.12f, x.bars[i * 4]) : 0.10f;
                float bh = amp * 24f * sc;
                Q.setColor(Theme.lerp(Theme.primary(), Theme.secondary(), i / (float) mb));
                c.drawRoundRect(tr - 68f * sc + i * mbW + 1f * sc, mbBase - bh,
                        tr - 68f * sc + (i + 1) * mbW - 1f * sc, mbBase, 2f * sc, 2f * sc, Q);
            }
            c.restore();

            Q.setColor(0x16ffffff);
            c.drawRect(l, t, r, cy, Q);
            c.restore();
        }

        for (Skin.Btn b : sk.btns) {
            float bx = b.cx * sc, by = b.cy * sc, br = b.r * sc;
            if ("drawers".equals(b.id)) {
                drawTopDrawerPill(c, bx, by, b == down, 58f * sc, 21f * sc, sc);
            } else {
                drawJewelGlyph(c, b.id, bx, by, br, b == down, "play".equals(b.id), x, sc);
            }
        }
    }

    private void drawTopDrawerPill(Canvas c, float bx, float by, boolean pressed, float hw, float hh, float sc) {
        if (drawersOpen || pressed) {
            rTmp.set(bx - hw, by - hh, bx + hw, by + hh);
            S.setStyle(Paint.Style.STROKE);
            S.setStrokeWidth(sw(pressed ? 4.0f : 3.0f, sc));
            S.setColor(pressed ? 0xffffffff : Theme.primary());
            c.drawRoundRect(rTmp, hh, hh, S);
        }
        String label = drawersOpen ? "▼ 3D" : "◄ 3D ►";
        Skin.text(c, label, bx, by + 7f * sc, 20f * sc, 0xffffffff, Paint.Align.CENTER, Theme.TF_BOLD, false);
    }

    private void drawAlbumTile(Canvas c, RectF r, Bitmap bmp, float rad, float sc) {
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
        S.setStrokeWidth(sw(2f, sc));
        S.setColor(Theme.alpha(Theme.primary(), 185));
        c.drawRoundRect(r, rad, rad, S);
    }

    private void drawJewelGlyph(Canvas c, String id, float bx, float by, float br, boolean pressed, boolean primary, Hud x, float sc) {
        float rr = br * (pressed ? 0.91f : 1f);
        if (pressed) {
            Q.setStyle(Paint.Style.FILL);
            Q.setColor(0x55ffffff);
            c.drawCircle(bx, by, rr * 0.95f, Q);
        }
        if (pressed || (primary && x.playing)) {
            S.setStyle(Paint.Style.STROKE);
            S.setStrokeWidth(sw(pressed ? 4.2f : 2.8f, sc));
            int glowCol = pressed ? 0xffffffff : Theme.primary();
            S.setColor(Theme.alpha(glowCol, pressed ? 245 : 210));
            c.drawCircle(bx, by, rr * 1.04f, S);
        }
        Skin.glyph(c, id, bx, by, rr * 0.90f, x.playing, 0xffffffff, x.repeat, false);
    }

    // ------------------------------------------------------------------ Independent Event-Driven Wing Panel View!
    private final class WingPanelView extends View {
        private final boolean isLeft;
        private final Paint wQ = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wS = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wB = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final RectF wTmp = new RectF(), wFull = new RectF();
        private final OverScroller scroller;
        private VelocityTracker vt;
        private float scrollY = 0f;
        private float downX, downY, lastY;
        private boolean moved;

        WingPanelView(Context c, boolean left) {
            super(c);
            this.isLeft = left;
            this.scroller = new OverScroller(c);
            wS.setStyle(Paint.Style.STROKE);
            wQ.setStyle(Paint.Style.FILL);
        }

        void resetScroll() {
            scroller.forceFinished(true);
            scrollY = 0f;
        }

        void scrollToLyric(int activeIdx) {
            if (isLeft || rightTab != 2 || activeIdx < 0) return;
            int vh = getHeight();
            if (vh <= 0) return;
            float scY = vh / dH;
            float listTop = (dScreen.top + 16f + 56f) * scY;
            float listBot = (dScreen.bottom - 12f) * scY;
            float listH = Math.max(40f, listBot - listTop);
            float rowPx = LYRIC_ROW * scY;
            Lyrics.Sheet sh = sv.currentLyrics;
            if (sh == null) return;
            float maxSc = Math.max(0f, sh.lines.size() * rowPx - listH + 24f * scY);
            float target = Math.max(0f, Math.min(maxSc, activeIdx * rowPx - listH * 0.35f));
            scrollY = target;
        }

        @Override
        public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollY = scroller.getCurrY();
                postInvalidateOnAnimation();
            }
        }

        private float maxScrollPx() {
            int vh = getHeight();
            if (vh <= 0) return 0f;
            float scY = vh / dH;
            float bot = (dScreen.bottom - 12f) * scY;
            if (isLeft) {
                float y = dScreen.top + 16f + 54f + ((leftTab == 0 || leftTab == 1) ? 58f : 0f) + 44f;
                float listH = Math.max(40f, bot - y * scY);
                return Math.max(0f, leftVisibleTracks().size() * (DRAWER_ROW * scY) - listH);
            } else if (rightTab == 1) {
                float y = dScreen.top + 16f + 56f + 46f;
                float listH = Math.max(40f, bot - y * scY);
                return Math.max(0f, sv.queue.size() * (DRAWER_ROW * scY) - listH);
            } else if (rightTab == 2) {
                Lyrics.Sheet sh = sv.currentLyrics;
                if (sh == null) return 0f;
                float y = dScreen.top + 16f + 56f;
                float listH = Math.max(40f, bot - y * scY);
                return Math.max(0f, sh.lines.size() * (LYRIC_ROW * scY) - listH + 24f * scY);
            }
            return 0f;
        }

        @Override
        protected void onDraw(Canvas c) {
            int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;

            float p = wingProgress;
            if (p <= 0.004f) return;

            int saveLayerCount = -1;
            if (p < 0.996f) {
                int alpha = Math.max(0, Math.min(255, (int) (p * 255f)));
                saveLayerCount = c.saveLayerAlpha(0f, 0f, vw, vh, alpha);
                float sx = 0.62f + 0.38f * p;
                float sy = 0.88f + 0.12f * p;
                float pivotX = isLeft ? vw : 0f;
                float pivotY = vh * 0.5f;
                float ty = -vh * 0.04f * (1f - p);
                c.translate(0f, ty);
                c.scale(sx, sy, pivotX, pivotY);
            }

            Skin.resetPaints();
            wQ.setStyle(Paint.Style.FILL);
            wS.setStyle(Paint.Style.STROKE);

            // 1. Draw crisp HD mipmapped chrome wing frame at exact view pixels
            wFull.set(0f, 0f, vw, vh);
            Bitmap wingBmp = isLeft ? drawerL : drawerR;
            if (wingBmp != null && !wingBmp.isRecycled()) {
                c.drawBitmap(wingBmp, null, wFull, wB);
            } else {
                wQ.setColor(0xff10223b);
                c.drawRoundRect(wFull, 24f, 24f, wQ);
            }

            // 2. Map screen cavity to EXACT 1:1 native display pixels (zero Canvas.scale blur, fast hardware clipRect!)
            float scX = vw / dW;
            float scY = vh / dH;
            float sc = Math.min(scX, scY);
            float sl = dScreen.left * scX, st = dScreen.top * scY, sr = dScreen.right * scX, sb = dScreen.bottom * scY;

            c.save();
            c.clipRect(sl + 2f, st + 2f, sr - 2f, sb - 2f);
            wQ.setColor(0xed071121);
            c.drawRoundRect(sl, st, sr, sb, 28f * sc, 28f * sc, wQ);

            Hud x = sv.hud();
            if (isLeft) drawLeftContent(c, sl, st, sr, sb, scX, scY, sc);
            else drawRightContent(c, sl, st, sr, sb, scX, scY, sc, x);
            c.restore();

            if (saveLayerCount >= 0) {
                c.restoreToCount(saveLayerCount);
            }
        }

        private float stroke(float designPx, float sc) {
            return Math.max(1.25f, designPx * sc);
        }

        private void drawLeftContent(Canvas c, float sl, float st, float sr, float sb, float scX, float scY, float sc) {
            float pad = 16f * scX;
            float lx = sl + pad, rx = sr - pad, w = rx - lx;
            float y = st + 16f * scY;

            // 1. Source Tabs [YT | LIB | RAD]
            String[] tabs = {"YT", "LIB", "RAD"};
            float gap = 6f * scX;
            float tw = (w - gap * 2f) / 3f;
            float tabH = 44f * scY;
            for (int i = 0; i < 3; i++) {
                float x0 = lx + i * (tw + gap);
                boolean on = (i == leftTab);
                wTmp.set(x0, y, x0 + tw, y + tabH);
                int accent = (i == 0) ? Theme.YT_BADGE : Theme.primary();
                wQ.setColor(on ? accent : 0xff102036);
                c.drawRoundRect(wTmp, 12f * sc, 12f * sc, wQ);
                wS.setStrokeWidth(stroke(1.6f, sc));
                wS.setColor(on ? 0xffffffff : Theme.alpha(Theme.CHROME_MID, 120));
                c.drawRoundRect(wTmp, 12f * sc, 12f * sc, wS);
                Skin.text(c, tabs[i], wTmp.centerX(), wTmp.centerY() + 7f * sc, 21f * sc,
                        on ? 0xff03101d : Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_BOLD, false);
            }
            y += 54f * scY;

            // 2. Search Bar (YT or LIB)
            if (leftTab == 0 || leftTab == 1) {
                float sbH = 48f * scY;
                wTmp.set(lx, y, rx, y + sbH);
                wQ.setColor(0xff0b182c);
                c.drawRoundRect(wTmp, 14f * sc, 14f * sc, wQ);
                wS.setStrokeWidth(stroke(1.8f, sc));
                wS.setColor(leftTab == 0 ? Theme.YT_BADGE : Theme.primary());
                c.drawRoundRect(wTmp, 14f * sc, 14f * sc, wS);
                String curQ = leftTab == 0 ? leftYtQuery : leftLibQuery;
                String ph = curQ.isEmpty() ? (leftTab == 0 ? "🔍 Search YT Music..." : "🔍 Filter library...") : ("🔍 " + curQ + "  ×");
                Skin.text(c, Skin.fit(ph, 20f * sc, w - 24f * sc, Theme.TF_BOLD),
                        lx + 14f * sc, wTmp.centerY() + 7f * sc, 20f * sc,
                        curQ.isEmpty() ? Theme.TEXT_SUB : Theme.glow(), Paint.Align.LEFT, Theme.TF_BOLD, false);
                y += 58f * scY;
            }

            // 3. Quick Context Pills
            float pillH = 36f * scY;
            float pGap = 4f * scX;
            if (leftTab == 0) {
                String[] moods = {"TREND", "CHILL", "BASS", "ARABIC"};
                int[] moodMap = {0, 1, 2, 7};
                float mw = (w - pGap * 3f) / 4f;
                for (int i = 0; i < 4; i++) {
                    float mx = lx + i * (mw + pGap);
                    boolean hot = leftYtQuery.isEmpty() && leftYtMood == moodMap[i];
                    wTmp.set(mx, y, mx + mw, y + pillH);
                    wQ.setColor(hot ? Theme.alpha(Theme.primary(), 82) : 0xff0c182a);
                    c.drawRoundRect(wTmp, 10f * sc, 10f * sc, wQ);
                    if (hot) {
                        wS.setStrokeWidth(stroke(1.5f, sc)); wS.setColor(Theme.primary());
                        c.drawRoundRect(wTmp, 10f * sc, 10f * sc, wS);
                    }
                    Skin.text(c, moods[i], wTmp.centerX(), wTmp.centerY() + 5.5f * sc, 15f * sc,
                            hot ? 0xffffffff : Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
                }
            } else if (leftTab == 1) {
                String[] subs = {"SONGS", "OFFLINE", "FAVS"};
                float mw = (w - pGap * 2f) / 3f;
                for (int i = 0; i < 3; i++) {
                    float mx = lx + i * (mw + pGap);
                    boolean hot = leftLibSub == i;
                    wTmp.set(mx, y, mx + mw, y + pillH);
                    wQ.setColor(hot ? Theme.alpha(Theme.primary(), 82) : 0xff0c182a);
                    c.drawRoundRect(wTmp, 10f * sc, 10f * sc, wQ);
                    if (hot) {
                        wS.setStrokeWidth(stroke(1.5f, sc)); wS.setColor(Theme.primary());
                        c.drawRoundRect(wTmp, 10f * sc, 10f * sc, wS);
                    }
                    Skin.text(c, subs[i], wTmp.centerX(), wTmp.centerY() + 5.5f * sc, 15.5f * sc,
                            hot ? 0xffffffff : Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
                }
            } else {
                String[] rgs = {"TOP", "CHILL", "ELECTRO", "ARABIC"};
                int[] rgMap = {0, 1, 2, 7};
                float mw = (w - pGap * 3f) / 4f;
                for (int i = 0; i < 4; i++) {
                    float mx = lx + i * (mw + pGap);
                    boolean hot = leftRadGenre == rgMap[i];
                    wTmp.set(mx, y, mx + mw, y + pillH);
                    wQ.setColor(hot ? Theme.alpha(Theme.secondary(), 86) : 0xff0c182a);
                    c.drawRoundRect(wTmp, 10f * sc, 10f * sc, wQ);
                    Skin.text(c, rgs[i], wTmp.centerX(), wTmp.centerY() + 5.5f * sc, 14.5f * sc,
                            hot ? 0xffffffff : Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
                }
            }
            y += 44f * scY;

            // 4. Scrollable Track List (1:1 crisp native pixels!)
            List<Track> list = leftVisibleTracks();
            float listTop = y;
            float listBot = sb - 12f * scY;
            float listH = Math.max(40f, listBot - listTop);
            float rowH = DRAWER_ROW * scY;
            float maxSc = Math.max(0f, list.size() * rowH - listH);
            scrollY = Math.max(0f, Math.min(maxSc, scrollY));

            if (list.isEmpty()) {
                String empty = (leftYtLoading || leftRadLoading) ? leftMsg
                        : (leftTab == 0 ? (leftMsg.isEmpty() ? "Tap Search to find songs" : leftMsg) : "No tracks here");
                Skin.text(c, Skin.fit(empty, 19f * sc, w, Theme.TF_MEDIUM),
                        (lx + rx) * 0.5f, listTop + listH * 0.45f, 19f * sc, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MEDIUM, false);
                return;
            }

            Track cur = sv.current();
            c.save();
            c.clipRect(lx - 2f, listTop, rx + 2f, listBot);
            int first = Math.max(0, (int) (scrollY / rowH));
            for (int i = first; i < list.size(); i++) {
                float ry = listTop + i * rowH - scrollY;
                if (ry > listBot) break;
                Track t = list.get(i);
                boolean on = cur != null && cur.key().equals(t.key());

                wTmp.set(lx, ry + 3f * scY, rx, ry + rowH - 4f * scY);
                wQ.setColor(on ? Theme.alpha(Theme.primary(), 52) : 0x990b182c);
                c.drawRoundRect(wTmp, 12f * sc, 12f * sc, wQ);
                if (on) {
                    wS.setStrokeWidth(stroke(1.6f, sc)); wS.setColor(Theme.primary());
                    c.drawRoundRect(wTmp, 12f * sc, 12f * sc, wS);
                }

                boolean showDl = t.isYouTube();
                float rightReserve = showDl ? (72f * scX) : (14f * scX);
                float avail = Math.max(40f, w - 16f * scX - rightReserve);

                Skin.text(c, Skin.fit(t.title, 20.5f * sc, avail, Theme.TF_BOLD),
                        lx + 12f * scX, ry + 33f * scY, 20.5f * sc, on ? Theme.glow() : Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, false);
                Skin.text(c, Skin.fit(t.subLabel(), 15.5f * sc, avail, Theme.TF_MEDIUM),
                        lx + 12f * scX, ry + 57f * scY, 15.5f * sc, Theme.TEXT_SUB, Paint.Align.LEFT, Theme.TF_MEDIUM, false);

                if (showDl) {
                    boolean off = sv.isOffline(t);
                    int pct = Downloader.progress(t.ytId);
                    boolean dl = pct >= 0;
                    wTmp.set(rx - 62f * scX, ry + 14f * scY, rx - 8f * scX, ry + rowH - 15f * scY);
                    wQ.setColor(off ? Theme.alpha(Theme.OFF_BADGE, 58) : (dl ? Theme.alpha(Theme.primary(), 64) : 0xff132744));
                    c.drawRoundRect(wTmp, 11f * sc, 11f * sc, wQ);
                    wS.setStrokeWidth(stroke(1.5f, sc));
                    wS.setColor(off ? Theme.OFF_BADGE : Theme.primary());
                    c.drawRoundRect(wTmp, 11f * sc, 11f * sc, wS);
                    Skin.text(c, off ? "✓" : (dl ? pct + "%" : "↓"), wTmp.centerX(), wTmp.centerY() + 7f * sc,
                            (dl ? 14.5f : 21f) * sc, off ? Theme.OFF_BADGE : Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, false);
                }
            }
            c.restore();
        }

        private void drawRightContent(Canvas c, float sl, float st, float sr, float sb, float scX, float scY, float sc, Hud x) {
            float pad = 16f * scX;
            float lx = sl + pad, rx = sr - pad, w = rx - lx;
            float y = st + 16f * scY;

            // 1. Sub-tabs [TOOLS | QUEUE | LYRICS]
            String[] tabs = {"TOOLS", "QUEUE", "LYRICS"};
            float gap = 6f * scX;
            float tw = (w - gap * 2f) / 3f;
            float tabH = 44f * scY;
            for (int i = 0; i < 3; i++) {
                float x0 = lx + i * (tw + gap);
                boolean on = (i == rightTab);
                wTmp.set(x0, y, x0 + tw, y + tabH);
                wQ.setColor(on ? Theme.primary() : 0xff102036);
                c.drawRoundRect(wTmp, 12f * sc, 12f * sc, wQ);
                wS.setStrokeWidth(stroke(1.6f, sc));
                wS.setColor(on ? 0xffffffff : Theme.alpha(Theme.CHROME_MID, 120));
                c.drawRoundRect(wTmp, 12f * sc, 12f * sc, wS);
                Skin.text(c, tabs[i], wTmp.centerX(), wTmp.centerY() + 7f * sc, 19.5f * sc,
                        on ? 0xff03101d : Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_BOLD, false);
            }
            y += 56f * scY;

            float bot = sb - 12f * scY;
            if (rightTab == 0) drawRightTools(c, lx, y, rx, bot, scX, scY, sc, x);
            else if (rightTab == 1) drawRightQueue(c, lx, y, rx, bot, scX, scY, sc);
            else drawRightLyrics(c, lx, y, rx, bot, scX, scY, sc);
        }

        private void drawRightTools(Canvas c, float lx, float top, float rx, float bot, float scX, float scY, float sc, Hud x) {
            float w = rx - lx;
            Track cur = sv.current();
            float y = top;

            // Current track card
            wTmp.set(lx, y, rx, y + 66f * scY);
            wQ.setColor(0xff0d1d34);
            c.drawRoundRect(wTmp, 14f * sc, 14f * sc, wQ);
            wS.setStrokeWidth(stroke(1.5f, sc)); wS.setColor(Theme.alpha(Theme.primary(), 130));
            c.drawRoundRect(wTmp, 14f * sc, 14f * sc, wS);
            Skin.text(c, Skin.fit(x.title, 19.5f * sc, w - 22f * sc, Theme.TF_BOLD),
                    lx + 11f * scX, y + 28f * scY, 19.5f * sc, Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, false);
            Skin.text(c, Skin.fit(x.badge.isEmpty() ? "EARWAX 4.6" : x.badge, 15f * sc, w - 22f * sc, Theme.TF_MONO),
                    lx + 11f * scX, y + 52f * scY, 15f * sc, Theme.primary(), Paint.Align.LEFT, Theme.TF_MONO, false);
            y += 74f * scY;

            boolean isYt = cur != null && (cur.isYouTube() || cur.isOfflineYtm());
            float gap = 8f * scY;
            float colGap = 10f * scX;
            float halfW = (w - colGap) * 0.5f;
            int rows = isYt ? 5 : 4;
            float btnH = Math.min(54f * scY, Math.max(36f * scY, (bot - y - gap * (rows - 1)) / rows));

            if (isYt) {
                boolean off = sv.isOffline(cur);
                int pct = Downloader.progress(cur.ytId);
                String dlLbl = off ? ("✓ SAVED • RE-DL (" + sv.audioQualityShort() + ")")
                        : (pct >= 0 ? ("DOWNLOADING " + pct + "%") : ("↓ DOWNLOAD (" + sv.audioQualityShort() + ")"));
                drawToolTile(c, lx, y, rx, y + btnH, dlLbl, off || pct >= 0, Theme.OFF_BADGE, sc);
                y += btnH + gap;

                drawToolTile(c, lx, y, lx + halfW, y + btnH, "◎ YT RADIO", false, Theme.YT_BADGE, sc);
                drawToolTile(c, lx + halfW + colGap, y, rx, y + btnH, x.fav ? "♥ FAVOURITED" : "♡ FAVOURITE", x.fav, Theme.HEART_ON, sc);
                y += btnH + gap;
            } else {
                drawToolTile(c, lx, y, lx + halfW, y + btnH, x.fav ? "♥ FAVOURITED" : "♡ FAVOURITE", x.fav, Theme.HEART_ON, sc);
                drawToolTile(c, lx + halfW + colGap, y, rx, y + btnH, "LRC LYRICS", false, Theme.primary(), sc);
                y += btnH + gap;
            }

            drawToolTile(c, lx, y, lx + halfW, y + btnH, x.shuffle ? "⇄ SHUF: ON" : "⇄ SHUFFLE", x.shuffle, Theme.primary(), sc);
            String repLbl = x.repeat == 1 ? "↻ REP: ONE" : (x.repeat == 2 ? "↻ REP: ALL" : "↻ REPEAT");
            drawToolTile(c, lx + halfW + colGap, y, rx, y + btnH, repLbl, x.repeat != 0, Theme.primary(), sc);
            y += btnH + gap;

            String eqName = (sv.presetIdx >= 0 && sv.presetIdx < PlayerService.PRESET_NAMES.length)
                    ? PlayerService.PRESET_NAMES[sv.presetIdx] : "CUSTOM";
            drawToolTile(c, lx, y, lx + halfW, y + btnH, "EQ: " + eqName, sv.dsp.eqEnabled, Theme.secondary(), sc);
            drawToolTile(c, lx + halfW + colGap, y, rx, y + btnH, "♫ QTY: " + sv.audioQualityShort(), true, Theme.primary(), sc);
            y += btnH + gap;

            drawToolTile(c, lx, y, lx + halfW, y + btnH, "◈ " + Theme.get().name, false, Theme.primary(), sc);
            drawToolTile(c, lx + halfW + colGap, y, rx, y + btnH, "⬈ FULL APP", false, Theme.glow(), sc);
        }

        private void drawToolTile(Canvas c, float l, float t, float r, float b, String lbl, boolean hot, int col, float sc) {
            wTmp.set(l, t, r, b);
            wQ.setColor(hot ? Theme.alpha(col, 72) : 0xff102037);
            c.drawRoundRect(wTmp, 13f * sc, 13f * sc, wQ);
            wS.setStrokeWidth(stroke(hot ? 2.0f : 1.4f, sc));
            wS.setColor(hot ? col : Theme.alpha(Theme.CHROME_MID, 125));
            c.drawRoundRect(wTmp, 13f * sc, 13f * sc, wS);
            Skin.text(c, Skin.fit(lbl, 17f * sc, (r - l) - 14f * sc, Theme.TF_BOLD),
                    wTmp.centerX(), wTmp.centerY() + 6f * sc, 17f * sc,
                    hot ? 0xffffffff : Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_BOLD, false);
        }

        private void drawRightQueue(Canvas c, float lx, float top, float rx, float bot, float scX, float scY, float sc) {
            float w = rx - lx;
            Skin.text(c, "UP NEXT (" + sv.queue.size() + ")", lx + 4f * scX, top + 28f * scY, 19f * sc, Theme.glow(), Paint.Align.LEFT, Theme.TF_BOLD, false);
            wTmp.set(rx - 102f * scX, top + 2f * scY, rx, top + 38f * scY);
            wQ.setColor(0xff132744);
            c.drawRoundRect(wTmp, 10f * sc, 10f * sc, wQ);
            wS.setStrokeWidth(stroke(1.4f, sc)); wS.setColor(Theme.primary());
            c.drawRoundRect(wTmp, 10f * sc, 10f * sc, wS);
            Skin.text(c, "⇄ SHUF", wTmp.centerX(), wTmp.centerY() + 6f * sc, 16f * sc, Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_BOLD, false);

            float listTop = top + 46f * scY;
            float listH = Math.max(40f, bot - listTop);
            float rowH = DRAWER_ROW * scY;
            float maxSc = Math.max(0f, sv.queue.size() * rowH - listH);
            scrollY = Math.max(0f, Math.min(maxSc, scrollY));

            if (sv.queue.isEmpty()) {
                Skin.text(c, "Queue is empty", (lx + rx) * 0.5f, listTop + listH * 0.45f, 19f * sc, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MEDIUM, false);
                return;
            }

            int curQ = sv.currentQueueIndex();
            c.save();
            c.clipRect(lx - 2f, listTop, rx + 2f, bot);
            int first = Math.max(0, (int) (scrollY / rowH));
            for (int i = first; i < sv.queue.size(); i++) {
                float ry = listTop + i * rowH - scrollY;
                if (ry > bot) break;
                Track t = sv.queue.get(i);
                boolean on = (i == curQ);
                wTmp.set(lx, ry + 3f * scY, rx, ry + rowH - 4f * scY);
                wQ.setColor(on ? Theme.alpha(Theme.primary(), 52) : 0x990b182c);
                c.drawRoundRect(wTmp, 12f * sc, 12f * sc, wQ);
                if (on) {
                    wS.setStrokeWidth(stroke(1.6f, sc)); wS.setColor(Theme.primary());
                    c.drawRoundRect(wTmp, 12f * sc, 12f * sc, wS);
                }
                float avail = Math.max(40f, w - 68f * scX);
                Skin.text(c, Skin.fit(t.title, 20f * sc, avail, Theme.TF_BOLD),
                        lx + 12f * scX, ry + 32f * scY, 20f * sc, on ? Theme.glow() : Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, false);
                Skin.text(c, Skin.fit(t.subLabel(), 15f * sc, avail, Theme.TF_MEDIUM),
                        lx + 12f * scX, ry + 56f * scY, 15f * sc, Theme.TEXT_SUB, Paint.Align.LEFT, Theme.TF_MEDIUM, false);
                Skin.text(c, "×", rx - 24f * scX, ry + 45f * scY, 26f * sc, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
            }
            c.restore();
        }

        private void drawRightLyrics(Canvas c, float lx, float top, float rx, float bot, float scX, float scY, float sc) {
            float w = rx - lx;
            float listH = Math.max(40f, bot - top);
            Lyrics.Sheet sh = sv.currentLyrics;
            if (sh == null || sh.lines.isEmpty()) {
                String msg = sv.lyricsLoading ? "Loading synced lyrics..."
                        : (sv.lyricsMsg.isEmpty() ? "Tap to fetch lyrics" : sv.lyricsMsg);
                Skin.text(c, Skin.fit(msg, 18.5f * sc, w, Theme.TF_MEDIUM),
                        (lx + rx) * 0.5f, top + listH * 0.45f, 18.5f * sc, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MEDIUM, false);
                return;
            }
            long pos = sv.pl != null ? sv.pl.getCurrentPosition() : 0L;
            int active = sh.synced ? sh.activeIndex(pos) : -1;
            float rowH = LYRIC_ROW * scY;
            float maxSc = Math.max(0f, sh.lines.size() * rowH - listH + 24f * scY);
            scrollY = Math.max(0f, Math.min(maxSc, scrollY));

            c.save();
            c.clipRect(lx - 2f, top, rx + 2f, bot);
            int first = Math.max(0, (int) (scrollY / rowH));
            for (int i = first; i < sh.lines.size(); i++) {
                float ry = top + i * rowH - scrollY;
                if (ry > bot) break;
                Lyrics.Line line = sh.lines.get(i);
                boolean hot = (i == active);
                if (hot) {
                    wTmp.set(lx, ry + 2f * scY, rx, ry + rowH - 4f * scY);
                    wQ.setColor(Theme.alpha(Theme.primary(), 48));
                    c.drawRoundRect(wTmp, 10f * sc, 10f * sc, wQ);
                }
                float fsz = (hot ? 20f : 17.5f) * sc;
                Skin.text(c, Skin.fit(line.text, fsz, w - 12f * scX, hot ? Theme.TF_BOLD : Theme.TF_MEDIUM),
                        (lx + rx) * 0.5f, ry + rowH * 0.62f, fsz,
                        hot ? 0xffffffff : Theme.TEXT_SUB, Paint.Align.CENTER, hot ? Theme.TF_BOLD : Theme.TF_MEDIUM, false);
            }
            c.restore();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!drawersOpen || wingProgress < 0.5f) return false;
            int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return false;
            float vx = e.getX(), vy = e.getY();

            if (vt == null) vt = VelocityTracker.obtain();
            vt.addMovement(e);

            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scroller.forceFinished(true);
                    downX = vx; downY = vy; lastY = vy;
                    moved = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dy = lastY - vy;
                    lastY = vy;
                    if (!moved && Math.hypot(vx - downX, vy - downY) > slop) moved = true;
                    if (moved) {
                        float maxSc = maxScrollPx();
                        if (maxSc > 0f) {
                            scrollY = Math.max(0f, Math.min(maxSc, scrollY + dy));
                            invalidate();
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (moved) {
                        float maxSc = maxScrollPx();
                        if (maxSc > 0f && vt != null) {
                            vt.computeCurrentVelocity(1000);
                            int velY = (int) (-vt.getYVelocity());
                            if (Math.abs(velY) > 120) {
                                scroller.fling(0, (int) scrollY, 0, velY, 0, 0, 0, (int) maxSc);
                                postInvalidateOnAnimation();
                            }
                        }
                    } else {
                        handleTap(vx, vy, vw, vh);
                    }
                    if (vt != null) { vt.recycle(); vt = null; }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    if (vt != null) { vt.recycle(); vt = null; }
                    return true;
            }
            return true;
        }

        private void handleTap(float vx, float vy, int vw, int vh) {
            float scX = vw / dW, scY = vh / dH;
            float dx = vx / scX, dy = vy / scY;
            if (!dScreen.contains(dx, dy)) return;
            if (isLeft) tapLeftWing(dx, dy, scrollY / scY);
            else tapRightWing(dx, dy, scrollY / scY, scY);
        }

        private void tapLeftWing(float dx, float dy, float scrollDesign) {
            float pad = 16f;
            float lx = dScreen.left + pad, rx = dScreen.right - pad, w = rx - lx;
            float y = dScreen.top + 16f;

            if (dy >= y && dy <= y + 48f) {
                int i = (int) ((dx - lx) / (w / 3f));
                if (i >= 0 && i < 3) {
                    leftTab = i;
                    leftListDirty = true;
                    resetScroll();
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (i == 0 && leftYtTracks.isEmpty() && !leftYtLoading) loadLeftYtMood(leftYtMood);
                    if (i == 2 && leftRadTracks.isEmpty() && !leftRadLoading) loadLeftRadio(leftRadGenre);
                    invalidate();
                }
                return;
            }
            y += 54f;

            if (leftTab == 0 || leftTab == 1) {
                if (dy >= y && dy <= y + 52f) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    String curQ = leftTab == 0 ? leftYtQuery : leftLibQuery;
                    if (!curQ.isEmpty() && dx > rx - 56f) {
                        if (leftTab == 0) loadLeftYtMood(leftYtMood);
                        else { leftLibQuery = ""; leftListDirty = true; resetScroll(); invalidate(); }
                    } else {
                        openOverlaySearch(leftTab == 0);
                    }
                    return;
                }
                y += 58f;
            }

            if (dy >= y && dy <= y + 40f) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (leftTab == 0) {
                    int[] moodMap = {0, 1, 2, 7};
                    int idx = Math.max(0, Math.min(3, (int) ((dx - lx) / (w / 4f))));
                    loadLeftYtMood(moodMap[idx]);
                } else if (leftTab == 1) {
                    leftLibSub = Math.max(0, Math.min(2, (int) ((dx - lx) / (w / 3f))));
                    leftListDirty = true;
                    resetScroll();
                    invalidate();
                } else {
                    int[] rgMap = {0, 1, 2, 7};
                    int idx = Math.max(0, Math.min(3, (int) ((dx - lx) / (w / 4f))));
                    loadLeftRadio(rgMap[idx]);
                }
                return;
            }
            y += 44f;

            List<Track> list = leftVisibleTracks();
            if (dy < y || list.isEmpty()) return;
            int idx = (int) ((dy - y + scrollDesign) / DRAWER_ROW);
            if (idx < 0 || idx >= list.size()) return;
            Track t = list.get(idx);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

            if (t.isYouTube() && dx >= rx - 68f) {
                Toast.makeText(sv, "Downloading (" + sv.audioQualityShort() + "): " + t.title, Toast.LENGTH_SHORT).show();
                sv.downloadTrack(t, (off, err) -> {
                    Toast.makeText(sv, err != null ? err : ("Saved (" + sv.audioQualityShort() + "): " + t.title), Toast.LENGTH_SHORT).show();
                    leftListDirty = true;
                    invalidate();
                });
                return;
            }
            String key = leftTab == 0 ? ("pop_yt|" + leftYtQuery + "|" + leftYtMood)
                    : (leftTab == 1 ? ("pop_lib|" + leftLibSub + "|" + leftLibQuery) : ("pop_rad|" + leftRadGenre));
            sv.playList(list, idx, key);
            invalidate();
        }

        private void tapRightWing(float dx, float dy, float scrollDesign, float scY) {
            float pad = 16f;
            float lx = dScreen.left + pad, rx = dScreen.right - pad, w = rx - lx;
            float y = dScreen.top + 16f;

            if (dy >= y && dy <= y + 48f) {
                int i = (int) ((dx - lx) / (w / 3f));
                if (i >= 0 && i < 3) {
                    rightTab = i;
                    resetScroll();
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (i == 2) sv.loadLyricsForCurrent();
                    invalidate();
                }
                return;
            }
            y += 56f;

            if (rightTab == 0) {
                tapRightTools(dx, dy, lx, y, rx, dScreen.bottom - 12f);
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
                int idx = (int) ((dy - (y + 46f) + scrollDesign) / DRAWER_ROW);
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
                    int idx = (int) ((dy - y + scrollDesign) / LYRIC_ROW);
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

        private void tapRightTools(float dx, float dy, float lx, float top, float rx, float bot) {
            float w = rx - lx;
            float gap = 8f, colGap = 10f, halfW = (w - colGap) * 0.5f;
            float y = top + 74f;
            boolean rightCol = dx > lx + halfW + colGap * 0.5f;
            Track cur = sv.current();
            boolean isYt = cur != null && (cur.isYouTube() || cur.isOfflineYtm());
            int rows = isYt ? 5 : 4;
            float btnH = Math.min(54f, Math.max(36f, (bot - y - gap * (rows - 1)) / rows));

            if (dy < y) return;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);

            if (isYt) {
                if (dy < y + btnH) {
                    Toast.makeText(sv, "Downloading (" + sv.audioQualityShort() + "): " + cur.title, Toast.LENGTH_SHORT).show();
                    sv.downloadTrack(cur, (off, err) -> {
                        Toast.makeText(sv, err != null ? err : ("Saved (" + sv.audioQualityShort() + "): " + cur.title), Toast.LENGTH_SHORT).show();
                        leftListDirty = true;
                        invalidate();
                        if (leftWingView != null) leftWingView.invalidate();
                    });
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
                if (!rightCol) {
                    sv.cyclePreset();
                } else {
                    sv.cycleAudioQuality();
                    Toast.makeText(sv, "Audio Quality: " + YtMusic.QUALITY_LABELS[sv.audioQuality], Toast.LENGTH_SHORT).show();
                }
                invalidate();
                return;
            }
            y += btnH + gap;

            if (dy < y + btnH) {
                if (!rightCol) {
                    sv.selectTheme((Theme.index() + 1) % Theme.PALETTES.length);
                    if (leftWingView != null) leftWingView.invalidate();
                    PopupView.this.invalidate();
                } else {
                    sv.openMainApp(-1);
                }
                invalidate();
            }
        }
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
                leftListDirty = true;
                if (leftWingView != null) { leftWingView.resetScroll(); leftWingView.invalidate(); }
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
        Skin.Btn dr = sk.find("drawers");
        if (dr != null && !"orb".equals(skinName)) {
            if (Math.abs(x - dr.cx) <= 84f && Math.abs(y - dr.cy) <= 36f) return dr;
        }
        return sk.hit(x, y);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        if (pinch.isInProgress() || e.getPointerCount() > 1) {
            scaling = true; down = null; rotating = false; seek = false; rot.up();
            return true;
        }

        int vw = getWidth();
        float sc = vw > 0 ? (vw / (float) sk.w) : 1f;
        float x = e.getX() / sc;
        float y = e.getY() / sc;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scaling = false; drag = false; seek = false; rotating = false; rotMoved = false;
                rx = e.getRawX(); ry = e.getRawY();
                downDesignX = x; downDesignY = y;

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
                        try { wm.updateViewLayout(this, lp); } catch (Exception ignored) { }
                        syncWingPositions();
                        rx = e.getRawX();
                        ry = e.getRawY();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
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
                down = null; rotating = false; seek = false; drag = false; rot.up();
                invalidate();
                return true;
        }
        return true;
    }
}
