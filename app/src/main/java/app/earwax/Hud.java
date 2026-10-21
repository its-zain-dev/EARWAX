package app.earwax;

/** Snapshot of what the screens / popups need to draw. One instance is reused every frame (main thread only). */
final class Hud {
    String title = "", artist = "", tool = "VOL", toolText = "";
    long pos, dur;
    boolean playing, live, buffering, fav;
    final float[] bars = new float[32];
    float bass;        // 0..1 low-frequency energy: drives the ear vibration
    final float[] env = new float[3];   // 0..1 beat-synchronous pulse of the low / mid / high band: drives the speaker cones
    private final float[] avg = {-90, -90, -90};
    private long last;
    float toolNorm;    // 0..1 position of the selected tool (drives the dial / knob)

    /**
     * Turns the three band levels (dB) into a pulse that follows the music: a band counts as "hit" when it rises above its own
     * recent average (about 1 s), so steady loud passages do not keep the ears shaking and every kick / snare / hi-hat does.
     * Fast attack, ~0.15 s release. Time based, so calling it twice in one frame (main screen + popup) is harmless.
     */
    void beat(float[] db, boolean on) {
        long now = System.nanoTime(); float dt = Math.min(0.1f, (now - last) / 1e9f); last = now;
        if (dt < 0.004f) return;
        float rel = (float) Math.exp(-dt / 0.15f), att = 1f - (float) Math.exp(-dt / 0.02f), tau = 1f - (float) Math.exp(-dt / 1.0f);
        for (int i = 0; i < 3; i++) {
            float lvl = Math.max(0f, Math.min(1f, (db[i] + 55f) / 35f));
            float hit = Math.max(0f, Math.min(1f, (db[i] - avg[i]) / 8f));
            avg[i] += (db[i] - avg[i]) * tau;
            float tgt = on ? Math.min(1f, 0.22f * lvl + 0.85f * hit) : 0f;
            env[i] = tgt > env[i] ? env[i] + (tgt - env[i]) * att : env[i] * rel;
        }
        bass = env[0];
    }
}
