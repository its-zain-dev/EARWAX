package app.earwax;

/** Snapshot of what the screens / popups need to draw. One instance is reused every frame (main thread only). */
final class Hud {
    String title = "", artist = "", tool = "VOL", toolText = "";
    long pos, dur;
    boolean playing, live, buffering, fav;
    final float[] bars = new float[32];
    float bass;        // 0..1 low-frequency energy: drives the ear vibration
    float toolNorm;    // 0..1 position of the selected tool (drives the dial / knob)
}
