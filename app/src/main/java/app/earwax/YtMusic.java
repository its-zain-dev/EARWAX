package app.earwax;

import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * EARWAX 4.0 Open-Source YouTube Music InnerTube Engine (SimpMusic / InnerTune-style, ZERO API keys!).
 *
 * Features:
 *  - Live Search (Songs + Videos/All) on music.youtube.com/youtubei/v1/search (WEB_REMIX client)
 *  - Instant Mood / Chart Discovery (Trending, Chill, Bass/Phonk, Synthwave, Pop, Hip-Hop, Rock, Arabic)
 *  - Smart Auto-Radio / Up Next Queue (music.youtube.com/youtubei/v1/next RDAMVM<videoId>)
 *  - Multi-Tier Direct Audio Stream Resolver with 3-hour cache & pre-fetching:
 *      Tier 1: InnerTube IOS /player (direct high-bitrate AAC/M4A & Opus streams)
 *      Tier 2: InnerTube IOS HLS audio manifest fallback
 *      Tier 3: InnerTube ANDROID_VR /player with live visitorData
 *      Tier 4: Open-source public Piped API mirrors fallback
 */
final class YtMusic {
    interface ListCallback { void done(List<Track> list, String error); }

    static final class ResolvedStream {
        final String url;
        final String userAgent;
        final String codec;
        final int bitrateKbps;
        final long expiresAtMs;

        ResolvedStream(String url, String userAgent, String codec, int bitrateKbps, long expiresAtMs) {
            this.url = url;
            this.userAgent = userAgent;
            this.codec = codec;
            this.bitrateKbps = bitrateKbps;
            this.expiresAtMs = expiresAtMs;
        }

        boolean isValid() {
            return url != null && !url.isEmpty() && System.currentTimeMillis() < expiresAtMs;
        }
    }

    static final String[] MOODS = {"TRENDING", "CHILL", "BASS", "SYNTH", "POP", "HIP-HOP", "ROCK", "ARABIC"};
    private static final String[] MOOD_QUERIES = {
            "top hits music 2025 2026 official audio",
            "lofi chill beats study relax music",
            "phonk bass boosted electronic house",
            "synthwave retrowave night drive outrun",
            "popular pop hits global chart song",
            "hip hop rap top hits instrumental",
            "classic rock alternative top songs",
            "arabic top songs hits tarab modern"
    };

    static final String UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";
    static final String UA_IOS = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
    static final String UA_VR  = "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

    private static final String[] PIPED_MIRRORS = {
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee"
    };

    private static final String[] INVIDIOUS_MIRRORS = {
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yt.artemislena.eu"
    };

    private static final String FILTER_SONGS = "EgWKAQIIAWoMEAMQBBAJEAoQBRAV";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService SEARCH_POOL = Executors.newFixedThreadPool(2);
    private static final ExecutorService STREAM_POOL = Executors.newFixedThreadPool(3);
    private static final Pattern DUR_PAT = Pattern.compile("^(\\d{1,2}:)?\\d{1,2}:\\d{2}$");

    private static final Map<String, ResolvedStream> STREAM_CACHE = Collections.synchronizedMap(new HashMap<>());
    private static final Map<Integer, List<Track>> MOOD_CACHE = Collections.synchronizedMap(new HashMap<>());
    private static final Set<String> PREFETCHING = Collections.synchronizedSet(new HashSet<>());
    private static volatile String visitorData = "";

    // ------------------------------------------------------------------ Search & Discovery
    static void search(String query, boolean songsOnly, ListCallback cb) {
        if (query == null || query.trim().isEmpty()) {
            discover(0, cb);
            return;
        }
        final String q = query.trim();
        SEARCH_POOL.execute(() -> {
            try {
                List<Track> list = searchSync(q, songsOnly);
                if (list.isEmpty() && songsOnly) {
                    // Broaden automatically to all results (remixes, covers, live sessions)
                    list = searchSync(q, false);
                }
                final List<Track> res = list;
                MAIN.post(() -> cb.done(res, res.isEmpty() ? "NO RESULTS ON YT MUSIC" : null));
            } catch (Exception e) {
                MAIN.post(() -> cb.done(new ArrayList<>(), "NETWORK ERROR — CHECK CONNECTION"));
            }
        });
    }

