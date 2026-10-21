package app.earwax;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * Floating, shaped popup (whale or orb) in a system overlay window.
 *  - turn the knob / dial like a real one: circle your finger around it (clockwise = more)
 *  - drag the body to move, pinch to resize (remembered), drag the title strip to seek
 */
final class PopupView extends View {
    private final Skin sk;
    private final PlayerService sv;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final DisplayMetrics dm;
    private final ScaleGestureDetector pinch;
    private final Rotary rot = new Rotary();
    private final float baseDp, aspect, slop;
    private float scale;
    private Skin.Btn down;
    private boolean drag, seek, rotating, scaling;
    private float rx, ry, lastTick;
    private final Runnable tick = new Runnable() {
        public void run() { invalidate(); postDelayed(this, sv.pl.isPlaying() || rotating || down != null ? 50 : 400); }
    };

    PopupView(PlayerService s, String name) {
        super(s); sv = s;
        dm = s.getResources().getDisplayMetrics();
        baseDp = name.equals("orb") ? 170 : 290;                          // comfortable defaults; pinch changes them
        scale = Math.max(0.6f, Math.min(1.7f, Prefs.popupScale(s)));
        sk = Skin.load(s, name, (int) (baseDp * 1.7f * dm.density));      // decode once at the largest size we allow
        aspect = sk.h / (float) sk.w; slop = ViewConfiguration.get(s).getScaledTouchSlop();
        int wp = (int) (baseDp * scale * dm.density);
        lp = new WindowManager.LayoutParams(wp, (int) (wp * aspect), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.popupX(s, (int) (24 * dm.density)); lp.y = Prefs.popupY(s, (int) (160 * dm.density));
        pinch = new ScaleGestureDetector(s, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { resize(scale * d.getScaleFactor()); return true; }
        });
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        wm.addView(this, lp); s.viewer(true); tick.run();
    }

    void dismiss() {
        removeCallbacks(tick); Prefs.popupPos(sv, lp.x, lp.y); Prefs.popupScale(sv, scale);
        try { wm.removeView(this); } catch (Exception ignored) { }
        sv.viewer(false); sk.release();
    }

    private void resize(float k) {
        k = Math.max(0.6f, Math.min(1.7f, k)); if (k == scale) return;
        float cx = lp.x + lp.width / 2f, cy = lp.y + lp.height / 2f; scale = k;
        lp.width = (int) (baseDp * scale * dm.density); lp.height = (int) (lp.width * aspect);
        lp.x = (int) (cx - lp.width / 2f); lp.y = (int) (cy - lp.height / 2f);
        wm.updateViewLayout(this, lp);
    }

    private void keepOnScreen() {
        lp.x = Math.max(-lp.width / 4, Math.min(dm.widthPixels - lp.width * 3 / 4, lp.x));
        lp.y = Math.max(0, Math.min(dm.heightPixels - lp.height / 2, lp.y));
        wm.updateViewLayout(this, lp);
    }

    @Override protected void onDraw(Canvas c) {
        if (sk.bmp == null) return;
        Skin.resetPaints();
        float s = getWidth() / (float) sk.w; c.scale(s, s);
        sk.hud(c, sv.hud(), down, rotating);
    }

    /** Where does a finger-circle make sense? The whale knob, or the orb's outer dial ring. */
    private boolean onRotary(float x, float y) {
        if (sk.knob != null) return Math.hypot(x - sk.knob.cx, y - sk.knob.cy) <= sk.knob.r * 1.5f;
        Skin.Dial d = sk.dial; if (d == null) return false;
        float r = (float) Math.hypot(x - d.cx, y - d.cy), a = Rotary.angle(d.cx, d.cy, x, y);
        return r >= d.r0 - 26 && r <= d.r1 + 22 && Math.abs(a) <= 142;
    }

    private float cx() { return sk.knob != null ? sk.knob.cx : sk.dial.cx; }
    private float cy() { return sk.knob != null ? sk.knob.cy : sk.dial.cy; }

    @Override public boolean onTouchEvent(MotionEvent e) {
        pinch.onTouchEvent(e);
        if (pinch.isInProgress() || e.getPointerCount() > 1) { scaling = true; down = null; rotating = false; rot.up(); return true; }
        float s = getWidth() / (float) sk.w, x = e.getX() / s, y = e.getY() / s;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scaling = false; rx = e.getRawX(); ry = e.getRawY(); drag = false; seek = false; rotating = false;
                down = sk.hit(x, y);
                if (down != null && down == sk.knob) down = null;                  // the knob is not a push button: it turns
                if (down == null && onRotary(x, y)) { rotating = true; rot.down(cx(), cy(), x, y); lastTick = sv.toolNorm(); performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }
                else if (down == null && sk.info != null && sk.info.contains(x, y)) { seek = true; sv.seekFrac((x - sk.info.left) / sk.info.width()); }
                else if (down != null) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (scaling) return true;
                if (rotating) {
                    float deg = rot.move(cx(), cy(), x, y, 18f);
                    if (deg != 0) {
                        sv.adjustTool(deg / 270f);                                    // 270 degrees of finger travel = the whole range
                        float n = sv.toolNorm();
                        if (Math.abs(n - lastTick) >= 0.05f) { lastTick = n; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
                    }
                } else if (seek) sv.seekFrac((x - sk.info.left) / sk.info.width());
                else if (down == null || !(sk.hit(x, y) == down)) {
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (drag || Math.hypot(dx, dy) > slop) { drag = true; down = null; lp.x += (int) dx; lp.y += (int) dy; wm.updateViewLayout(this, lp); rx = e.getRawX(); ry = e.getRawY(); }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!scaling && !drag && down != null && sk.hit(x, y) == down) sv.popupAction(down.id);
                if (drag || scaling) keepOnScreen();
                down = null; rotating = false; seek = false; drag = false; rot.up(); invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                down = null; rotating = false; seek = false; drag = false; rot.up(); invalidate();
                return true;
        }
        return true;
    }
}
