package app.earwax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * EARWAX 4.0 Pure-Python 3D Skin Loader & Crisp Vector Icon / Typography Engine.
 * Loads analytical Python-raymarched Y2K liquid-chrome PNG assets + pixel-calibrated JSON
 * hitboxes/screens with exact RAM-disciplined decoding (never double-downscales!).
 */
final class Skin {
    static final class Btn { String id; float cx, cy, r; }
    static final class Dial { float cx, cy, r0, r1, a0, a1; }
    static final class Ear { Bitmap bmp; final RectF r = new RectF(); boolean left; }

    static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private static final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint T = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
    private static final Path PATH = new Path();
    private static final RectF R1 = new RectF();

    static {
        T.setTypeface(Theme.TF_BOLD);
    }

    static void resetPaints() {
        P.setShader(null); P.setColorFilter(null); P.setXfermode(null); P.setAlpha(255);
        Q.setShader(null); Q.setColorFilter(null); Q.setXfermode(null); Q.setAlpha(255); Q.setStyle(Paint.Style.FILL); Q.clearShadowLayer();
        T.setShader(null); T.setColorFilter(null); T.setXfermode(null); T.setAlpha(255); T.clearShadowLayer();
        T.setTypeface(Theme.TF_BOLD);
    }

    Bitmap bmp, cone, pearl;
    int w, h;
    float coneW;
    final RectF full = new RectF();
    final ArrayList<Btn> btns = new ArrayList<>();
    final ArrayList<Ear> ears = new ArrayList<>();
    RectF lcd, info, spec, screen, panel, meter, dock;
    Btn knob;
    Dial dial;

