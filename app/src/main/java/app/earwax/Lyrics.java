package app.earwax;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * EARWAX 4.0 Live Synchronized Lyrics Engine (powered by open-source LRCLIB — zero API keys).
 * Supports both time-synced [mm:ss.xx] LRC lines (auto-scroll + tap-to-seek) and plain lyrics.
 */
final class Lyrics {
    static final class Line {
        final long timeMs; // -1 if unsynced plain lyric
        final String text;
        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    static final class Sheet {
        final String trackKey;
        final boolean synced;
        final List<Line> lines;
        Sheet(String trackKey, boolean synced, List<Line> lines) {
            this.trackKey = trackKey;
            this.synced = synced;
            this.lines = lines;
        }

        int activeIndex(long posMs) {
            if (!synced || lines.isEmpty()) return -1;
            int lo = 0, hi = lines.size() - 1, ans = 0;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (lines.get(mid).timeMs <= posMs + 180L) {
                    ans = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return ans;
        }
    }

    interface Callback { void done(Sheet sheet, String error); }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Sheet> CACHE = Collections.synchronizedMap(new HashMap<>());
    private static final Pattern LRC_PAT = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\](.*)");

    static Sheet cached(Track t) {
        return t == null ? null : CACHE.get(t.key());
    }

    static void fetch(Track t, Callback cb) {
        if (t == null || t.isRadio()) {
            MAIN.post(() -> cb.done(null, "LYRICS UNAVAILABLE FOR LIVE RADIO"));
            return;
        }
        Sheet hit = CACHE.get(t.key());
        if (hit != null) {
            MAIN.post(() -> cb.done(hit, null));
            return;
        }
        final String key = t.key();
        final String cleanTitle = cleanQuery(t.title);
        final String cleanArtist = cleanQuery(t.artist);

        POOL.execute(() -> {
            try {
                // 1. Try exact match first
                String q = (cleanArtist.isEmpty() ? cleanTitle : (cleanTitle + " " + cleanArtist)).trim();
                String raw = get("https://lrclib.net/api/search?q=" + Uri.encode(q));
                JSONArray arr = new JSONArray(raw);
                JSONObject best = null;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String synced = o.optString("syncedLyrics", "");
                    if (!synced.isEmpty() && !"null".equals(synced)) {
                        best = o;
                        break;
                    }
                    if (best == null) {
                        String plain = o.optString("plainLyrics", "");
                        if (!plain.isEmpty() && !"null".equals(plain)) best = o;
                    }
                }
                if (best == null) {
                    MAIN.post(() -> cb.done(null, "NO LYRICS FOUND"));
                    return;
                }
                String syncedRaw = best.optString("syncedLyrics", "");
                Sheet sheet;
                if (!syncedRaw.isEmpty() && !"null".equals(syncedRaw)) {
                    List<Line> lines = parseLrc(syncedRaw);
                    sheet = new Sheet(key, !lines.isEmpty(), lines);
                } else {
                    String plainRaw = best.optString("plainLyrics", "");
                    List<Line> lines = parsePlain(plainRaw);
                    sheet = new Sheet(key, false, lines);
                }
                if (sheet.lines.isEmpty()) {
                    MAIN.post(() -> cb.done(null, "NO LYRICS FOUND"));
                    return;
                }
                CACHE.put(key, sheet);
                MAIN.post(() -> cb.done(sheet, null));
            } catch (Exception e) {
                MAIN.post(() -> cb.done(null, "LYRICS OFFLINE"));
            }
        });
    }

    private static String cleanQuery(String s) {
        if (s == null) return "";
        // Strip common YouTube clutter like (Official Video), [Lyrics], (feat. ...)
        s = s.replaceAll("\\([^)]*(official|video|audio|lyric|hq|hd|remaster|feat|ft\\.)[^)]*\\)", " ");
        s = s.replaceAll("\\[[^\\]]*(official|video|audio|lyric|hq|hd)[^\\]]*\\]", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static List<Line> parseLrc(String raw) {
        ArrayList<Line> out = new ArrayList<>();
        for (String row : raw.split("\\r?\\n")) {
            Matcher m = LRC_PAT.matcher(row.trim());
            if (m.find()) {
                long min = Long.parseLong(m.group(1));
                long sec = Long.parseLong(m.group(2));
                String frac = m.group(3);
                long ms = 0;
                if (frac != null) {
                    if (frac.length() == 1) ms = Long.parseLong(frac) * 100L;
                    else if (frac.length() == 2) ms = Long.parseLong(frac) * 10L;
                    else ms = Long.parseLong(frac.substring(0, 3));
                }
                String txt = m.group(4).trim();
                if (!txt.isEmpty()) {
                    out.add(new Line((min * 60L + sec) * 1000L + ms, txt));
                }
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out;
    }

    private static List<Line> parsePlain(String raw) {
        ArrayList<Line> out = new ArrayList<>();
        for (String row : raw.split("\\r?\\n")) {
            String s = row.trim();
            if (!s.isEmpty()) out.add(new Line(-1L, s));
        }
        return out;
    }

    private static String get(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(6500);
        c.setReadTimeout(7500);
        c.setRequestProperty("User-Agent", "EARWAX/4.0 (Android)");
        try (InputStream in = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder(8192);
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 200_000) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
