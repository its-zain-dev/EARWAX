package app.earwax;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * A skin = pre-rendered 3-D artwork (PNG with alpha) + a JSON layout (button circles, LCD rectangles).
 * The same drawing code serves the popup windows and the notification, so every shape behaves alike.
 */
final class Skin {
    static final class Btn { String id; float cx, cy, r; }

    static final int INK = 0xff17381f, LED = 0xff9bffa8, DIM = 0xff3f8a52;
    static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG), T = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path();
    static { T.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)); }

    Bitmap bmp; int w, h;
    final ArrayList<Btn> btns = new ArrayList<>();
    RectF lcd, info, spec, screen, panel;
    Btn knob;

    static Skin load(Context c, String n) {
        try {
            Skin s = new Skin();
            try (InputStream in = c.getAssets().open("skins/" + n + ".png")) { s.bmp = BitmapFactory.decodeStream(in); }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("skins/" + n + ".json")))) {
                String l; while ((l = r.readLine()) != null) sb.append(l);
            }
            JSONObject j = new JSONObject(sb.toString());
            s.w = j.getInt("w"); s.h = j.getInt("h");
            JSONArray a = j.optJSONArray("buttons");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i); Btn b = new Btn();
                b.id = o.getString("id"); b.cx = (float) o.getDouble("cx"); b.cy = (float) o.getDouble("cy"); b.r = (float) o.getDouble("r");
                s.btns.add(b); if (b.id.equals("knob")) s.knob = b;
            }
            s.lcd = rc(j, "lcd"); s.info = rc(j, "info"); s.spec = rc(j, "spec"); s.screen = rc(j, "screen"); s.panel = rc(j, "panel");
            return s;
        } catch (Exception e) { throw new RuntimeException("skin " + n, e); }
    }

    private static RectF rc(JSONObject j, String k) throws JSONException {
        JSONObject o = j.optJSONObject(k); if (o == null) return null;
        float x = (float) o.getDouble("x"), y = (float) o.getDouble("y");
        return new RectF(x, y, x + (float) o.getDouble("w"), y + (float) o.getDouble("h"));
    }

    Btn hit(float x, float y) {
        Btn best = null; float bd = 1e9f;
        for (Btn b : btns) { float d = (float) Math.hypot(x - b.cx, y - b.cy); if (d <= b.r + 8 && d < bd) { bd = d; best = b; } }
        return best;
    }

    // ---------------------------------------------------------------- text helpers
    static void text(Canvas c, String s, float x, float y, float size, int col, Paint.Align al) {
        T.setTextSize(size); T.setColor(col); T.setTextAlign(al);
        if (col == LED || col == 0xffffffff) T.setShadowLayer(size * 0.28f, 0, 0, (col & 0x00ffffff) | 0x99000000); else T.clearShadowLayer();   // soft LCD bloom
        c.drawText(s, x, y, T);
    }
    static String fit(String s, float size, float max) {
        T.setTextSize(size); if (T.measureText(s) <= max) return s;
        int n = s.length(); while (n > 1 && T.measureText(s, 0, n) + T.measureText("..") > max) n--;
        return s.substring(0, n) + "..";
    }
    static String mmss(long ms) { long s = ms / 1000; return String.format(Locale.US, "%02d:%02d", s / 60, s % 60); }
    static void marquee(Canvas c, String s, float x, float y, float size, int col, float room) {
        T.setTextSize(size); float tw = T.measureText(s);
        if (tw <= room) { text(c, s, x, y, size, col, Paint.Align.LEFT); return; }
        float off = -((System.currentTimeMillis() / 22) % (long) (tw + 90));
        text(c, s, x + off, y, size, col, Paint.Align.LEFT); text(c, s, x + off + tw + 90, y, size, col, Paint.Align.LEFT);
    }

    // ---------------------------------------------------------------- HUD drawing (popups + notification)
    void hud(Canvas c, Hud x, boolean strip) {
        c.drawBitmap(bmp, 0, 0, P);
        if (lcd != null) {
            boolean round = lcd.height() > lcd.width() * 0.7f;
            if (strip) {
                float sz = lcd.height() * 0.34f;
                text(c, fit(x.title.isEmpty() ? "EARWAX" : x.title, sz, lcd.width() - 40), lcd.left + 20, lcd.centerY() + sz * 0.35f, sz, INK, Paint.Align.LEFT);
            } else if (round) {
                float ts = lcd.height() * 0.24f;
                text(c, x.playing ? "PLAY" : "PAUSE", lcd.centerX(), lcd.centerY() - ts * 0.8f, ts * 0.32f, INK, Paint.Align.CENTER);
                text(c, mmss(x.pos), lcd.centerX(), lcd.centerY() + ts * 0.25f, ts, INK, Paint.Align.CENTER);
                text(c, x.tool + " " + x.toolText, lcd.centerX(), lcd.centerY() + ts * 0.8f, ts * 0.34f, INK, Paint.Align.CENTER);
            } else {
                float ts = lcd.height() * 0.5f;
                text(c, mmss(x.pos), lcd.left + 20, lcd.centerY() + ts * 0.35f, ts, INK, Paint.Align.LEFT);
                text(c, x.tool + " " + x.toolText, lcd.right - 16, lcd.top + lcd.height() * 0.34f, lcd.height() * 0.2f, INK, Paint.Align.RIGHT);
                text(c, x.playing ? "> PLAY" : "|| PAUSE", lcd.right - 16, lcd.bottom - lcd.height() * 0.16f, lcd.height() * 0.2f, INK, Paint.Align.RIGHT);
            }
        }
        if (info != null) {
            c.save(); c.clipRect(info);
            if (!strip && x.dur > 0) {
                Q.setStyle(Paint.Style.FILL); Q.setColor(0x6640b060);
                c.drawRect(info.left, info.top, info.left + info.width() * Math.min(1f, x.pos / (float) x.dur), info.bottom, Q);
            }
            float sz = info.height() * 0.6f, by = info.centerY() + sz * 0.35f;
            if (strip) text(c, fit(x.artist.isEmpty() ? (x.playing ? "PLAYING" : "PAUSED") : x.artist, sz, info.width() - 24), info.left + 12, by, sz, INK, Paint.Align.LEFT);
            else marquee(c, x.title, info.left + 12, by, sz, INK, info.width() - 24);
            c.restore();
        }
        if (spec != null && !strip) bars(c, spec, x.bars);
        for (Btn b : btns) {
            if (b == knob) continue;
            glyph(c, b.id, b.cx, b.cy, b.r, x.playing, Color.WHITE);
        }
        if (knob != null) {   // value indicator on the big blue knob
            double a = Math.toRadians(-135 + 270 * x.toolNorm);
            Q.setStyle(Paint.Style.STROKE); Q.setStrokeCap(Paint.Cap.ROUND); Q.setStrokeWidth(knob.r * 0.12f); Q.setColor(Color.WHITE);
            c.drawLine(knob.cx + (float) Math.sin(a) * knob.r * 0.28f, knob.cy - (float) Math.cos(a) * knob.r * 0.28f,
                    knob.cx + (float) Math.sin(a) * knob.r * 0.78f, knob.cy - (float) Math.cos(a) * knob.r * 0.78f, Q);
            Q.setStyle(Paint.Style.FILL);
        }
    }

    static void bars(Canvas c, RectF r, float[] b) {
        float bw = (r.width() - 20) / b.length;
        Q.setStyle(Paint.Style.FILL);
        for (int i = 0; i < b.length; i++) {
            float hh = b[i] * (r.height() - 12);
            Q.setColor(Color.HSVToColor(new float[]{125 - 120 * b[i], 0.9f, 1f}));
            c.drawRect(r.left + 10 + i * bw + 1.5f, r.bottom - 6 - hh, r.left + 10 + (i + 1) * bw - 1.5f, r.bottom - 6, Q);
        }
    }

    // ---------------------------------------------------------------- button pictograms
    private static void tri(Canvas c, float x1, float y1, float x2, float y2, float x3, float y3) {
        PATH.reset(); PATH.moveTo(x1, y1); PATH.lineTo(x2, y2); PATH.lineTo(x3, y3); PATH.close(); c.drawPath(PATH, Q);
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