    static void discover(int moodIdx, ListCallback cb) {
        final int idx = Math.max(0, Math.min(MOOD_QUERIES.length - 1, moodIdx));
        List<Track> cached = MOOD_CACHE.get(idx);
        if (cached != null && !cached.isEmpty()) {
            MAIN.post(() -> cb.done(new ArrayList<>(cached), null));
            return;
        }
        SEARCH_POOL.execute(() -> {
            try {
                List<Track> list = searchSync(MOOD_QUERIES[idx], true);
                if (list.isEmpty()) list = searchSync(MOOD_QUERIES[idx], false);
                if (!list.isEmpty()) MOOD_CACHE.put(idx, list);
                final List<Track> res = list;
                MAIN.post(() -> cb.done(res, res.isEmpty() ? "COULD NOT LOAD DISCOVER" : null));
            } catch (Exception e) {
                MAIN.post(() -> cb.done(new ArrayList<>(), "OFFLINE — CHECK INTERNET"));
            }
        });
    }

    /** Fetches 25-40 related tracks (SimpMusic-style YouTube Music Auto-Radio queue). */
    static void radio(String videoId, ListCallback cb) {
        if (videoId == null || videoId.isEmpty()) {
            MAIN.post(() -> cb.done(new ArrayList<>(), "INVALID TRACK"));
            return;
        }
        SEARCH_POOL.execute(() -> {
            try {
                List<Track> list = radioSync(videoId);
                MAIN.post(() -> cb.done(list, list.isEmpty() ? "NO RADIO TRACKS" : null));
            } catch (Exception e) {
                MAIN.post(() -> cb.done(new ArrayList<>(), "RADIO UNAVAILABLE"));
            }
        });
    }

