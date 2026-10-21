package app.earwax;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A skin = pre-rendered 3-D artwork (PNG with alpha) + a JSON layout (button circles, LCD rectangles, dial, ears).
 * Bitmaps are decoded at the size they are actually drawn, so small phones do not pay for 1080-px art.
 */
final class Skin {
    static final class Btn { String id; float cx, cy, r; }
    static final class Dial { float cx, cy, r0, r1, a0, a1; }
    static final class Ear { Bitmap bmp; final RectF r = new RectF(); boolean left; }

    static final int INK = 0xff17381f, LED = 0xff9bffa8, DIM = 0xff3f8a52;
    static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG), T = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    private static final RectF R1 = new RectF();
    private static final int[] LUT = new int[64];
    static {
        T.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        float[] hsv = {0, 0.9f, 1f};
        for (int i = 0; i < 64; i++) { hsv[0] = 125 - 120 * (i / 63f); LUT[i] = Color.HSVToColor(hsv); }
    }

<<<<<<< HEAD
    /**
     * The three paints above are shared by the main screen and the floating popups, which draw one after the other on the
     * main thread. Every draw pass starts by putting them back to a clean state, so a shader, tint, alpha or glow left over
     * from one window can never leak into the next one (that showed up as lights / background colours changing).
     */
    static void resetPaints() {
        P.setShader(null); P.setColorFilter(null); P.setXfermode(null); P.setAlpha(255);
        Q.setShader(null); Q.setColorFilter(null); Q.setXfermode(null); Q.setAlpha(255); Q.setStyle(Paint.Style.FILL);
        T.setShader(null); T.setColorFilter(null); T.setXfermode(null); T.setAlpha(255); T.clearShadowLayer();
    }

    Bitmap bmp, cone; int w, h;          // cone = one speaker-cone sprite (the app pumps it, six times)
    float coneW;                         // its size in design units
=======
    Bitmap bmp; int w, h;
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
    final RectF full = new RectF();
    final ArrayList<Btn> btns = new ArrayList<>();
    final ArrayList<Ear> ears = new ArrayList<>();
    RectF lcd, info, spec, screen, panel;
    Btn knob; Dial dial;

    /** @param targetPx width in pixels the skin will be drawn at (0 = full size). */
    static Skin load(Context c, String n, int targetPx) {
        try {
            Skin s = new Skin();
            JSONObject j = new JSONObject(readAsset(c, "skins/" + n + ".json"));
            s.w = j.getInt("w"); s.h = j.getInt("h"); s.full.set(0, 0, s.w, s.h);
            float k = targetPx <= 0 ? 1f : Math.min(1f, targetPx / (float) s.w);
            s.bmp = decode(c, "skins/" + n + ".png", k);
            JSONArray a = j.optJSONArray("buttons");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i); Btn b = new Btn();
                b.id = o.getString("id"); b.cx = (float) o.getDouble("cx"); b.cy = (float) o.getDouble("cy"); b.r = (float) o.getDouble("r");
                s.btns.add(b); if (b.id.equals("knob")) s.knob = b;
            }
            s.lcd = rc(j, "lcd"); s.info = rc(j, "info"); s.spec = rc(j, "spec"); s.screen = rc(j, "screen"); s.panel = rc(j, "panel");
            JSONObject d = j.optJSONObject("dial");
            if (d != null) { Dial dl = new Dial(); dl.cx = (float) d.getDouble("cx"); dl.cy = (float) d.getDouble("cy"); dl.r0 = (float) d.getDouble("r0"); dl.r1 = (float) d.getDouble("r1"); dl.a0 = (float) d.getDouble("a0"); dl.a1 = (float) d.getDouble("a1"); s.dial = dl; }
            for (String en : new String[]{"ear_l", "ear_r"}) {
                JSONObject o = j.optJSONObject(en); if (o == null) continue;
                Ear e = new Ear(); e.left = en.equals("ear_l");
                e.r.set((float) o.getDouble("x"), (float) o.getDouble("y"), (float) (o.getDouble("x") + o.getDouble("w")), (float) (o.getDouble("y") + o.getDouble("h")));
                e.bmp = decode(c, "skins/" + en + ".png", k); s.ears.add(e);
            }
