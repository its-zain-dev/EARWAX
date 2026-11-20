package app.earwax;

/**
 * Precision rotary gesture controller: converts finger circling around a knob or orbital ring
 * into signed degrees (clockwise = positive), with radius-adaptive precision and wrap handling.
 */
final class Rotary {
    private float lastAngle;
    private boolean active;

    boolean active() { return active; }

    void down(float cx, float cy, float x, float y) {
        lastAngle = angle(cx, cy, x, y);
        active = true;
    }

    void up() { active = false; }

    /**
     * Returns delta degrees since previous touch event.
     * Ignores touches too close to the center (noisy atan2) and attenuates huge single-frame jumps.
     */
    float move(float cx, float cy, float x, float y, float minRadius) {
        if (!active) return 0f;
        float dist = (float) Math.hypot(x - cx, y - cy);
        if (dist < minRadius) return 0f;
        float a = angle(cx, cy, x, y);
        float d = a - lastAngle;
        if (d > 180f) d -= 360f;
        else if (d < -180f) d += 360f;
        lastAngle = a;
        // Clamp extreme jitter spikes
        if (Math.abs(d) > 65f) return 0f;
        return d;
    }

    /** 0 deg = straight up (12 o'clock), clockwise positive, range -180..180. */
    static float angle(float cx, float cy, float x, float y) {
        return (float) Math.toDegrees(Math.atan2(x - cx, -(y - cy)));
    }
}