    private static List<Track> searchSync(String query, boolean songsOnly) throws Exception {
        JSONObject client = new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20250310.01.00")
                .put("hl", "en")
                .put("gl", "US");
        JSONObject body = new JSONObject()
                .put("context", new JSONObject().put("client", client))
                .put("query", query);
        if (songsOnly) body.put("params", FILTER_SONGS);

        String raw = postJson("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString(), UA_WEB, true);
        JSONObject root = new JSONObject(raw);
        captureVisitorData(root);

        ArrayList<Track> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        collectResponsiveItems(root, out, seen);
        return out;
    }

    static List<Track> radioSync(String videoId) throws Exception {
        JSONObject client = new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", "1.20250310.01.00")
                .put("hl", "en")
                .put("gl", "US");
        JSONObject body = new JSONObject()
                .put("context", new JSONObject().put("client", client))
                .put("videoId", videoId)
                .put("playlistId", "RDAMVM" + videoId)
                .put("isAudioOnly", true);

        String raw = postJson("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", body.toString(), UA_WEB, true);
        JSONObject root = new JSONObject(raw);
        captureVisitorData(root);

        ArrayList<Track> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        collectPanelItems(root, out, seen);
        return out;
    }

    private static void captureVisitorData(JSONObject root) {
        JSONObject rc = root.optJSONObject("responseContext");
        if (rc != null) {
            String vd = rc.optString("visitorData", "");
            if (!vd.isEmpty()) visitorData = vd;
        }
    }

    // ------------------------------------------------------------------ JSON Tree Parsers
    private static void collectResponsiveItems(Object node, List<Track> out, Set<String> seen) {
        if (out.size() >= 35) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has("musicResponsiveListItemRenderer")) {
                Track t = parseResponsiveItem(obj.optJSONObject("musicResponsiveListItemRenderer"));
                if (t != null && seen.add(t.ytId)) out.add(t);
            }
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    collectResponsiveItems(obj.opt(names.optString(i)), out, seen);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectResponsiveItems(arr.opt(i), out, seen);
            }
        }
    }

    private static Track parseResponsiveItem(JSONObject it) {
        if (it == null) return null;
        String vid = "";
        JSONObject pid = it.optJSONObject("playlistItemData");
        if (pid != null) vid = pid.optString("videoId", "");
        if (vid.isEmpty()) {
            vid = findVideoId(it.optJSONObject("overlay"));
        }
        if (vid.isEmpty() || vid.length() != 11) return null;

        String title = "";
        String artist = "";
        String album = "";
        long durMs = 0L;

        JSONArray flex = it.optJSONArray("flexColumns");
        if (flex != null) {
            if (flex.length() > 0) {
                title = extractRuns(flex.optJSONObject(0));
            }
            if (flex.length() > 1) {
                String meta = extractRuns(flex.optJSONObject(1));
                String[] parts = meta.split("\\s*[•·]\\s*");
                ArrayList<String> clean = new ArrayList<>();
                for (String p : parts) {
                    String s = p.trim();
                    if (s.isEmpty()) continue;
                    if (s.equalsIgnoreCase("Song") || s.equalsIgnoreCase("Video")
                            || s.equalsIgnoreCase("Single") || s.endsWith("plays") || s.endsWith("views")) {
                        continue;
                    }
                    if (DUR_PAT.matcher(s).matches()) {
                        durMs = parseDurationMs(s);
                    } else {
                        clean.add(s);
                    }
                }
                if (clean.size() >= 1) artist = clean.get(0);
                if (clean.size() >= 2) album = clean.get(1);
            }
        }
        if (title.isEmpty()) return null;

        String thumb = extractBestThumb(it.optJSONObject("thumbnail"));
        return Track.ytm(vid, title, artist, album, durMs, thumb);
    }

    private static void collectPanelItems(Object node, List<Track> out, Set<String> seen) {
        if (out.size() >= 40) return;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has("playlistPanelVideoRenderer")) {
                JSONObject it = obj.optJSONObject("playlistPanelVideoRenderer");
                if (it != null) {
                    String vid = it.optString("videoId", "");
                    if (vid.length() == 11 && seen.add(vid)) {
                        String title = runsToString(it.optJSONObject("title"));
                        String artist = runsToString(it.optJSONObject("shortBylineText"));
                        if (artist.isEmpty()) artist = runsToString(it.optJSONObject("longBylineText"));
                        long dur = parseDurationMs(runsToString(it.optJSONObject("lengthText")));
                        String thumb = extractBestThumb(it.optJSONObject("thumbnail"));
                        if (!title.isEmpty()) {
                            out.add(Track.ytm(vid, title, artist, "", dur, thumb));
                        }
                    }
                }
            }
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    collectPanelItems(obj.opt(names.optString(i)), out, seen);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectPanelItems(arr.opt(i), out, seen);
            }
        }
    }

    private static String extractRuns(JSONObject flexCol) {
        if (flexCol == null) return "";
        JSONObject r = flexCol.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        if (r == null) return "";
        return runsToString(r.optJSONObject("text"));
    }

    private static String runsToString(JSONObject textObj) {
        if (textObj == null) return "";
        JSONArray runs = textObj.optJSONArray("runs");
        if (runs == null) return textObj.optString("simpleText", "").trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) sb.append(run.optString("text", ""));
        }
        return sb.toString().trim();
    }

    private static String findVideoId(JSONObject obj) {
        if (obj == null) return "";
        if (obj.has("videoId")) {
            String v = obj.optString("videoId", "");
            if (v.length() == 11) return v;
        }
        JSONArray names = obj.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                Object child = obj.opt(names.optString(i));
                if (child instanceof JSONObject) {
                    String v = findVideoId((JSONObject) child);
                    if (!v.isEmpty()) return v;
                }
            }
        }
        return "";
    }

    private static String extractBestThumb(JSONObject thumbContainer) {
        if (thumbContainer == null) return "";
        JSONArray arr = thumbContainer.optJSONArray("thumbnails");
        if (arr == null) {
            JSONObject mtr = thumbContainer.optJSONObject("musicThumbnailRenderer");
            if (mtr != null) {
                JSONObject th = mtr.optJSONObject("thumbnail");
                if (th != null) arr = th.optJSONArray("thumbnails");
            }
        }
        if (arr == null || arr.length() == 0) return "";
        JSONObject best = arr.optJSONObject(arr.length() - 1);
        return best == null ? "" : best.optString("url", "");
    }

    static long parseDurationMs(String s) {
        if (s == null || s.isEmpty()) return 0L;
        try {
            String[] p = s.trim().split(":");
            long sec = 0;
            for (String part : p) sec = sec * 60 + Long.parseLong(part.trim());
            return sec * 1000L;
        } catch (Exception e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ Multi-Tier Direct Audio Stream Resolver
    static ResolvedStream getCached(String videoId) {
        if (videoId == null || videoId.isEmpty()) return null;
        ResolvedStream s = STREAM_CACHE.get(videoId);
        return (s != null && s.isValid()) ? s : null;
    }

    static void invalidateCache(String videoId) {
        if (videoId != null) STREAM_CACHE.remove(videoId);
    }

    /** Silently pre-resolves an upcoming YouTube Music track in background so Next/Prev is instant! */
    static void prefetch(Track t) {
        if (t == null || !t.needsResolve() || t.ytId.isEmpty()) return;
        if (getCached(t.ytId) != null) return;
        if (!PREFETCHING.add(t.ytId)) return;
        STREAM_POOL.execute(() -> {
            try {
                resolveAudioSync(t.ytId, false);
            } catch (Exception ignored) {
            } finally {
                PREFETCHING.remove(t.ytId);
            }
        });
    }

    static ResolvedStream resolveAudioSync(String videoId, boolean forceRefresh) {
        if (videoId == null || videoId.isEmpty()) return null;
        if (!forceRefresh) {
            ResolvedStream cached = getCached(videoId);
            if (cached != null) return cached;
        }

        // Tier 1: InnerTube ANDROID_VR (direct progressive AAC/M4A & Opus audio, zero PO-token!)
        ResolvedStream res = resolveViaInnerTube(videoId, "ANDROID_VR", "1.62.27", UA_VR);
        if (res != null) {
            STREAM_CACHE.put(videoId, res);
            return res;
        }

        // Tier 2: InnerTube IOS client (direct AAC/M4A & Opus streams)
        res = resolveViaInnerTube(videoId, "IOS", "20.10.4", UA_IOS);
        if (res != null) {
            STREAM_CACHE.put(videoId, res);
            return res;
        }

        // Tier 3: Open-source public Piped API mirrors
        res = resolveViaPiped(videoId);
        if (res != null) {
            STREAM_CACHE.put(videoId, res);
            return res;
        }

        // Tier 4: Open-source public Invidious API mirrors
        res = resolveViaInvidious(videoId);
        if (res != null) {
            STREAM_CACHE.put(videoId, res);
            return res;
        }
        return null;
    }

    private static ResolvedStream resolveViaInnerTube(String videoId, String clientName, String clientVer, String ua) {
        try {
            JSONObject cli = new JSONObject()
                    .put("clientName", clientName)
                    .put("clientVersion", clientVer)
                    .put("hl", "en")
                    .put("gl", "US");
            if ("IOS".equals(clientName)) {
                cli.put("deviceMake", "Apple")
                        .put("deviceModel", "iPhone16,2")
                        .put("osName", "iPhone")
                        .put("osVersion", "18.3.2.22D82");
            } else if ("ANDROID_VR".equals(clientName)) {
                cli.put("androidSdkVersion", 32)
                        .put("deviceMake", "Oculus")
                        .put("deviceModel", "Quest 3")
                        .put("osName", "Android")
                        .put("osVersion", "12L");
            }
            if (!visitorData.isEmpty()) cli.put("visitorData", visitorData);

            JSONObject body = new JSONObject()
                    .put("context", new JSONObject().put("client", cli))
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true);

            String raw = postJson("https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false", body.toString(), ua, false);
            JSONObject root = new JSONObject(raw);
            JSONObject sd = root.optJSONObject("streamingData");
            if (sd == null) return null;

            long expiresSec = sd.optLong("expiresInSeconds", 14400L);
            long expAt = System.currentTimeMillis() + Math.max(1800L, expiresSec - 900L) * 1000L;

            JSONArray af = sd.optJSONArray("adaptiveFormats");
            String bestUrl = null;
            String bestCodec = "AAC";
            int bestScore = -1;
            int bestBr = 128;

            if (af != null) {
                for (int i = 0; i < af.length(); i++) {
                    JSONObject f = af.optJSONObject(i);
                    if (f == null) continue;
                    String mime = f.optString("mimeType", "").toLowerCase(Locale.ROOT);
                    if (!mime.startsWith("audio/")) continue;
                    String u = f.optString("url", "");
                    if (u.isEmpty()) continue;

                    int br = f.optInt("bitrate", 64000);
                    boolean isMp4 = mime.contains("mp4") || mime.contains("mp4a");
                    boolean isOpus = mime.contains("opus");
                    // Prefer 128-256k AAC/M4A (hardware-decoded on weak phones with lowest CPU!) or Opus
                    int score = (br / 1000);
                    if (isMp4) score += 50;
                    else if (isOpus) score += 35;
                    // Avoid excessively huge bitrates if lower good ones exist on weak connections, cap bonus at 220k
                    if (br > 260_000) score -= 20;

                    if (score > bestScore) {
                        bestScore = score;
                        bestUrl = u;
                        bestBr = Math.max(48, br / 1000);
                        bestCodec = isOpus ? "OPUS" : "AAC";
                    }
                }
            }
            if (bestUrl != null) {
                return new ResolvedStream(bestUrl, ua, bestCodec, bestBr, expAt);
            }
            // Fallback to progressive muxed `formats` if audio-only adaptiveFormats lacked direct URL
            JSONArray fmts = sd.optJSONArray("formats");
            if (fmts != null) {
                for (int i = 0; i < fmts.length(); i++) {
                    JSONObject f = fmts.optJSONObject(i);
                    if (f == null) continue;
                    String u = f.optString("url", "");
                    if (!u.isEmpty()) {
                        return new ResolvedStream(u, ua, "AAC", 128, expAt);
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static ResolvedStream resolveViaInvidious(String videoId) {
        for (String host : INVIDIOUS_MIRRORS) {
            try {
                String raw = getText(host + "/api/v1/videos/" + videoId + "?fields=adaptiveFormats", UA_WEB);
                JSONObject o = new JSONObject(raw);
                JSONArray af = o.optJSONArray("adaptiveFormats");
                if (af == null) continue;
                String bestUrl = null;
                int bestBr = -1;
                String bestCodec = "AAC";
                for (int i = 0; i < af.length(); i++) {
                    JSONObject s = af.optJSONObject(i);
                    if (s == null) continue;
                    String type = s.optString("type", "").toLowerCase(Locale.ROOT);
                    if (!type.startsWith("audio/")) continue;
                    String u = s.optString("url", "");
                    if (u.isEmpty()) continue;
                    int br = 0;
                    try { br = Integer.parseInt(s.optString("bitrate", "0")); } catch (Exception ignored) { }
                    if (br > bestBr) {
                        bestBr = br;
                        bestUrl = u;
                        bestCodec = type.contains("opus") ? "OPUS" : "AAC";
                    }
                }
                if (bestUrl != null) {
                    return new ResolvedStream(bestUrl, UA_WEB, bestCodec, Math.max(64, bestBr / 1000),
                            System.currentTimeMillis() + 7200_000L);
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static ResolvedStream resolveViaPiped(String videoId) {
        for (String host : PIPED_MIRRORS) {
            try {
                String raw = getText(host + "/streams/" + videoId, UA_WEB);
                JSONObject o = new JSONObject(raw);
                JSONArray streams = o.optJSONArray("audioStreams");
                if (streams == null) continue;
                String bestUrl = null;
                int bestBr = -1;
                String bestCodec = "AAC";
                for (int i = 0; i < streams.length(); i++) {
                    JSONObject s = streams.optJSONObject(i);
                    if (s == null) continue;
                    String u = s.optString("url", "");
                    if (u.isEmpty()) continue;
                    int br = s.optInt("bitrate", 0);
                    String fmt = s.optString("mimeType", "").toLowerCase(Locale.ROOT);
                    if (br > bestBr) {
                        bestBr = br;
                        bestUrl = u;
                        bestCodec = fmt.contains("opus") ? "OPUS" : "AAC";
                    }
                }
                if (bestUrl != null) {
                    return new ResolvedStream(bestUrl, UA_WEB, bestCodec, Math.max(64, bestBr / 1000),
                            System.currentTimeMillis() + 7200_000L);
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    // ------------------------------------------------------------------ HTTP Helpers
    private static String postJson(String urlStr, String json, String ua, boolean ytmHeaders) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("User-Agent", ua);
        if (ytmHeaders) {
            c.setRequestProperty("Origin", "https://music.youtube.com");
            c.setRequestProperty("Referer", "https://music.youtube.com/");
            c.setRequestProperty("X-YouTube-Client-Name", "67");
            c.setRequestProperty("X-YouTube-Client-Version", "1.20250310.01.00");
        }
        byte[] payload = json.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload);
        }
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        if (in == null) {
            c.disconnect();
            return "";
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder(16384);
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 900_000) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static String getText(String urlStr, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(6500);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", ua);
        try (InputStream in = c.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder sb = new StringBuilder(8192);
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0 && sb.length() < 350_000) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
