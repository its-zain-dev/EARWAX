package app.earwax;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

/**
 * The audio brain: 10-band parametric EQ + pre-amp + bass/treble shelves + channel mode, and a 1024-point FFT tap that
 * feeds the spectrum bars and the ear vibration. Runs inside ExoPlayer's audio pipeline.
 *
 * Cheap by design: with a flat EQ the audio is copied straight through, and the FFT only runs while a screen that shows
 * it is visible (`want`). All tables are precomputed and nothing is allocated per buffer.
 */
@UnstableApi
final class Dsp extends BaseAudioProcessor {
    static final float[] F = {60, 120, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000};
    static final int N = 1024;
    final float[] g = new float[10];          // band gains in dB
    volatile float pre, bass, treble;         // dB
    volatile int mode;                        // 0 stereo, 1 mono L, 2 mono R
    volatile int ver = 1;
    volatile boolean want;                    // a visible screen needs the spectrum
    final float[] bars = new float[32];
<<<<<<< HEAD
    final float[] band = new float[]{-90, -90, -90};   // low / mid / high level in dB (see fft)
=======
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10

    private int seen, rate = 44100, ch = 2, rp;
    private boolean anyAct;
    private final double[] cf = new double[12 * 5], z = new double[2 * 12 * 2];
    private final boolean[] act = new boolean[12];
    private final float[] xs = new float[8], ring = new float[N], re = new float[N], im = new float[N], win = new float[N], tc = new float[N / 2], ts = new float[N / 2];
    private final int[] rev = new int[N];

    Dsp() {
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        for (int i = 0; i < N / 2; i++) { tc[i] = (float) Math.cos(-2 * Math.PI * i / N); ts[i] = (float) Math.sin(-2 * Math.PI * i / N); }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    void touch() { ver++; }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat f) throws UnhandledAudioFormatException {
        if (f.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(f);
        rate = f.sampleRate; ch = Math.min(f.channelCount, 8); seen = 0;
        return f;
    }

    @Override
    public void queueInput(ByteBuffer in) {
        if (seen != ver) { recalc(); seen = ver; }
        int n = in.remaining();
        if (n == 0) return;
        ByteBuffer o = replaceOutputBuffer(n);
        final int m = mode; final float p = pre;
        final boolean fx = anyAct || m != 0 || Math.abs(p) > 0.05f, tap = want;
        if (!fx && !tap) { o.put(in); o.flip(); return; }          // flat EQ, nobody looking: zero-cost passthrough
        final float pg = fx ? (float) Math.pow(10, p / 20) : 1f;
        final int frame = 2 * ch;
        while (in.remaining() >= frame) {
            for (int c = 0; c < ch; c++) xs[c] = in.getShort() * pg;
            if (fx) {
                for (int c = 0; c < Math.min(ch, 2); c++)
                    for (int b = 0; b < 12; b++) if (act[b]) xs[c] = bq(c, b, xs[c]);
                if (ch >= 2) { if (m == 1) xs[1] = xs[0]; else if (m == 2) xs[0] = xs[1]; }
            }
            for (int c = 0; c < ch; c++) o.putShort((short) Math.max(-32768, Math.min(32767, Math.round(xs[c]))));
            if (tap) {
                ring[rp++] = (xs[0] + (ch > 1 ? xs[1] : xs[0])) * (0.5f / 32768f);
                if (rp == N) { fft(); rp = 0; }
            }
        }
        o.flip();
    }

    private float bq(int c, int b, float x) {
        int k = b * 5, s = (c * 12 + b) * 2;
        double y = cf[k] * x + z[s];
        z[s] = cf[k + 1] * x - cf[k + 3] * y + z[s + 1];
        z[s + 1] = cf[k + 2] * x - cf[k + 4] * y;
        return (float) y;
    }

    private void recalc() {
        anyAct = false;
        for (int b = 0; b < 10; b++) { act[b] = Math.abs(g[b]) > 0.05f; if (act[b]) peak(b, F[b], g[b], F[b] > 8000 ? 2 : 1.41); }
        act[10] = Math.abs(bass) > 0.05f; if (act[10]) shelf(10, 120, bass, true);
        act[11] = Math.abs(treble) > 0.05f; if (act[11]) shelf(11, 8000, treble, false);
        for (boolean a : act) anyAct |= a;
    }

    private void set(int b, double b0, double b1, double b2, double a0, double a1, double a2) {
        int k = b * 5; cf[k] = b0 / a0; cf[k + 1] = b1 / a0; cf[k + 2] = b2 / a0; cf[k + 3] = a1 / a0; cf[k + 4] = a2 / a0;
    }

    private void peak(int b, double f, double db, double q) {   // RBJ cookbook
        double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / rate, al = Math.sin(w) / (2 * q), c = Math.cos(w);
        set(b, 1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
    }

    private void shelf(int b, double f, double db, boolean low) {
        double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / rate, c = Math.cos(w), sA = 2 * Math.sqrt(A) * Math.sin(w) / 2 * Math.sqrt(2);
        if (low) set(b, A * ((A + 1) - (A - 1) * c + sA), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sA),
                (A + 1) + (A - 1) * c + sA, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sA);
        else set(b, A * ((A + 1) + (A - 1) * c + sA), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sA),
                (A + 1) - (A - 1) * c + sA, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sA);
    }

    private void fft() {
        for (int i = 0; i < N; i++) { int j = rev[i]; re[j] = ring[i] * win[i]; im[j] = 0; }
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1, step = N / len;
            for (int i = 0; i < N; i += len) {
                for (int k = 0, t = 0; k < half; k++, t += step) {
                    int a = i + k, b = a + half;
                    float tr = re[b] * tc[t] - im[b] * ts[t], ti = re[b] * ts[t] + im[b] * tc[t];
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                }
            }
        }
        for (int i = 0; i < 32; i++) {
            int lo = (int) (Math.pow(i / 32.0, 2) * 300) + 1, hi = Math.max(lo + 1, (int) (Math.pow((i + 1) / 32.0, 2) * 300) + 1);
            float s = 0; for (int k = lo; k < hi; k++) s += (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            double db = 20 * Math.log10(s / (hi - lo) / 256 + 1e-9);
            bars[i] = (float) Math.max(Math.max(0, Math.min(1, (db + 80) / 56)), bars[i] * 0.86);
        }
<<<<<<< HEAD
        // raw (un-clamped) level in dB of three bands: low 43-170 Hz, mid 0.5-2.5 kHz, high 4 kHz+. The bars above saturate on loud
        // music, so they cannot show the beat; these keep their full dynamic range for the speaker cones.
        band[0] = bandDb(1, 5); band[1] = bandDb(11, 59); band[2] = bandDb(95, 301);
    }

    private float bandDb(int lo, int hi) {
        float s = 0; for (int k = lo; k < hi; k++) s += (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
        return (float) (20 * Math.log10(s / (hi - lo) / 256 + 1e-9));
=======
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
    }
}
