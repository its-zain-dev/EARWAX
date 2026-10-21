package app.earwax;

/** Turns a finger circling around a centre into signed degrees, like a real knob (clockwise = positive). */
final class Rotary {
    private float last;
    private boolean on;

    boolean active() { return on; }
    void down(float cx, float cy, float x, float y) { last = angle(cx, cy, x, y); on = true; }
    void up() { on = false; }

    /** Degrees turned since the previous call. Near the centre the angle is noisy, so it returns 0 there. */
    float move(float cx, float cy, float x, float y, float minRadius) {
        if (!on || Math.hypot(x - cx, y - cy) < minRadius) return 0;
        float a = angle(cx, cy, x, y), d = a - last;
        if (d > 180) d -= 360; else if (d < -180) d += 360;
        last = a;
        return d;
    }

    /** 0 = straight up, clockwise positive, range -180..180. */
    static float angle(float cx, float cy, float x, float y) { return (float) Math.toDegrees(Math.atan2(x - cx, -(y - cy))); }
}
