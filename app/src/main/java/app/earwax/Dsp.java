package app.earwax;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;

/**
 * EARWAX 4.0 Pro Audio DSP Engine (Zero-GC inside ExoPlayer's PCM pipeline):
 *  1. Sample-accurate Micro-Crossfade Ramp on every track switch/seek (eliminates clicks & pops!)
 *  2. Smooth Audio-Focus Ducking Ramp (glides smoothly down to 25% and back up, never harsh cuts!)
 *  3. Pre-amp & 10-band double-precision RBJ Parametric EQ (32 Hz .. 16 kHz) with clean bypass
 *  4. Dedicated Sub-Bass (100 Hz) & Air/Treble (8 kHz) Shelving filters
 *  5. Mid-Side 3D Stereo Width Spatializer (0% mono .. 200% ultra-wide, tight <115 Hz bass-mono)
 *  6. Real Algorithmic Studio Reverb (Schroeder/Freeverb: 4 damped stereo comb + 2 allpass diffusers)
 *  7. 5 Channel Routing modes + Soft-Knee Brickwall Limiter
 *  8. Zero-CPU background gate: FFT/Oscilloscope/VU only compute while UI is visible (`want == true`)
 */
@UnstableApi
final class Dsp extends BaseAudioProcessor {
    static final float[] F = {32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    static final String[] LABELS = {"32", "64", "125", "250", "500", "1K", "2K", "4K", "8K", "16K"};
    static final String[] REVERB_NAMES = {"OFF", "STUDIO", "HALL", "ARENA", "VOID"};
    static final String[] CHAN_NAMES = {"STEREO", "MONO", "MONO L", "MONO R", "SWAP"};
    static final int N = 1024;
    static final int WAVE_N = 64;

    final float[] g = new float[10];
    volatile boolean eqEnabled = true;
    volatile boolean limiterEnabled = true;
    volatile float pre, bass, treble;
    volatile float width = 1.0f;
    volatile int reverbMode = 0;
    volatile int mode = 0;
    volatile int ver = 1;
    volatile boolean want;
    volatile int sampleRate = 44100;
    volatile int channelCount = 2;

    // Smooth click-free switch fade-in & audio-focus ducking envelope
    private volatile float targetDuck = 1.0f;
    private volatile boolean pendingSwitchRamp = false;
    private float curDuck = 1.0f;
    private float switchRamp = 1.0f;
    private float switchStep = 0.0005f;

    // Live analysis outputs
    final float[] bars = new float[32];
    final float[] peaks = new float[32];
    final float[] wave = new float[WAVE_N];
    final float[] band = new float[]{-90, -90, -90};
    volatile float vuL, vuR;

    private int seen, ch = 2, rp;
    private boolean anyBiquad;
    private final double[] cf = new double[12 * 5];
    private final double[] z = new double[2 * 12 * 2];
    private final boolean[] act = new boolean[12];
    private double bassLpCoef = 0.017, bassLpL, bassLpR;

    private static final int[] COMB_BASE = {1116, 1188, 1277, 1356};
    private static final int[] AP_BASE   = {556, 441};
    private static final int STEREO_SPREAD = 23;
    private final float[][] combL = new float[4][3400], combR = new float[4][3400];
    private final float[][] apL   = new float[2][1400], apR   = new float[2][1400];
    private final int[] combLenL = new int[4], combLenR = new int[4], combIdxL = new int[4], combIdxR = new int[4];
    private final int[] apLenL   = new int[2], apLenR   = new int[2], apIdxL   = new int[2], apIdxR   = new int[2];
    private final float[] combFiltL = new float[4], combFiltR = new float[4];
    private float revFeedback, revDamp, revWet;

    private final float[] xs = new float[8];
    private final float[] ring = new float[N], re = new float[N], im = new float[N];
    private final float[] win = new float[N], tc = new float[N / 2], ts = new float[N / 2];
    private final int[] rev = new int[N];

    Dsp() {
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        for (int i = 0; i < N / 2; i++) {
            tc[i] = (float) Math.cos(-2 * Math.PI * i / N);
            ts[i] = (float) Math.sin(-2 * Math.PI * i / N);
        }
        int bits = Integer.numberOfTrailingZeros(N);
        for (int i = 0; i < N; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        configureReverbDelays(44100);
    }

    void touch() { ver++; }

    /** Triggers a silky ~55ms cosine fade-in after flush/track-switch/seek (zero pop, thread-safe!). */
    void triggerSmoothSwitch() {
        pendingSwitchRamp = true;
    }

    /** Smoothly glides gain to `duck` (e.g., 0.25 when ducking, 1.0 normal). */
    void setDuck(float duck) {
        targetDuck = Math.max(0.05f, Math.min(1.0f, duck));
    }

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat f) throws UnhandledAudioFormatException {
        if (f.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(f);
        sampleRate = Math.max(8000, f.sampleRate);
        ch = Math.min(Math.max(1, f.channelCount), 8);
        channelCount = ch;
        seen = 0;
        configureReverbDelays(sampleRate);
        clearStates();
        triggerSmoothSwitch();
        return f;
    }

    @Override
    protected void onFlush() {
        clearStates();
        triggerSmoothSwitch();
    }

    @Override
    protected void onReset() {
        clearStates();
    }

    private void clearStates() {
        for (int i = 0; i < z.length; i++) z[i] = 0;
        bassLpL = 0;
        bassLpR = 0;
        for (int i = 0; i < 4; i++) {
            combFiltL[i] = 0; combFiltR[i] = 0;
            combIdxL[i] = 0;  combIdxR[i] = 0;
            java.util.Arrays.fill(combL[i], 0f);
            java.util.Arrays.fill(combR[i], 0f);
        }
        for (int i = 0; i < 2; i++) {
            apIdxL[i] = 0; apIdxR[i] = 0;
            java.util.Arrays.fill(apL[i], 0f);
            java.util.Arrays.fill(apR[i], 0f);
        }
    }

    private void configureReverbDelays(int sr) {
        double scale = Math.min(2.2, sr / 44100.0);
        for (int i = 0; i < 4; i++) {
            combLenL[i] = Math.min(combL[i].length - 1, Math.max(64, (int) Math.round(COMB_BASE[i] * scale)));
            combLenR[i] = Math.min(combR[i].length - 1, Math.max(64, (int) Math.round((COMB_BASE[i] + STEREO_SPREAD) * scale)));
        }
        for (int i = 0; i < 2; i++) {
            apLenL[i] = Math.min(apL[i].length - 1, Math.max(32, (int) Math.round(AP_BASE[i] * scale)));
            apLenR[i] = Math.min(apR[i].length - 1, Math.max(32, (int) Math.round((AP_BASE[i] + STEREO_SPREAD) * scale)));
        }
        bassLpCoef = 1.0 - Math.exp(-2.0 * Math.PI * 115.0 / Math.max(8000, sr));
    }

    @Override
    public void queueInput(ByteBuffer in) {
        if (seen != ver) {
            recalc();
            seen = ver;
        }
        if (pendingSwitchRamp) {
            pendingSwitchRamp = false;
            switchRamp = 0f;
            switchStep = 1f / Math.max(400f, sampleRate * 0.055f);
        }
        int n = in.remaining();
        if (n == 0) return;
        ByteBuffer o = replaceOutputBuffer(n);

        final int chMode = mode;
        final float preDb = pre;
        final float stWidth = width;
        final int revM = reverbMode;
        final boolean lim = limiterEnabled;
        final boolean doWidth = ch >= 2 && Math.abs(stWidth - 1.0f) > 0.01f;
        final boolean doReverb = revM > 0;
        final float duckGoal = targetDuck;
        final boolean doEnv = (switchRamp < 0.999f) || (Math.abs(curDuck - duckGoal) > 0.002f) || (duckGoal < 0.999f);
        final boolean fx = anyBiquad || chMode != 0 || Math.abs(preDb) > 0.05f || doWidth || doReverb || doEnv;
        final boolean tap = want;

        // Pure zero-CPU passthrough when backgrounded and flat DSP!
        if (!fx && !tap) {
            o.put(in);
            o.flip();
            return;
        }

        final float pg = (Math.abs(preDb) > 0.05f) ? (float) Math.pow(10.0, preDb / 20.0) : 1f;
        final float duckCoeff = 1f / Math.max(800f, sampleRate * 0.08f);
        final int frame = 2 * ch;
        float sumL = 0f, sumR = 0f;
        int samplesCount = 0;

        while (in.remaining() >= frame) {
            // Smooth per-sample envelope (click-free switch + smooth ducking)
            float envGain = 1f;
            if (switchRamp < 1f) {
                switchRamp = Math.min(1f, switchRamp + switchStep);
                // Smooth half-cosine curve (0 -> 1 with zero slope discontinuity!)
                envGain *= 0.5f - 0.5f * (float) Math.cos(Math.PI * switchRamp);
            }
            if (Math.abs(curDuck - duckGoal) > 0.001f) {
                curDuck += (duckGoal - curDuck) * duckCoeff;
            } else {
                curDuck = duckGoal;
            }
            envGain *= curDuck;

            float gIn = pg * envGain;
            for (int c = 0; c < ch; c++) xs[c] = in.getShort() * gIn;

            if (anyBiquad) {
                int nCh = Math.min(ch, 2);
                for (int c = 0; c < nCh; c++) {
                    float v = xs[c];
                    for (int b = 0; b < 12; b++) {
                        if (act[b]) v = bq(c, b, v);
                    }
                    xs[c] = v;
                }
            }

            if (doWidth) {
                float L = xs[0], R = xs[1];
                bassLpL += bassLpCoef * (L - bassLpL);
                bassLpR += bassLpCoef * (R - bassLpR);
                float lowM = (float) (0.5 * (bassLpL + bassLpR));
                float hiL = (float) (L - bassLpL);
                float hiR = (float) (R - bassLpR);
                float mid = 0.5f * (hiL + hiR);
                float side = 0.5f * (hiL - hiR) * stWidth;
                xs[0] = lowM + mid + side;
                xs[1] = lowM + mid - side;
            }

            if (doReverb) {
                float inL = xs[0] * (1f / 32768f);
                float inR = (ch > 1 ? xs[1] : xs[0]) * (1f / 32768f);
                float monoIn = (inL + inR) * 0.018f;
                float outL = 0f, outR = 0f;
                float fb = revFeedback, damp1 = revDamp, damp2 = 1f - damp1;
                for (int i = 0; i < 4; i++) {
                    int iL = combIdxL[i];
                    float yL = combL[i][iL];
                    float fL = yL * damp2 + combFiltL[i] * damp1;
                    if (Math.abs(fL) < 1e-18f) fL = 0f;
                    combFiltL[i] = fL;
                    combL[i][iL] = monoIn + fL * fb;
                    if (++iL >= combLenL[i]) iL = 0;
                    combIdxL[i] = iL;
                    outL += yL;

                    int iR = combIdxR[i];
                    float yR = combR[i][iR];
                    float fR = yR * damp2 + combFiltR[i] * damp1;
                    if (Math.abs(fR) < 1e-18f) fR = 0f;
                    combFiltR[i] = fR;
                    combR[i][iR] = monoIn + fR * fb;
                    if (++iR >= combLenR[i]) iR = 0;
                    combIdxR[i] = iR;
                    outR += yR;
                }
                for (int i = 0; i < 2; i++) {
                    int iL = apIdxL[i];
                    float bufL = apL[i][iL];
                    float vL = -outL + bufL;
                    apL[i][iL] = outL + bufL * 0.5f;
                    if (++iL >= apLenL[i]) iL = 0;
                    apIdxL[i] = iL;
                    outL = vL;

                    int iR = apIdxR[i];
                    float bufR = apR[i][iR];
                    float vR = -outR + bufR;
                    apR[i][iR] = outR + bufR * 0.5f;
                    if (++iR >= apLenR[i]) iR = 0;
                    apIdxR[i] = iR;
                    outR = vR;
                }
                float wet = revWet;
                float dry = 1f - wet * 0.35f;
                xs[0] = (inL * dry + outL * wet * 1.4f) * 32768f;
                if (ch > 1) xs[1] = (inR * dry + outR * wet * 1.4f) * 32768f;
            }

            if (ch >= 2 && chMode != 0) {
                if (chMode == 1) {
                    float m = 0.5f * (xs[0] + xs[1]);
                    xs[0] = m; xs[1] = m;
                } else if (chMode == 2) {
                    xs[1] = xs[0];
                } else if (chMode == 3) {
                    xs[0] = xs[1];
                } else if (chMode == 4) {
                    float tmp = xs[0]; xs[0] = xs[1]; xs[1] = tmp;
                }
            }

            for (int c = 0; c < ch; c++) {
                float s = xs[c];
                if (lim && fx) {
                    float norm = s * (1f / 32768f);
                    float absN = Math.abs(norm);
                    if (absN > 0.85f) {
                        float excess = (absN - 0.85f) / 0.15f;
                        float comp = 0.85f + 0.145f * (float) Math.tanh(excess);
                        norm = norm >= 0 ? comp : -comp;
                    }
                    s = norm * 32768f;
                }
                int iv = Math.round(s);
                if (iv > 32767) iv = 32767;
                else if (iv < -32768) iv = -32768;
                o.putShort((short) iv);
            }

            if (tap) {
                float lN = xs[0] * (1f / 32768f);
                float rN = (ch > 1 ? xs[1] : xs[0]) * (1f / 32768f);
                sumL += lN * lN;
                sumR += rN * rN;
                samplesCount++;
                ring[rp++] = 0.5f * (lN + rN);
                if (rp == N) {
                    fft();
                    rp = 0;
                }
            }
        }

        if (tap && samplesCount > 0) {
            float rmsL = (float) Math.min(1.0, Math.sqrt(sumL / samplesCount) * 2.1);
            float rmsR = (float) Math.min(1.0, Math.sqrt(sumR / samplesCount) * 2.1);
            vuL = rmsL > vuL ? rmsL : vuL * 0.85f + rmsL * 0.15f;
            vuR = rmsR > vuR ? rmsR : vuR * 0.85f + rmsR * 0.15f;
        }
        o.flip();
    }

    private float bq(int c, int b, float x) {
        int k = b * 5;
        int s = (c * 12 + b) * 2;
        double y = cf[k] * x + z[s];
        z[s] = cf[k + 1] * x - cf[k + 3] * y + z[s + 1];
        z[s + 1] = cf[k + 2] * x - cf[k + 4] * y;
        return (float) y;
    }

    private void recalc() {
        anyBiquad = false;
        boolean eq = eqEnabled;
        int sr = Math.max(8000, sampleRate);
        for (int b = 0; b < 10; b++) {
            float gain = eq ? g[b] : 0f;
            act[b] = Math.abs(gain) > 0.05f && F[b] < sr * 0.46f;
            if (act[b]) peak(b, F[b], gain, F[b] >= 8000 ? 1.8 : 1.41);
        }
        act[10] = Math.abs(bass) > 0.05f;
        if (act[10]) shelf(10, 100.0, bass, true);
        act[11] = Math.abs(treble) > 0.05f;
        if (act[11]) shelf(11, 8000.0, treble, false);
        for (boolean a : act) anyBiquad |= a;

        switch (reverbMode) {
            case 1: revFeedback = 0.72f; revDamp = 0.45f; revWet = 0.22f; break;
            case 2: revFeedback = 0.82f; revDamp = 0.35f; revWet = 0.34f; break;
            case 3: revFeedback = 0.88f; revDamp = 0.28f; revWet = 0.44f; break;
            case 4: revFeedback = 0.93f; revDamp = 0.18f; revWet = 0.56f; break;
            default: revFeedback = 0f;   revDamp = 0.5f;  revWet = 0f;    break;
        }
    }

    private void set(int b, double b0, double b1, double b2, double a0, double a1, double a2) {
        int k = b * 5;
        cf[k] = b0 / a0;
        cf[k + 1] = b1 / a0;
        cf[k + 2] = b2 / a0;
        cf[k + 3] = a1 / a0;
        cf[k + 4] = a2 / a0;
    }

    private void peak(int b, double f, double db, double q) {
        double A = Math.pow(10.0, db / 40.0);
        double w = 2.0 * Math.PI * f / Math.max(8000, sampleRate);
        double al = Math.sin(w) / (2.0 * q);
        double c = Math.cos(w);
        set(b, 1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
    }

    private void shelf(int b, double f, double db, boolean low) {
        double A = Math.pow(10.0, db / 40.0);
        double w = 2.0 * Math.PI * f / Math.max(8000, sampleRate);
        double c = Math.cos(w);
        double sA = 2.0 * Math.sqrt(A) * Math.sin(w) * 0.5 * Math.sqrt(2.0);
        if (low) {
            set(b,
                A * ((A + 1) - (A - 1) * c + sA),
                2 * A * ((A - 1) - (A + 1) * c),
                A * ((A + 1) - (A - 1) * c - sA),
                (A + 1) + (A - 1) * c + sA,
                -2 * ((A - 1) + (A + 1) * c),
                (A + 1) + (A - 1) * c - sA);
        } else {
            set(b,
                A * ((A + 1) + (A - 1) * c + sA),
                -2 * A * ((A - 1) + (A + 1) * c),
                A * ((A + 1) + (A - 1) * c - sA),
                (A + 1) - (A - 1) * c + sA,
                2 * ((A - 1) - (A + 1) * c),
                (A + 1) - (A - 1) * c - sA);
        }
    }

    private void fft() {
        int stride = N / WAVE_N;
        for (int i = 0; i < WAVE_N; i++) {
            float acc = 0f;
            int base = i * stride;
            for (int k = 0; k < stride; k++) acc += ring[base + k];
            float v = Math.max(-1f, Math.min(1f, (acc / stride) * 2.4f));
            wave[i] = wave[i] * 0.45f + v * 0.55f;
        }

        for (int i = 0; i < N; i++) {
            int j = rev[i];
            re[j] = ring[i] * win[i];
            im[j] = 0f;
        }
        for (int len = 2; len <= N; len <<= 1) {
            int half = len >> 1;
            int step = N / len;
            for (int i = 0; i < N; i += len) {
                for (int k = 0, t = 0; k < half; k++, t += step) {
                    int a = i + k, b = a + half;
                    float tr = re[b] * tc[t] - im[b] * ts[t];
                    float ti = re[b] * ts[t] + im[b] * tc[t];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }

        for (int i = 0; i < 32; i++) {
            int lo = (int) (Math.pow(i / 32.0, 2.0) * 320) + 1;
            int hi = Math.max(lo + 1, (int) (Math.pow((i + 1) / 32.0, 2.0) * 320) + 1);
            if (hi > N / 2) hi = N / 2;
            float s = 0f;
            for (int k = lo; k < hi; k++) s += (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            double db = 20.0 * Math.log10(s / Math.max(1, hi - lo) / 240.0 + 1e-9);
            float val = (float) Math.max(0.0, Math.min(1.0, (db + 78.0) / 54.0));
            bars[i] = Math.max(val, bars[i] * 0.84f);
            peaks[i] = Math.max(bars[i], peaks[i] - 0.018f);
            if (peaks[i] < 0f) peaks[i] = 0f;
        }

        band[0] = bandDb(1, 5);
        band[1] = bandDb(10, 58);
        band[2] = bandDb(92, 320);
    }

    private float bandDb(int lo, int hi) {
        float s = 0f;
        int end = Math.min(hi, N / 2);
        for (int k = lo; k < end; k++) s += (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]);
        return (float) (20.0 * Math.log10(s / Math.max(1, end - lo) / 240.0 + 1e-9));
    }
}
