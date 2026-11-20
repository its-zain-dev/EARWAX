package app.earwax;

import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * EARWAX 4.0 Early-2000s Y2K Liquid-Chrome, Glossy Aqua & Obsidian Glass Design System.
 * Supports 5 live neon-chrome palettes that dynamically re-tint the Forehead Visor,
 * spectrum visualizers, ear acoustic shockwaves, Aqua crown orbs, Chest Console,
 * modal ChromeDialogs, and all 3 Floating 3D Y2K Popups.
 */
final class Theme {
    static final class Palette {
        final String name;
        final int primary;      // main luminous accent (e.g., Y2K electric aqua-cyan)
        final int secondary;    // complementary accent (e.g., sapphire / cyber violet)
        final int glow;         // high-luminance specular bloom highlight
        final int dim;          // muted accent for tracks / recessed strokes
        final int surfaceTint;  // subtle translucent glass surface tint
        final int notifBg;      // notification colorized chrome tone

        Palette(String name, int primary, int secondary, int glow, int dim, int surfaceTint, int notifBg) {
            this.name = name;
            this.primary = primary;
            this.secondary = secondary;
            this.glow = glow;
            this.dim = dim;
            this.surfaceTint = surfaceTint;
            this.notifBg = notifBg;
        }
    }

    static final Palette[] PALETTES = {
        new Palette("AQUA",    0xff33d6ff, 0xff6b78ff, 0xffc8f6ff, 0xff225f84, 0xff091a2e, 0xff122338),
        new Palette("VIOLET",  0xffb474ff, 0xff38d9ff, 0xffe8d2ff, 0xff5a3c8a, 0xff16102a, 0xff21173b),
        new Palette("MINT",    0xff34ffb6, 0xff32d4ff, 0xffc6ffea, 0xff206d55, 0xff08211b, 0xff102c25),
        new Palette("RUBY",    0xffff4d74, 0xffff9642, 0xffffc8d4, 0xff7a2b3d, 0xff240e16, 0xff301520),
        new Palette("SOLAR",   0xffffd25a, 0xffff8838, 0xfffff2c6, 0xff78602c, 0xff221a0d, 0xff2e2313)
    };

    // Core Early-2000s Obsidian, Aqua & Liquid-Chrome constants
    static final int BG_DEEP      = 0xff040810;
    static final int GLASS_TOP    = 0xff0b192e;
    static final int GLASS_BOT    = 0xff040914;
    static final int CARD_BG      = 0xc40b182b;
    static final int CARD_HOT     = 0xe6132b4a;
    static final int CARD_STROKE  = 0x6684c4f0;
    static final int TRACK_BG     = 0xff0b1729;
    static final int TEXT_MAIN    = 0xfff2f8ff;
    static final int TEXT_SUB     = 0xff92accb;
    static final int TEXT_MUTED   = 0xff5a7496;
    static final int HEART_ON     = 0xffff4d79;
    static final int DANGER       = 0xffff5266;
    static final int YT_BADGE     = 0xffff4769;
    static final int OFF_BADGE    = 0xff34ffb6;
    static final int CHROME_HI    = 0xfff0f7ff;
    static final int CHROME_MID   = 0xff829cb9;
    static final int CHROME_LO    = 0xff243248;

    static final Typeface TF_REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface TF_MEDIUM  = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface TF_BOLD    = Typeface.create("sans-serif", Typeface.BOLD);
    static final Typeface TF_MONO    = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    private static volatile int currentIdx = 0;

    static void set(int idx) {
        currentIdx = Math.max(0, Math.min(PALETTES.length - 1, idx));
    }

    static int index() { return currentIdx; }
    static Palette get() { return PALETTES[currentIdx]; }
    static int primary() { return PALETTES[currentIdx].primary; }
    static int secondary() { return PALETTES[currentIdx].secondary; }
    static int glow() { return PALETTES[currentIdx].glow; }
    static int dim() { return PALETTES[currentIdx].dim; }

    static int alpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0x00ffffff);
    }

    static int lerp(int c0, int c1, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (Color.alpha(c0) + (Color.alpha(c1) - Color.alpha(c0)) * t);
        int r = (int) (Color.red(c0) + (Color.red(c1) - Color.red(c0)) * t);
        int g = (int) (Color.green(c0) + (Color.green(c1) - Color.green(c0)) * t);
        int b = (int) (Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * t);
        return Color.argb(a, r, g, b);
    }

    static LinearGradient glassGrad(float top, float bottom) {
        return new LinearGradient(0, top, 0, bottom,
                new int[]{0xff0e2039, GLASS_TOP, GLASS_BOT},
                new float[]{0f, 0.28f, 1f}, Shader.TileMode.CLAMP);
    }

    static LinearGradient accentGrad(float x0, float y0, float x1, float y1) {
        Palette p = get();
        return new LinearGradient(x0, y0, x1, y1, p.primary, p.secondary, Shader.TileMode.CLAMP);
    }

    /** Authentic Early-2000s glossy Aqua candy-pill gradient (bright top reflection, deep body, bottom caustic). */
    static LinearGradient aquaPillGrad(float top, float bottom, int baseColor) {
        int hi = lerp(baseColor, 0xffffffff, 0.52f);
        int lo = lerp(baseColor, 0xff051020, 0.45f);
        int bounce = lerp(baseColor, 0xffb8f6ff, 0.35f);
        return new LinearGradient(0, top, 0, bottom,
                new int[]{hi, baseColor, lo, bounce},
                new float[]{0f, 0.44f, 0.72f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Multi-stop early-2000s liquid-mercury metallic chrome sheen. */
    static LinearGradient chromeGrad(float x0, float y0, float x1, float y1) {
        return new LinearGradient(x0, y0, x1, y1,
                new int[]{CHROME_HI, CHROME_MID, 0xffe0eeff, CHROME_LO, 0xff9bb6d6},
                new float[]{0f, 0.32f, 0.56f, 0.80f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Curved top-half glossy glass reflection sheen (classic Y2K Aqua glass look). */
    static LinearGradient y2kGlossSheen(float top, float bottom) {
        return new LinearGradient(0, top, 0, bottom,
                new int[]{0x42ffffff, 0x16d8f4ff, 0x00ffffff},
                new float[]{0f, 0.48f, 1f}, Shader.TileMode.CLAMP);
    }

    static void applyGlow(Paint p, float radius, int color, int alphaVal) {
        // Lightweight zero-blur mode: avoids GPU/software shadow-layer stalls on Android!
        p.clearShadowLayer();
    }
}
