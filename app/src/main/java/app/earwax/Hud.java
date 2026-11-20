package app.earwax;

/**
 * Reusable zero-allocation snapshot of player, DSP, and beat state shared by StageView,
 * VisorRenderer, ConsoleRenderer, PopupView, and Notif on the main thread.
 */
final class Hud {
    String title = "EARWAX", artist = "", album = "", badge = "", tool = "VOL", toolText = "100%";
    long pos, dur;
    boolean playing, live, buffering, fav, eqOn = true, limiterOn = true, shuffle;
    int repeat, reverbMode, sleepSec, sampleRate = 44100;
    float toolNorm = 1f, vuL, vuR;

    final float[] bars = new float[32];
    final float[] peaks = new float[32];
    final float[] wave = new float[Dsp.WAVE_N];
    final float[] env = new float[3];        // 0..1 beat-reactive pulse for Low / Mid / High ear cones
    float bass;                              // low-band pulse
    float shock;                             // 0..1 decaying acoustic shockwave pulse on kick hits
    int accentColor;                         // blended theme/album glow color

    private final float[] avg = {-90f, -90f, -90f};
    private long lastNs;
    private float prevBass;

    /**
     * Computes beat-synchronous envelopes from the 3 raw dB bands with fast 18ms attack
     * and crisp 140ms release, plus a kick-hit trigger for ear acoustic shockwave rings.
     */
    void beat(float[] db, boolean on) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (dt < 0.004f) return;

        float rel = (float) Math.exp(-dt / 0.14f);
        float att = 1f - (float) Math.exp(-dt / 0.018f);
        float tau = 1f - (float) Math.exp(-dt / 0.95f);

        for (int i = 0; i < 3; i++) {
            float lvl = Math.max(0f, Math.min(1f, (db[i] + 54f) / 34f));
            float hit = Math.max(0f, Math.min(1f, (db[i] - avg[i]) / 7.5f));
            avg[i] += (db[i] - avg[i]) * tau;
            float tgt = on ? Math.min(1f, 0.24f * lvl + 0.88f * hit) : 0f;
            env[i] = tgt > env[i] ? env[i] + (tgt - env[i]) * att : env[i] * rel;
        }

        bass = env[0];
        if (on && bass > 0.52f && (bass - prevBass) > 0.12f) {
            shock = 1.0f;
        } else {
            shock *= (float) Math.exp(-dt / 0.34f);
            if (shock < 0.01f) shock = 0f;
        }
        prevBass = bass;
    }
}