    static Skin load(Context c, String n, int targetPx) {
        try {
            Skin s = new Skin();
            JSONObject j = new JSONObject(readAsset(c, "skins/" + n + ".json"));
            s.w = j.getInt("w");
            s.h = j.getInt("h");
            s.full.set(0, 0, s.w, s.h);
            int maxW = targetPx <= 0 ? 0 : targetPx;
            s.bmp = decodeMaxW(c, "skins/" + n + ".png", maxW);
            JSONArray a = j.optJSONArray("buttons");
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Btn b = new Btn();
                    b.id = o.getString("id");
                    b.cx = (float) o.getDouble("cx");
                    b.cy = (float) o.getDouble("cy");
                    b.r = (float) o.getDouble("r");
                    s.btns.add(b);
                    if ("knob".equals(b.id)) s.knob = b;
                }
            }
            s.lcd = rc(j, "lcd");
            s.info = rc(j, "info");
            s.spec = rc(j, "spec");
            s.screen = rc(j, "screen");
            s.panel = rc(j, "panel");
            s.meter = rc(j, "meter");
            s.dock = rc(j, "dock");
            JSONObject d = j.optJSONObject("dial");
            if (d != null) {
                Dial dl = new Dial();
                dl.cx = (float) d.getDouble("cx");
                dl.cy = (float) d.getDouble("cy");
                dl.r0 = (float) d.getDouble("r0");
                dl.r1 = (float) d.getDouble("r1");
                dl.a0 = (float) d.getDouble("a0");
                dl.a1 = (float) d.getDouble("a1");
                s.dial = dl;
            }
            for (String en : new String[]{"ear_l", "ear_r"}) {
                JSONObject o = j.optJSONObject(en);
                if (o == null) continue;
                Ear e = new Ear();
                e.left = "ear_l".equals(en);
                float ew = (float) o.getDouble("w");
                e.r.set((float) o.getDouble("x"), (float) o.getDouble("y"),
                        (float) (o.getDouble("x") + ew),
                        (float) (o.getDouble("y") + o.getDouble("h")));
                int earMaxW = maxW <= 0 ? 0 : Math.max(180, Math.round(ew * maxW / (float) s.w));
                e.bmp = decodeMaxW(c, "skins/" + en + ".png", earMaxW);
                s.ears.add(e);
            }
            JSONObject co = j.optJSONObject("cone");
            if (co != null) {
                s.coneW = (float) co.getDouble("w");
                s.cone = decodeMaxW(c, "skins/cone.png", 384);
            }
            try {
                s.pearl = decodeMaxW(c, "skins/pearl.png", 384);
            } catch (Exception ignored) { }
            return s;
        } catch (Exception e) {
            throw new RuntimeException("skin " + n, e);
        }
    }

    void release() {
        if (bmp != null) bmp.recycle();
        if (cone != null) cone.recycle();
        if (pearl != null) pearl.recycle();
        for (Ear e : ears) if (e.bmp != null) e.bmp.recycle();
        bmp = null; cone = null; pearl = null; ears.clear();
    }

    static Bitmap loadBitmap(Context c, String path, int maxW) {
        try {
            return decodeMaxW(c, path, maxW);
        } catch (Exception e) {
            return null;
        }
    }

    static JSONObject loadJson(Context c, String path) {
        try {
            return new JSONObject(readAsset(c, path));
        } catch (Exception e) {
            return null;
        }
    }

    private static String readAsset(Context c, String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(path)))) {
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
        }
        return sb.toString();
    }

    /** Decodes asset PNG cleanly so actual bitmap width never exceeds maxW (never double-downscales!). */
    private static Bitmap decodeMaxW(Context c, String path, int maxW) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getAssets().open(path)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int srcW = Math.max(1, bounds.outWidth);
        int ss = 1;
        while (maxW > 0 && (srcW / (ss * 2)) >= maxW) ss *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inScaled = false;
        o.inSampleSize = ss;
        Bitmap b;
        try (InputStream in = c.getAssets().open(path)) {
            b = BitmapFactory.decodeStream(in, null, o);
        }
        if (b != null && maxW > 0 && b.getWidth() > maxW * 1.12f) {
            float k = maxW / (float) b.getWidth();
            Bitmap sc = Bitmap.createScaledBitmap(b,
                    Math.max(1, Math.round(b.getWidth() * k)),
                    Math.max(1, Math.round(b.getHeight() * k)), true);
            if (sc != b) b.recycle();
            b = sc;
        }
        if (b != null) {
            try { b.setHasMipMap(true); } catch (Exception ignored) { }
        }
        return b;
    }

    private static RectF rc(JSONObject j, String key) throws JSONException {
        JSONObject o = j.optJSONObject(key);
        if (o == null) return null;
        float x = (float) o.getDouble("x"), y = (float) o.getDouble("y");
        return new RectF(x, y, x + (float) o.getDouble("w"), y + (float) o.getDouble("h"));
    }

    Btn hit(float x, float y) {
        Btn best = null;
        float bd = 1e9f;
        for (Btn b : btns) {
            float d = (float) Math.hypot(x - b.cx, y - b.cy);
            if (d <= b.r + 12f && d < bd) { bd = d; best = b; }
        }
        return best;
    }

    Btn find(String id) {
        for (Btn b : btns) if (b.id.equals(id)) return b;
        return null;
    }

    // ------------------------------------------------------------------ Typography helpers
    static void text(Canvas c, String s, float x, float y, float size, int col, Paint.Align al) {
        text(c, s, x, y, size, col, al, Theme.TF_BOLD, false);
    }

    static void text(Canvas c, String s, float x, float y, float size, int col, Paint.Align al, Typeface tf, boolean glow) {
        if (s == null || s.isEmpty()) return;
        T.setTypeface(tf);
        T.setTextSize(size);
        T.setColor(col);
        T.setTextAlign(al);
        // Zero-blur high-FPS text rendering (no expensive setShadowLayer passes!)
        T.clearShadowLayer();
        c.drawText(s, x, y, T);
    }

    static float width(String s, float size, Typeface tf) {
        T.setTypeface(tf);
        T.setTextSize(size);
        return T.measureText(s);
    }

    static String fit(String s, float size, float max) {
        return fit(s, size, max, Theme.TF_BOLD);
    }

    static String fit(String s, float size, float max, Typeface tf) {
        if (s == null) return "";
        T.setTypeface(tf);
        T.setTextSize(size);
        if (T.measureText(s) <= max) return s;
        int n = s.length();
        float ell = T.measureText("…");
        while (n > 1 && T.measureText(s, 0, n) + ell > max) n--;
        return s.substring(0, n) + "…";
    }

    static String mmss(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000, m = s / 60;
        s %= 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    static void marquee(Canvas c, String s, float x, float y, float size, int col, float room, Typeface tf, boolean glow) {
        if (s == null || s.isEmpty()) return;
        T.setTypeface(tf);
        T.setTextSize(size);
        float tw = T.measureText(s);
        if (tw <= room) {
            text(c, s, x, y, size, col, Paint.Align.LEFT, tf, glow);
            return;
        }
        float gap = 85f;
        float period = tw + gap;
        float off = -((System.currentTimeMillis() / 24f) % period);
        text(c, s, x + off, y, size, col, Paint.Align.LEFT, tf, glow);
        text(c, s, x + off + period, y, size, col, Paint.Align.LEFT, tf, glow);
    }

    // ------------------------------------------------------------------ Crisp Vector Glyphs
    private static void tri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
        PATH.reset();
        PATH.moveTo(x1, y1);
        PATH.lineTo(x2, y2);
        PATH.lineTo(x3, y3);
        PATH.close();
        c.drawPath(PATH, Q);
    }

    static void heart(Canvas c, float cx, float cy, float k, boolean filled, int col) {
        PATH.reset();
        PATH.moveTo(cx, cy + k * 0.95f);
        PATH.cubicTo(cx - k * 1.7f, cy - k * 0.1f, cx - k * 0.7f, cy - k * 1.5f, cx, cy - k * 0.45f);
        PATH.cubicTo(cx + k * 0.7f, cy - k * 1.5f, cx + k * 1.7f, cy - k * 0.1f, cx, cy + k * 0.95f);
        PATH.close();
        Q.setColor(col);
        Q.setStrokeWidth(k * 0.24f);
        Q.setStrokeJoin(Paint.Join.ROUND);
        Q.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
        if (filled) Theme.applyGlow(Q, k * 0.9f, col, 150); else Q.clearShadowLayer();
        c.drawPath(PATH, Q);
        Q.clearShadowLayer();
    }

    static void glyph(Canvas c, String id, float cx, float cy, float r, boolean playing, int col, int repeatMode) {
        glyph(c, id, cx, cy, r, playing, col, repeatMode, false);
    }

    static void glyph(Canvas c, String id, float cx, float cy, float r, boolean playing, int col, int repeatMode, boolean dropShadow) {
        float k = r * 0.44f;
        Q.setColor(col);
        Q.setStyle(Paint.Style.FILL);
        Q.setStrokeCap(Paint.Cap.ROUND);
        Q.setStrokeJoin(Paint.Join.ROUND);
        Q.setStrokeWidth(Math.max(2.2f, r * 0.13f));
        Q.clearShadowLayer();

        switch (id) {
            case "prev":
                c.drawRoundRect(cx - k, cy - k * 0.92f, cx - k * 0.56f, cy + k * 0.92f, k * 0.2f, k * 0.2f, Q);
                tri(c, cx + k * 0.95f, cy - k * 0.92f, cx + k * 0.95f, cy + k * 0.92f, cx - k * 0.52f, cy);
                break;

            case "next":
                c.drawRoundRect(cx + k * 0.56f, cy - k * 0.92f, cx + k, cy + k * 0.92f, k * 0.2f, k * 0.2f, Q);
                tri(c, cx - k * 0.95f, cy - k * 0.92f, cx - k * 0.95f, cy + k * 0.92f, cx + k * 0.52f, cy);
                break;

            case "play":
                if (playing) {
                    c.drawRoundRect(cx - k * 0.85f, cy - k * 0.95f, cx - k * 0.22f, cy + k * 0.95f, k * 0.2f, k * 0.2f, Q);
                    c.drawRoundRect(cx + k * 0.22f, cy - k * 0.95f, cx + k * 0.85f, cy + k * 0.95f, k * 0.2f, k * 0.2f, Q);
                } else {
                    tri(c, cx - k * 0.62f, cy - k * 1.05f, cx - k * 0.62f, cy + k * 1.05f, cx + k * 1.08f, cy);
                }
                break;

            case "list":
                Q.setStyle(Paint.Style.STROKE);
                for (int i = -1; i <= 1; i++) {
                    float ly = cy + i * k * 0.72f;
                    c.drawLine(cx - k * 0.45f, ly, cx + k * 0.95f, ly, Q);
                }
                Q.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) {
                    c.drawCircle(cx - k * 0.85f, cy + i * k * 0.72f, k * 0.19f, Q);
                }
                break;

            case "eq":
                Q.setStyle(Paint.Style.STROKE);
                float[] eqPos = {-0.35f, 0.35f, -0.15f};
                for (int i = -1; i <= 1; i++) {
                    float lx = cx + i * k * 0.78f;
                    c.drawLine(lx, cy - k * 0.92f, lx, cy + k * 0.92f, Q);
                }
                Q.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) {
                    float lx = cx + i * k * 0.78f;
                    c.drawCircle(lx, cy + eqPos[i + 1] * k, k * 0.28f, Q);
                }
                break;

            case "fx":
                Q.setStyle(Paint.Style.STROKE);
                PATH.reset();
                PATH.moveTo(cx - k, cy);
                PATH.lineTo(cx - k * 0.58f, cy);
                PATH.lineTo(cx - k * 0.32f, cy - k * 0.85f);
                PATH.lineTo(cx, cy + k * 0.85f);
                PATH.lineTo(cx + k * 0.34f, cy - k * 0.55f);
                PATH.lineTo(cx + k * 0.62f, cy);
                PATH.lineTo(cx + k, cy);
                c.drawPath(PATH, Q);
                Q.setStyle(Paint.Style.FILL);
                break;

            case "shuffle":
                Q.setStyle(Paint.Style.STROKE);
                PATH.reset();
                PATH.moveTo(cx - k * 0.95f, cy - k * 0.58f);
                PATH.cubicTo(cx - k * 0.1f, cy - k * 0.58f, cx + k * 0.1f, cy + k * 0.58f, cx + k * 0.78f, cy + k * 0.58f);
                PATH.moveTo(cx - k * 0.95f, cy + k * 0.58f);
                PATH.cubicTo(cx - k * 0.1f, cy + k * 0.58f, cx + k * 0.1f, cy - k * 0.58f, cx + k * 0.78f, cy - k * 0.58f);
                c.drawPath(PATH, Q);
                Q.setStyle(Paint.Style.FILL);
                tri(c, cx + k * 0.62f, cy - k * 0.95f, cx + k * 0.62f, cy - k * 0.22f, cx + k * 1.12f, cy - k * 0.58f);
                tri(c, cx + k * 0.62f, cy + k * 0.22f, cx + k * 0.62f, cy + k * 0.95f, cx + k * 1.12f, cy + k * 0.58f);
                break;

            case "repeat":
                Q.setStyle(Paint.Style.STROKE);
                R1.set(cx - k * 0.78f, cy - k * 0.78f, cx + k * 0.78f, cy + k * 0.78f);
                c.drawArc(R1, -60, 295, false, Q);
                Q.setStyle(Paint.Style.FILL);
                tri(c, cx + k * 0.25f, cy - k * 1.12f, cx + k * 0.95f, cy - k * 0.72f, cx + k * 0.35f, cy - k * 0.35f);
                if (repeatMode == 1) {
                    text(c, "1", cx, cy + k * 0.32f, k * 0.85f, col, Paint.Align.CENTER, Theme.TF_BOLD, false);
                }
                break;

            case "sleep":
                PATH.reset();
                R1.set(cx - k * 0.88f, cy - k * 0.88f, cx + k * 0.88f, cy + k * 0.88f);
                PATH.addArc(R1, 40, 240);
                PATH.cubicTo(cx - k * 0.15f, cy - k * 0.35f, cx - k * 0.15f, cy + k * 0.45f,
                        cx + k * 0.67f, cy + k * 0.56f);
                PATH.close();
                c.drawPath(PATH, Q);
                break;

            case "popup":
                Q.setStyle(Paint.Style.STROKE);
                R1.set(cx - k * 0.95f, cy - k * 0.72f, cx + k * 0.95f, cy + k * 0.72f);
                c.drawRoundRect(R1, k * 0.25f, k * 0.25f, Q);
                Q.setStyle(Paint.Style.FILL);
                R1.set(cx + k * 0.05f, cy - k * 0.02f, cx + k * 0.76f, cy + k * 0.52f);
                c.drawRoundRect(R1, k * 0.16f, k * 0.16f, Q);
                break;

            case "close":
                Q.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - k * 0.72f, cy - k * 0.72f, cx + k * 0.72f, cy + k * 0.72f, Q);
                c.drawLine(cx + k * 0.72f, cy - k * 0.72f, cx - k * 0.72f, cy + k * 0.72f, Q);
                break;

            case "tool":
                Q.setStyle(Paint.Style.STROKE);
                for (int i = -1; i <= 1; i++) {
                    float ly = cy + i * k * 0.72f;
                    c.drawLine(cx - k * 0.88f, ly, cx + k * 0.88f, ly, Q);
                }
                Q.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) {
                    c.drawCircle(cx + i * k * 0.42f, cy + i * k * 0.72f, k * 0.26f, Q);
                }
                break;

            case "skin":
                Q.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx - k * 0.24f, cy - k * 0.16f, k * 0.54f, Q);
                R1.set(cx - k * 0.08f, cy - k * 0.08f, cx + k * 0.84f, cy + k * 0.84f);
                c.drawRoundRect(R1, k * 0.28f, k * 0.28f, Q);
                break;

            case "app":
                Q.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - k * 0.75f, cy + k * 0.75f, cx + k * 0.75f, cy - k * 0.75f, Q);
                c.drawLine(cx + k * 0.1f, cy - k * 0.75f, cx + k * 0.75f, cy - k * 0.75f, Q);
                c.drawLine(cx + k * 0.75f, cy - k * 0.75f, cx + k * 0.75f, cy - k * 0.1f, Q);
                c.drawLine(cx - k * 0.75f, cy + k * 0.1f, cx - k * 0.75f, cy + k * 0.75f, Q);
                c.drawLine(cx - k * 0.75f, cy + k * 0.75f, cx - k * 0.1f, cy + k * 0.75f, Q);
                break;

            case "mode":
                Q.setStyle(Paint.Style.STROKE);
                R1.set(cx - k * 0.8f, cy - k * 0.8f, cx + k * 0.8f, cy + k * 0.8f);
                c.drawArc(R1, 40, 280, false, Q);
                Q.setStyle(Paint.Style.FILL);
                tri(c, cx + k * 0.2f, cy - k * 1.25f, cx + k * 0.2f, cy - k * 0.45f, cx + k * 1.0f, cy - k * 0.85f);
                break;

            case "drawers":
                // Twin 3D Wing Drawers icon (left & right expanding panels + center core)
                Q.setStyle(Paint.Style.FILL);
                R1.set(cx - k * 0.30f, cy - k * 0.56f, cx + k * 0.30f, cy + k * 0.56f);
                c.drawRoundRect(R1, k * 0.16f, k * 0.16f, Q);
                // Left 3D angled wing arrow
                tri(c, cx - k * 1.15f, cy, cx - k * 0.54f, cy - k * 0.56f, cx - k * 0.54f, cy + k * 0.56f);
                // Right 3D angled wing arrow
                tri(c, cx + k * 1.15f, cy, cx + k * 0.54f, cy - k * 0.56f, cx + k * 0.54f, cy + k * 0.56f);
                break;

            default:
                text(c, id.toUpperCase(), cx, cy + r * 0.25f, r * 0.55f, col, Paint.Align.CENTER);
                break;
        }
        Q.clearShadowLayer();
    }
}
