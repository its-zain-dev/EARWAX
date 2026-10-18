package app.earwax;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Floating, shaped popup (whale or orb). Lives in a system overlay window; the shape is the artwork. */
final class PopupView extends View {
    private final Skin sk;
    private final PlayerService sv;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private Skin.Btn down;
    private boolean drag, seek;
    private float rx, ry, ly;
    private final Runnable tick = new Runnable() { public void run() { invalidate(); postDelayed(this, 66); } };

    PopupView(PlayerService s, String name) {
        super(s); sv = s; sk = Skin.load(s, name);
        float d = s.getResources().getDisplayMetrics().density;
        int wp = (int) ((name.equals("orb") ? 260 : 340) * d), hp = (int) (wp * (float) sk.h / sk.w);
        lp = new WindowManager.LayoutParams(wp, hp, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START; lp.x = (int) (24 * d); lp.y = (int) (160 * d);
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        wm.addView(this, lp); tick.run();
    }

    void dismiss() { removeCallbacks(tick); wm.removeView(this); }

    @Override protected void onDraw(Canvas c) {
        float s = getWidth() / (float) sk.w; c.scale(s, s);
        sk.hud(c, sv.hud(), false);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float s = getWidth() / (float) sk.w, x = e.getX() / s, y = e.getY() / s;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                rx = e.getRawX(); ry = e.getRawY(); ly = e.getY(); drag = false;
                down = sk.hit(x, y); seek = down == null && sk.info != null && sk.info.contains(x, y);
                if (seek) sv.seekFrac((x - sk.info.left) / sk.info.width());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (down != null && down == sk.knob) { sv.adjustTool((ly - e.getY()) / (170f * s)); ly = e.getY(); }
                else if (seek) sv.seekFrac((x - sk.info.left) / sk.info.width());
                else if (down == null) {
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (drag || Math.hypot(dx, dy) > 8) { drag = true; lp.x += (int) dx; lp.y += (int) dy; wm.updateViewLayout(this, lp); rx = e.getRawX(); ry = e.getRawY(); }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (down != null && down != sk.knob && sk.hit(x, y) == down) sv.popupAction(down.id);
                return true;
        }
        return true;
    }
}
