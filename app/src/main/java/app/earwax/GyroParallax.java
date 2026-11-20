package app.earwax;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Low-power 2.5D gyroscope / accelerometer parallax engine.
 * Drives subtle 3D depth parallax between the backdrop, chrome bust, floating ear speaker pods,
 * crown orbs, and moving specular chrome light reflections. Automatically sleeps when view hides.
 */
final class GyroParallax implements SensorEventListener {
    private final SensorManager sm;
    private final Sensor sensor;
    private final float[] rotMat = new float[9], orient = new float[3];
    private boolean running, enabled = true;
    private boolean hasBaseline;
    private float baseX, baseY;
    float x, y;   // smoothed normalized tilt in [-1, 1]

    GyroParallax(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = sm != null ? sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) : null;
        if (s == null && sm != null) s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensor = s;
    }

    void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            stop();
            x = 0f;
            y = 0f;
        } else if (!running) {
            start();
        }
    }

    boolean isEnabled() { return enabled; }

    void start() {
        if (!enabled || running || sm == null || sensor == null) return;
        hasBaseline = false;
        running = true;
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
    }

    void stop() {
        if (!running || sm == null) return;
        running = false;
        sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!enabled) return;
        float tx, ty;
        if (e.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotMat, e.values);
            SensorManager.getOrientation(rotMat, orient);
            tx = orient[2]; // roll
            ty = orient[1]; // pitch
        } else {
            tx = -e.values[0] / 9.81f;
            ty = (e.values[1] - 5.5f) / 9.81f;
        }
        if (!hasBaseline) {
            baseX = tx;
            baseY = ty;
            hasBaseline = true;
        }
        // Gently adapt baseline so holding the phone at any comfortable angle stays centered
        baseX += (tx - baseX) * 0.015f;
        baseY += (ty - baseY) * 0.015f;
        float nx = clamp((tx - baseX) * 2.4f, -1f, 1f);
        float ny = clamp((ty - baseY) * 2.4f, -1f, 1f);
        x += (nx - x) * 0.14f;
        y += (ny - y) * 0.14f;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
