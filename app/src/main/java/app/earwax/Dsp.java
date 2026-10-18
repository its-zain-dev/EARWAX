package app.earwax;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

/**
 * The audio brain: 10-band parametric EQ + pre-amp + bass/treble shelves + channel mode,
 * and a 1024-point FFT tap that feeds the spectrum bars. Runs inside ExoPlayer's audio pipeline.
 */
@UnstableApi
final class Dsp extends BaseAudioProcessor {
    static final float[] F = {60, 120, 250, 500, 1000, 2000, 4000, 8000, 12000, 16000};
    final float[] g = new float[10];          // band gains in dB
    volatile float pre, bass, treble;         // dB
    volatile int mode;                        // 0 stereo, 1 mono L, 2 mono R
    volatile int ver = 1;
    final float[] bars = new float[32];

    private int seen, rate = 44100, ch = 2, rp;
    private final double[][] cf = new double[12][5];
    private final boolean[] act = new boolean[12];
    private final double[][][] z = new double[2][12][2];
    private final float[] xs = new float[8], ring = new float[1024];
    private final double[] re = new double[1024], im = new double[1024];

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
        ByteBuffer o = replaceOutputBuffer(in.remaining());
        float pg = (float) Math.pow(10, pre / 20); int m = mode;
        while (in.remaining() >= 2 * ch) {
            for (int c = 0; c < ch; c++) xs[c] = in.getShort() * pg;
            for (int c = 0; c < Math.min(ch, 2); c++)
                for (int b = 0; b < 12; b++) if (act[b]) xs[c] = bq(c, b, xs[c]);
            if (ch >= 2) { if (m == 1) xs[1] = xs[0]; else if (m == 2) xs[0] = xs[1]; }
            for (int c = 0; c < ch; c++) o.putShort((short) Math.max(-32768, Math.min(32767, Math.round(xs[c]))));
            ring[rp++] = (xs[0] + (ch > 1 ? xs[1] : xs[0])) * (0.5f / 32768f);
            if (rp == 1024) { fft(); rp = 0; }
        }
        o.flip();
    }

    private float bq(int c, int b, float x) {
        double[] k = cf[b], s = z[c][b];
        double y = k[0] * x + s[0];
        s[0] = k[1] * x - k[3] * y + s[1];
        s[1] = k[2] * x - k[4] * y;
        return (float) y;
    }

    private void recalc() {
        for (int b = 0; b < 10; b++) { act[b] = Math.abs(g[b]) > 0.05f; if (act[b]) peak(b, F[b], g[b], F[b] > 8000 ? 2 : 1.41); }
        act[10] = Math.abs(bass) > 0.05f; if (act[10]) shelf(10, 120, bass, true);
        act[11] = Math.abs(treble) > 0.05f; if (act[11]) shelf(11, 8000, treble, false);
    }

    private void set(int b, double b0, double b1, double b2, double a0, double a1, double a2) {
        cf[b][0] = b0 / a0; cf[b][1] = b1 / a0; cf[b][2] = b2 / a0; cf[b][3] = a1 / a0; cf[b][4] = a2 / a0;
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
        final int n = 1024;
        for (int i = 0; i < n; i++) { re[i] = ring[i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n)); im[i] = 0; }
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
        for (int i = 0; i < 32; i++) {
            int lo = (int) (Math.pow(i / 32.0, 2) * 300) + 1, hi = Math.max(lo + 1, (int) (Math.pow((i + 1) / 32.0, 2) * 300) + 1);
            double s = 0; for (int k = lo; k < hi; k++) s += Math.hypot(re[k], im[k]);
            double db = 20 * Math.log10(s / (hi - lo) / 256 + 1e-9);
            bars[i] = (float) Math.max(Math.max(0, Math.min(1, (db + 80) / 56)), bars[i] * 0.86);
        }
    }
}
