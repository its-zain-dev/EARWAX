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
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Internet radio: station search through the free community directory radio-browser.info (no key, no account).
 * Everything runs on a worker thread and answers on the main thread.
 */
final class Radio {
    interface Callback { void done(List<Track> list, String error); }
    private static final String[] HOSTS = {"de1", "nl1", "at1"};
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void top(Callback cb) { query("/json/stations/topclick/40?hidebroken=true", cb); }
    static void search(String q, Callback cb) {
        query("/json/stations/search?name=" + Uri.encode(q) + "&limit=40&hidebroken=true&order=clickcount&reverse=true", cb);
    }

    private static void query(String path, Callback cb) {
        new Thread(() -> {
            String err = "OFFLINE";
            for (String h : HOSTS) {
                try {
                    String body = get("https://" + h + ".api.radio-browser.info" + path, 120_000);
                    JSONArray a = new JSONArray(body); ArrayList<Track> out = new ArrayList<>();
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i); String u = o.optString("url_resolved", "");
                        if (u.isEmpty()) continue;
                        String sub = (o.optString("countrycode", "") + " " + o.optString("codec", "") + " " + o.optInt("bitrate", 0) + "k").trim();
                        out.add(Track.stream(o.optString("name", "?").trim(), sub, u));
                    }
                    post(cb, out, out.isEmpty() ? "NO RESULTS" : null); return;
                } catch (Exception e) { err = "OFFLINE"; }
            }
            post(cb, new ArrayList<>(), err);
        }, "radio-search").start();
    }

    /** .m3u / .pls links are playlists, not streams: pull the first real address out of them. */
    static void resolve(String url, Callback cb) {
        String l = url.toLowerCase();
        if (!(l.endsWith(".m3u") || l.endsWith(".pls"))) { ArrayList<Track> one = new ArrayList<>(); one.add(Track.stream(host(url), "", url)); post(cb, one, null); return; }
        new Thread(() -> {
            try {
                for (String line : get(url, 16_000).split("\\r?\\n")) {
                    String s = line.trim(); if (s.regionMatches(true, 0, "file1=", 0, 6)) s = s.substring(6).trim();
                    if (s.startsWith("http://") || s.startsWith("https://")) { ArrayList<Track> one = new ArrayList<>(); one.add(Track.stream(host(url), "", s)); post(cb, one, null); return; }
                }
            } catch (Exception ignored) { }
            post(cb, new ArrayList<>(), "BAD LINK");
        }, "radio-resolve").start();
    }

    static String host(String url) { String h = Uri.parse(url).getHost(); return h == null ? "STREAM" : h; }

    private static void post(Callback cb, List<Track> l, String e) { MAIN.post(() -> cb.done(l, e)); }

    private static String get(String u, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setRequestProperty("User-Agent", "EARWAX/2.0");
        try (InputStream in = c.getInputStream(); BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder(); char[] buf = new char[4096]; int n;
            while ((n = r.read(buf)) > 0 && sb.length() < maxBytes) sb.append(buf, 0, n);
            return sb.toString();
        } finally { c.disconnect(); }
    }
}
