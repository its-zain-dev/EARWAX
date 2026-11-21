package app.earwax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EARWAX 4.0 Memory-Disciplined LRU Album Art Engine (Local + Remote YouTube Music + Disk Cache).
 *  - Loads local MediaStore album artwork AND remote YouTube Music cover thumbnails seamlessly
 *  - Disk-caches YouTube Music thumbnails in cacheDir/yt_art for instant offline display
 *  - Low-RAM trimCache() frees memory when app is hidden so weak devices never kill audio!
 */
final class Art {
    private static final int HI_N = 384;
    private static final int THUMB_N = 128;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> IN_FLIGHT = Collections.synchronizedSet(new HashSet<>());
    private static Bitmap fallbackMark;

    // Modest 40-item LRU cache (~1.5 MB max!) so 1.8 GB RAM devices stay super lean
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(40) {
        @Override protected int sizeOf(String k, Bitmap v) { return 1; }
    };

    static final class Loaded {
        final Bitmap raw;
        final Bitmap framed;
        final int accent;
        Loaded(Bitmap raw, Bitmap framed, int accent) {
            this.raw = raw;
            this.framed = framed;
            this.accent = accent;
        }
    }

    /** Frees in-memory thumbnail cache when UI goes to background / low-RAM trim. */
    static void trimCache() {
        THUMBS.evictAll();
    }

    /** Blocking hi-res loader (call off main thread). Supports local AND YouTube Music artwork! */
    static Loaded loadFull(Context c, Track t) {
        Bitmap src = decodeRaw(c, t, HI_N, true);
        if (src == null) src = mark(c);
        int accent = extractAccent(src);
        Bitmap framed = frame(src, accent);
        return new Loaded(src, framed, accent);
    }

    /** Non-blocking thumbnail lookup for library & YouTube Music list rows. */
    static Bitmap thumb(Context c, Track t, Runnable onReady) {
        if (t == null) return null;
        String key;
        if (t.isLocal() && t.albumId > 0) {
            key = "a:" + t.albumId;
        } else if (!t.ytId.isEmpty() || !t.thumbUrl.isEmpty()) {
            key = "y:" + (t.ytId.isEmpty() ? Integer.toHexString(t.thumbUrl.hashCode()) : t.ytId);
        } else {
            return null;
        }

        Bitmap hit = THUMBS.get(key);
        if (hit != null && !hit.isRecycled()) return hit;
        if (!IN_FLIGHT.add(key)) return null;

        final Context app = c.getApplicationContext();
        POOL.execute(() -> {
            Bitmap b = null;
            try {
                b = decodeRaw(app, t, THUMB_N, false);
                if (b != null) THUMBS.put(key, b);
            } catch (Exception ignored) {
            } finally {
                IN_FLIGHT.remove(key);
            }
            if (b != null && onReady != null) MAIN.post(onReady);
        });
        return null;
    }

    private static Bitmap decodeRaw(Context c, Track t, int size, boolean hiRes) {
        if (t == null) return null;
        try {
            if (t.isLocal()) {
                if (Build.VERSION.SDK_INT >= 29) {
                    return c.getContentResolver().loadThumbnail(t.uri(), new Size(size, size), null);
                } else if (t.albumId > 0) {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    try (InputStream in = c.getContentResolver().openInputStream(t.artUri())) {
                        Bitmap raw = BitmapFactory.decodeStream(in, null, o);
                        return scaleDown(raw, size);
                    }
                }
            } else if (!t.ytId.isEmpty() || !t.thumbUrl.isEmpty()) {
                return loadRemoteArt(c, t, size, hiRes);
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static Bitmap loadRemoteArt(Context c, Track t, int size, boolean hiRes) {
        String id = !t.ytId.isEmpty() ? t.ytId.replaceAll("[^A-Za-z0-9_-]", "") : Integer.toHexString(t.thumbUrl.hashCode());
        File dir = new File(c.getCacheDir(), "yt_art");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, id + (hiRes ? "_hi.jpg" : "_lo.jpg"));

        // Check disk cache first
        if (f.exists() && f.length() > 512) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = hiRes ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            Bitmap cached = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (cached != null) return scaleDown(cached, size);
        }

        String urlStr = hiRes ? t.hiResThumbUrl() : t.smallThumbUrl();
        if (urlStr == null || urlStr.isEmpty()) return null;

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5500);
            conn.setReadTimeout(6500);
            conn.setRequestProperty("User-Agent", YtMusic.UA_WEB);
            try (InputStream in = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && bos.size() < 450_000) bos.write(buf, 0, n);
                byte[] data = bos.toByteArray();
                if (data.length < 256) return null;
                try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(data); } catch (Exception ignored) { }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = hiRes ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                return scaleDown(raw, size);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap scaleDown(Bitmap raw, int size) {
        if (raw == null) return null;
        if (raw.getWidth() > size * 1.6f || raw.getHeight() > size * 1.6f) {
            float k = Math.min(size / (float) raw.getWidth(), size / (float) raw.getHeight());
            int nw = Math.max(1, Math.round(raw.getWidth() * k));
            int nh = Math.max(1, Math.round(raw.getHeight() * k));
            Bitmap sc = Bitmap.createScaledBitmap(raw, nw, nh, true);
            if (sc != raw) raw.recycle();
            return sc;
        }
        return raw;
    }

