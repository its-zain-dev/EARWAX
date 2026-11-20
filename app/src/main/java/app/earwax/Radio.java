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
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Upgraded Internet Radio Engine:
 *  - Resilient multi-mirror radio-browser.info directory (no API key needed)
 *  - Genre quick-discovery (TOP, LO-FI, SYNTHWAVE, ELECTRONIC, JAZZ, ROCK, AMBIENT, CHILL)
 *  - Smart playlist link resolver (.m3u, .m3u8, .pls, .xspf)
 *  - Curated built-in instant stations so Radio works immediately even if the directory is slow
 */
final class Radio {
    interface Callback { void done(List<Track> list, String error); }

    static final String[] GENRES = {"TOP", "LO-FI", "SYNTH", "ELECTRO", "JAZZ", "ROCK", "AMBIENT", "CHILL"};
    private static final String[] GENRE_TAGS = {"", "lofi", "synthwave", "electronic", "jazz", "rock", "ambient", "chillout"};
    private static final String[] HOSTS = {"de1", "nl1", "at1", "fi1", "fr1"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static ArrayList<Track> curated() {
        ArrayList<Track> out = new ArrayList<>();
        out.add(Track.stream("Nightride FM", "US • SYNTHWAVE 160k", "https://stream.nightride.fm/nightride.mp3", "MP3", 160, "US"));
        out.add(Track.stream("SomaFM Groove Salad", "US • AMBIENT CHILL 128k", "https://ice2.somafm.com/groovesalad-128-aac", "AAC", 128, "US"));
        out.add(Track.stream("SomaFM DEF CON Radio", "US • CYBER ELECTRONIC 128k", "https://ice2.somafm.com/defcon-128-aac", "AAC", 128, "US"));
        out.add(Track.stream("SomaFM Drone Zone", "US • DEEP SPACE AMBIENT 128k", "https://ice2.somafm.com/dronezone-128-aac", "AAC", 128, "US"));
        out.add(Track.stream("SomaFM Vaporwaves", "US • VAPORWAVE 128k", "https://ice2.somafm.com/vaporwaves-128-aac", "AAC", 128, "US"));
        out.add(Track.stream("Radio Paradise Main", "US • ECLECTIC HI-FI 320k", "https://stream.radioparadise.com/aac-320", "AAC", 320, "US"));
        out.add(Track.stream("FIP Radio France", "FR • JAZZ / WORLD 192k", "https://icecast.radiofrance.fr/fip-hifi.aac", "AAC", 192, "FR"));
        out.add(Track.stream("SomaFM Secret Agent", "US • DOWNTEMPO LOUNGE 128k", "https://ice2.somafm.com/secretagent-128-aac", "AAC", 128, "US"));
        return out;
    }

    static void top(Callback cb) {
        query("/json/stations/topclick/45?hidebroken=true", cb);
    }

    static void genre(int genreIdx, Callback cb) {
        if (genreIdx <= 0 || genreIdx >= GENRE_TAGS.length) {
            top(cb);
            return;
        }
        String tag = GENRE_TAGS[genreIdx];
        query("/json/stations/bytag/" + Uri.encode(tag) + "?limit=45&hidebroken=true&order=clickcount&reverse=true", cb);
    }

    static void search(String q, Callback cb) {
        query("/json/stations/search?name=" + Uri.encode(q) + "&limit=45&hidebroken=true&order=clickcount&reverse=true", cb);
    }

    private static void query(String path, Callback cb) {
        new Thread(() -> {
            String err = "OFFLINE";
            for (String h : HOSTS) {
                try {
                    String body = get("https://" + h + ".api.radio-browser.info" + path, 150_000);
                    JSONArray a = new JSONArray(body);
                    ArrayList<Track> out = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        String u = o.optString("url_resolved", "");
                        if (u.isEmpty()) u = o.optString("url", "");
                        if (u.isEmpty()) continue;
                        String cc = o.optString("countrycode", "").trim();
                        String codec = o.optString("codec", "").trim().toUpperCase(Locale.ROOT);
                        int br = o.optInt("bitrate", 0);
                        StringBuilder sub = new StringBuilder();
                        if (!cc.isEmpty()) sub.append(cc);
                        if (!codec.isEmpty()) { if (sub.length() > 0) sub.append(" • "); sub.append(codec); }
                        if (br > 0) { if (sub.length() > 0) sub.append(" "); sub.append(br).append("k"); }
                        out.add(Track.stream(o.optString("name", "Radio").trim(), sub.toString(), u, codec, br, cc));
                    }
                    post(cb, out, out.isEmpty() ? "NO RESULTS" : null);
                    return;
                } catch (Exception e) {
                    err = "OFFLINE — SHOWING CURATED";
                }
            }
            post(cb, curated(), err);
        }, "earwax-radio").start();
    }

    /** Resolves .m3u / .pls / .xspf playlist URLs to the first direct stream link. */
    static void resolve(String url, Callback cb) {
        String l = url.toLowerCase(Locale.ROOT);
        if (!(l.endsWith(".m3u") || l.endsWith(".pls") || l.endsWith(".xspf"))) {
            ArrayList<Track> one = new ArrayList<>();
            one.add(Track.stream(host(url), "DIRECT STREAM", url, "STREAM", 0, ""));
            post(cb, one, null);
            return;
        }
        new Thread(() -> {
            try {
                String text = get(url, 24_000);
                for (String line : text.split("\\r?\\n")) {
                    String s = line.trim();
                    if (s.regionMatches(true, 0, "file1=", 0, 6)) s = s.substring(6).trim();
                    int loc = s.toLowerCase(Locale.ROOT).indexOf("<location>");
                    if (loc >= 0) {
                        int end = s.toLowerCase(Locale.ROOT).indexOf("</location>", loc);
                        if (end > loc) s = s.substring(loc + 10, end).trim();
                    }
                    if (s.startsWith("http://") || s.startsWith("https://")) {
                        ArrayList<Track> one = new ArrayList<>();
                        one.add(Track.stream(host(url), "RESOLVED STREAM", s, "STREAM", 0, ""));
                        post(cb, one, null);
                        return;
                    }
                }
            } catch (Exception ignored) { }
            post(cb, new ArrayList<>(), "BAD LINK");
        }, "earwax-resolve").start();
    }

    static String host(String url) {
        String h = Uri.parse(url).getHost();
        return h == null ? "STREAM" : h;
    }

    private static void post(Callback cb, List<Track> l, String e) {
        MAIN.post(() -> cb.done(l, e));
    }

    private static String get(String u, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(6500);
        c.setReadTimeout(7500);
        c.setRequestProperty("User-Agent", "EARWAX/4.0 (Android)");
        if (c.getResponseCode() >= 400) {
            c.disconnect();
            throw new java.io.IOException("HTTP " + c.getResponseCode());
        }
        try (InputStream in = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < maxBytes) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
