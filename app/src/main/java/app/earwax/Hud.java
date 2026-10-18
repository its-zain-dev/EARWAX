package app.earwax;

/** Immutable-ish snapshot of what the screens/popups/notification need to draw. */
final class Hud {
    String title = "", artist = "", tool = "VOL", toolText = "";
    long pos, dur;
    boolean playing;
    float[] bars = new float[32];
    float toolNorm;   // 0..1 position of the current tool value (drives the knob)
}