    static synchronized Bitmap mark(Context c) {
        if (fallbackMark != null && !fallbackMark.isRecycled()) return fallbackMark;
        try (InputStream in = c.getAssets().open("skins/mark.png")) {
            fallbackMark = BitmapFactory.decodeStream(in);
        } catch (Exception ignored) { }
        return fallbackMark;
    }

    static int extractAccent(Bitmap b) {
        if (b == null || b.isRecycled()) return Theme.primary();
        int w = b.getWidth(), h = b.getHeight();
        int stepX = Math.max(1, w / 16), stepY = Math.max(1, h / 16);
        float bestScore = -1f;
        int bestCol = Theme.primary();
        float[] hsv = new float[3];
        for (int y = stepY; y < h - stepY; y += stepY) {
            for (int x = stepX; x < w - stepX; x += stepX) {
                int px = b.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                float score = hsv[1] * (0.4f + 0.6f * hsv[2]);
                if (hsv[2] > 0.25f && hsv[1] > 0.28f && score > bestScore) {
                    bestScore = score;
                    hsv[1] = Math.min(1f, Math.max(0.55f, hsv[1]));
                    hsv[2] = Math.min(1f, Math.max(0.75f, hsv[2]));
                    bestCol = Color.HSVToColor(hsv);
                }
            }
        }
        return bestCol;
    }

    /** Frames album artwork inside a sculpted Early-2000s Chrome & Aqua beveled tile for Media Notification. */
    static Bitmap frame(Bitmap src, int accent) {
        Bitmap out = Bitmap.createBitmap(HI_N, HI_N, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF all = new RectF(0, 0, HI_N, HI_N);
        RectF mid = new RectF(10, 10, HI_N - 10, HI_N - 10);
        RectF in  = new RectF(15, 15, HI_N - 15, HI_N - 15);

        p.setShader(new LinearGradient(0, 0, HI_N, HI_N,
                new int[]{0xffeef7ff, 0xff7f99b8, 0xffd5e6fa, 0xff3b4d68},
                new float[]{0f, 0.35f, 0.68f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(all, 48, 48, p);

        p.setShader(new LinearGradient(0, 0, HI_N, HI_N, accent, Theme.secondary(), Shader.TileMode.CLAMP));
        c.drawRoundRect(mid, 38, 38, p);

        p.setShader(null);
        p.setColor(0xff080f1c);
        c.drawRoundRect(in, 32, 32, p);

        if (src != null && !src.isRecycled()) {
            Path clip = new Path();
            clip.addRoundRect(in, 32, 32, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            float k = Math.max(in.width() / src.getWidth(), in.height() / src.getHeight());
            float w = src.getWidth() * k, h = src.getHeight() * k;
            c.drawBitmap(src, null,
                    new RectF(in.centerX() - w / 2, in.centerY() - h / 2, in.centerX() + w / 2, in.centerY() + h / 2), p);
            c.restore();
        }

        p.setShader(new LinearGradient(0, 15, HI_N * 0.4f, HI_N * 0.55f, 0x38ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
        c.drawRoundRect(in, 32, 32, p);
        return out;
    }
}