<<<<<<< HEAD
            JSONObject co = j.optJSONObject("cone");
            if (co != null) { s.coneW = (float) co.getDouble("w"); s.cone = decode(c, "skins/cone.png", k); }
=======
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
            return s;
        } catch (Exception e) { throw new RuntimeException("skin " + n, e); }
    }

<<<<<<< HEAD
    void release() { if (bmp != null) bmp.recycle(); if (cone != null) cone.recycle(); for (Ear e : ears) if (e.bmp != null) e.bmp.recycle(); bmp = null; cone = null; ears.clear(); }
=======
    void release() { if (bmp != null) bmp.recycle(); for (Ear e : ears) if (e.bmp != null) e.bmp.recycle(); bmp = null; ears.clear(); }
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10

    private static String readAsset(Context c, String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(path)))) { String l; while ((l = r.readLine()) != null) sb.append(l); }
        return sb.toString();
    }

    private static Bitmap decode(Context c, String path, float k) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        int ss = 1; while (ss * 2 <= 1f / k) ss *= 2;
        o.inSampleSize = ss;
        Bitmap b;
        try (InputStream in = c.getAssets().open(path)) { b = BitmapFactory.decodeStream(in, null, o); }
        float have = 1f / ss;
        if (k < 0.92f * have) {                                   // finish the last (non power of two) step
            Bitmap sc = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k / have)), Math.max(1, Math.round(b.getHeight() * k / have)), true);
            if (sc != b) b.recycle(); b = sc;
        }
        return b;
    }

    private static RectF rc(JSONObject j, String key) throws JSONException {
        JSONObject o = j.optJSONObject(key); if (o == null) return null;
        float x = (float) o.getDouble("x"), y = (float) o.getDouble("y");
        return new RectF(x, y, x + (float) o.getDouble("w"), y + (float) o.getDouble("h"));
    }

    Btn hit(float x, float y) {
        Btn best = null; float bd = 1e9f;
        for (Btn b : btns) { float d = (float) Math.hypot(x - b.cx, y - b.cy); if (d <= b.r + 8 && d < bd) { bd = d; best = b; } }
        return best;
    }

    Btn find(String id) { for (Btn b : btns) if (b.id.equals(id)) return b; return null; }

    // ---------------------------------------------------------------- text helpers
    static void text(Canvas c, String s, float x, float y, float size, int col, Paint.Align al) {
        T.setTextSize(size); T.setColor(col); T.setTextAlign(al);
        if (col == LED || col == 0xffffffff) T.setShadowLayer(size * 0.28f, 0, 0, (col & 0x00ffffff) | 0x99000000); else T.clearShadowLayer();   // soft LCD bloom
        c.drawText(s, x, y, T);
    }
    static float width(String s, float size) { T.setTextSize(size); return T.measureText(s); }
    static String fit(String s, float size, float max) {
        T.setTextSize(size); if (T.measureText(s) <= max) return s;
        int n = s.length(); while (n > 1 && T.measureText(s, 0, n) + T.measureText("..") > max) n--;
        return s.substring(0, n) + "..";
    }
    static String mmss(long ms) { long s = ms / 1000, m = s / 60; s %= 60; return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s; }
    static void marquee(Canvas c, String s, float x, float y, float size, int col, float room) {
        T.setTextSize(size); float tw = T.measureText(s);
        if (tw <= room) { text(c, s, x, y, size, col, Paint.Align.LEFT); return; }
        float off = -((System.currentTimeMillis() / 22) % (long) (tw + 90));
        text(c, s, x + off, y, size, col, Paint.Align.LEFT); text(c, s, x + off + tw + 90, y, size, col, Paint.Align.LEFT);
    }
    private static void clipRound(Canvas c, RectF r, float inset, float rad) {
        PATH.reset(); PATH.addRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, rad, rad, Path.Direction.CW); c.clipPath(PATH);
    }
    private static String status(Hud x) { return x.buffering ? "BUFFER" : x.live ? "LIVE" : x.playing ? "PLAY" : "PAUSE"; }

    // ---------------------------------------------------------------- popup drawing
    void hud(Canvas c, Hud x, Btn pressed, boolean rotating) {
        c.drawBitmap(bmp, null, full, P);
        if (dial != null) orbFace(c, x, rotating); else whaleFace(c, x);
        for (Btn b : btns) {
            if (b == knob) continue;
            boolean pr = b == pressed;
            if (pr) { Q.setStyle(Paint.Style.FILL); Q.setColor(0x55000000); c.drawCircle(b.cx, b.cy, b.r, Q); }
            glyph(c, b.id, b.cx, b.cy, pr ? b.r * 0.88f : b.r, x.playing, Color.WHITE);
            if (pr) { Q.setStyle(Paint.Style.STROKE); Q.setStrokeWidth(4); Q.setColor(0xccffffff); c.drawCircle(b.cx, b.cy, b.r - 2, Q); }
        }
        if (knob != null) knob(c, x, rotating);
    }

    private void whaleFace(Canvas c, Hud x) {
        c.save(); clipRound(c, lcd, 6, lcd.height() * 0.2f);
        float sz = lcd.height() * 0.46f, l = lcd.left + 26, r = lcd.right - 26;
        text(c, mmss(x.pos), l, lcd.centerY() + sz * 0.34f, sz, INK, Paint.Align.LEFT);
        text(c, x.tool + " " + x.toolText, r, lcd.top + 14 + lcd.height() * 0.17f, lcd.height() * 0.19f, INK, Paint.Align.RIGHT);
        text(c, status(x), r, lcd.bottom - 16, lcd.height() * 0.17f, INK, Paint.Align.RIGHT);
        c.restore();
        c.save(); clipRound(c, info, 5, info.height() * 0.4f);
        if (!x.live && x.dur > 0) { Q.setStyle(Paint.Style.FILL); Q.setColor(0x6640b060); c.drawRect(info.left, info.top, info.left + info.width() * Math.min(1f, x.pos / (float) x.dur), info.bottom, Q); }
        float is = info.height() * 0.56f;
        marquee(c, x.title, info.left + 18, info.centerY() + is * 0.34f, is, INK, info.width() - 36);
        c.restore();
        c.save(); clipRound(c, spec, 4, 14); bars(c, spec, x.bars); c.restore();
    }

    private void orbFace(Canvas c, Hud x, boolean rotating) {
        float cx = lcd.centerX(), cy = lcd.centerY(), r = lcd.width() / 2f;
        if (x.dur > 0 && !x.live) {                      // progress ring around the LCD
            R1.set(cx - r - 16, cy - r - 16, cx + r + 16, cy + r + 16);
            Q.setStyle(Paint.Style.STROKE); Q.setStrokeCap(Paint.Cap.ROUND); Q.setStrokeWidth(6);
            Q.setColor(0x44000000); c.drawArc(R1, 0, 360, false, Q);
            Q.setColor(0xff35e0a0); c.drawArc(R1, -90, 360f * Math.min(1f, x.pos / (float) x.dur), false, Q);
        }
        c.save(); PATH.reset(); PATH.addCircle(cx, cy, r - 7, Path.Direction.CW); c.clipPath(PATH);   // nothing may leave the glass
        text(c, status(x), cx, cy - 88, 17, DIM, Paint.Align.CENTER);
        text(c, x.tool + " " + x.toolText, cx, cy - 58, 22, INK, Paint.Align.CENTER);
        text(c, mmss(x.pos), cx, cy + 2, 54, INK, Paint.Align.CENTER);
        c.save(); c.clipRect(info); marquee(c, x.title, info.left, info.centerY() + 7, 21, INK, info.width()); c.restore();
        bars(c, spec, x.bars);
        c.restore();
        dial(c, x, rotating);
    }

    private void dial(Canvas c, Hud x, boolean rotating) {
        float rm = (dial.r0 + dial.r1) / 2f, w = (dial.r1 - dial.r0) * 0.46f, sweep = dial.a1 - dial.a0, start = dial.a0 - 90;
        R1.set(dial.cx - rm, dial.cy - rm, dial.cx + rm, dial.cy + rm);
        Q.setStyle(Paint.Style.STROKE); Q.setStrokeCap(Paint.Cap.ROUND);
        Q.setStrokeWidth(w); Q.setColor(0x44506878); c.drawArc(R1, start, sweep, false, Q);
        Q.setStrokeWidth(2); Q.setColor(0x99bfe8ff);
        for (int i = 0; i <= 27; i++) {
            double a = Math.toRadians(dial.a0 + sweep * i / 27f); float s = (float) Math.sin(a), co = (float) Math.cos(a), r0 = dial.r1 - 9, r1 = dial.r1 - 3;
            c.drawLine(dial.cx + s * r0, dial.cy - co * r0, dial.cx + s * r1, dial.cy - co * r1, Q);
        }
        float v = Math.max(0.004f, Math.min(1f, x.toolNorm));
        Q.setStrokeWidth(w + 9); Q.setColor(0x3344ffc0); c.drawArc(R1, start, sweep * v, false, Q);
        Q.setStrokeWidth(w); Q.setColor(rotating ? 0xff9bfff0 : 0xff35e0a0); c.drawArc(R1, start, sweep * v, false, Q);
        double a = Math.toRadians(dial.a0 + sweep * v);
        Q.setStyle(Paint.Style.FILL); Q.setColor(0xffffffff);
        c.drawCircle(dial.cx + (float) Math.sin(a) * rm, dial.cy - (float) Math.cos(a) * rm, w * (rotating ? 0.78f : 0.62f), Q);
        text(c, x.tool + " " + x.toolText, dial.cx, dial.cy + rm * 0.97f, 26, INK, Paint.Align.CENTER);
    }

    private void knob(Canvas c, Hud x, boolean rotating) {
        float r = knob.r;
        R1.set(knob.cx - r * 0.86f, knob.cy - r * 0.86f, knob.cx + r * 0.86f, knob.cy + r * 0.86f);
        Q.setStyle(Paint.Style.STROKE); Q.setStrokeCap(Paint.Cap.ROUND); Q.setStrokeWidth(r * 0.075f);
        Q.setColor(0x55ffffff); c.drawArc(R1, -225, 270, false, Q);
        Q.setColor(rotating ? 0xffffffff : 0xeeb8f0ff); c.drawArc(R1, -225, 270 * Math.max(0.004f, x.toolNorm), false, Q);
        double a = Math.toRadians(-135 + 270 * x.toolNorm); float s = (float) Math.sin(a), co = (float) Math.cos(a);
        Q.setStrokeWidth(r * 0.11f); Q.setColor(Color.WHITE);
        c.drawLine(knob.cx + s * r * 0.30f, knob.cy - co * r * 0.30f, knob.cx + s * r * 0.66f, knob.cy - co * r * 0.66f, Q);
        Q.setStyle(Paint.Style.FILL); c.drawCircle(knob.cx + s * r * 0.66f, knob.cy - co * r * 0.66f, r * 0.07f, Q);
    }

    static void bars(Canvas c, RectF r, float[] b) {
        float bw = (r.width() - 20) / b.length;
        Q.setStyle(Paint.Style.FILL);
        for (int i = 0; i < b.length; i++) {
            float v = Math.max(0f, Math.min(1f, b[i])), hh = v * (r.height() - 12);
            Q.setColor(LUT[(int) (v * 63)]);
            c.drawRect(r.left + 10 + i * bw + 1.5f, r.bottom - 6 - hh, r.left + 10 + (i + 1) * bw - 1.5f, r.bottom - 6, Q);
        }
    }

    // ---------------------------------------------------------------- button pictograms
    private static void tri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
        PATH.reset(); PATH.moveTo(x1, y1); PATH.lineTo(x2, y2); PATH.lineTo(x3, y3); PATH.close(); c.drawPath(PATH, Q);
    }

    static void heart(Canvas c, float cx, float cy, float k, boolean filled, int col) {
        PATH.reset(); PATH.moveTo(cx, cy + k * 0.95f);
        PATH.cubicTo(cx - k * 1.7f, cy - k * 0.1f, cx - k * 0.7f, cy - k * 1.5f, cx, cy - k * 0.45f);
        PATH.cubicTo(cx + k * 0.7f, cy - k * 1.5f, cx + k * 1.7f, cy - k * 0.1f, cx, cy + k * 0.95f); PATH.close();
        Q.setColor(col); Q.setStrokeWidth(k * 0.22f); Q.setStrokeJoin(Paint.Join.ROUND);
        Q.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE); c.drawPath(PATH, Q);
    }

    static void glyph(Canvas c, String id, float cx, float cy, float r, boolean playing, int col) {
        float k = r * 0.42f;
        Q.setColor(col); Q.setStyle(Paint.Style.FILL); Q.setStrokeCap(Paint.Cap.ROUND); Q.setStrokeWidth(r * 0.14f);
        switch (id) {
            case "prev": c.drawRect(cx - k, cy - k, cx - k * 0.55f, cy + k, Q); tri(c, cx + k, cy - k, cx + k, cy + k, cx - k * 0.55f, cy); break;
            case "next": c.drawRect(cx + k * 0.55f, cy - k, cx + k, cy + k, Q); tri(c, cx - k, cy - k, cx - k, cy + k, cx + k * 0.55f, cy); break;
            case "play":
                if (playing) { c.drawRect(cx - k, cy - k, cx - k * 0.2f, cy + k, Q); c.drawRect(cx + k * 0.2f, cy - k, cx + k, cy + k, Q); }
                else tri(c, cx - k * 0.7f, cy - k * 1.1f, cx - k * 0.7f, cy + k * 1.1f, cx + k * 1.1f, cy);
                break;
            case "close": c.drawLine(cx - k, cy - k, cx + k, cy + k, Q); c.drawLine(cx + k, cy - k, cx - k, cy + k, Q); break;
            case "tool": for (int i = -1; i <= 1; i++) { c.drawLine(cx - k, cy + i * k * 0.8f, cx + k, cy + i * k * 0.8f, Q); c.drawCircle(cx + i * k * 0.5f, cy + i * k * 0.8f, k * 0.28f, Q); } break;
            case "mode":
                Q.setStyle(Paint.Style.STROKE); c.drawArc(cx - k, cy - k, cx + k, cy + k, 40, 280, false, Q); Q.setStyle(Paint.Style.FILL);
                tri(c, cx + k * 0.2f, cy - k * 1.45f, cx + k * 0.2f, cy - k * 0.55f, cx + k * 1.1f, cy - k); break;
            case "app": Q.setStyle(Paint.Style.STROKE); c.drawRect(cx - k, cy - k * 0.8f, cx + k * 0.6f, cy + k, Q); c.drawLine(cx, cy, cx + k, cy - k, Q); Q.setStyle(Paint.Style.FILL); break;
            default: {
                String t = id.equals("list") ? "LST" : id.equals("eq") ? "EQ" : id.equals("fx") ? "FX" : id.equals("shuffle") ? "S" : id.equals("repeat") ? "R" : id.equals("sleep") ? "Z" : id.equals("popup") ? "P" : "";
                text(c, t, cx, cy + r * 0.28f, r * (t.length() > 1 ? 0.62f : 0.9f), col, Paint.Align.CENTER);
            }
        }
    }
}
