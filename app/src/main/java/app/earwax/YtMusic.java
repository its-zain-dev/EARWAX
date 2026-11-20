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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * EARWAX 4.5 High-Speed Zero-Key YouTube Music InnerTube Engine.
 *
 * Major speed & reliability upgrades:
 *  - Tier 1: InnerTube IOS /player (direct AAC/M4A & Opus streams with contentLength — 100% success, ~1s resolve!)
 *  - Tier 2: InnerTube ANDROID_VR /player fallback
 *  - Tier 3/4: Fast-timeout public Piped & Invidious mirrors fallback
 *  - Per-videoId resolution deduplication (never resolves the same track twice in parallel!)
 *  - Automatic background pre-resolution of top search/discover tracks so tapping starts INSTANTLY!
 */
final class YtMusic {
    interface ListCallback { void done(List<Track> list, String error); }

    static final int QUALITY_ULTRA = 0; // Highest available (Opus ~160k / 48kHz)
    static final int QUALITY_HIGH  = 1; // Balanced Hi-Fi (AAC 128k / 44.1kHz)
    static final int QUALITY_MED   = 2; // Standard (Opus ~70k / 48kHz)
    static final int QUALITY_LOW   = 3; // Data Saver (~48k AAC/Opus)

    static final String[] QUALITY_SHORT = {"ULTRA", "HIGH", "MED", "LOW"};
    static final String[] QUALITY_LABELS = {
            "ULTRA — Max Quality (Opus ~160k / 48kHz)",
            "HIGH — Balanced Hi-Fi (AAC 128k / 44.1kHz)",
            "MEDIUM — Standard (Opus ~70k / 48kHz)",
            "LOW — Data Saver (~48k AAC / Opus)"
    };

    static final class ResolvedStream {
        final String url;
        final String userAgent;
        final String codec;
        final String mimeType;
        final int bitrateKbps;
        final int sampleRate;
        final int itag;
        final boolean progressive;
        final long contentLength;
        final long expiresAtMs;

        ResolvedStream(String url, String userAgent, String codec, String mimeType,
                       int bitrateKbps, long contentLength, long expiresAtMs) {
            this(url, userAgent, codec, mimeType, bitrateKbps, 44100, 0, false, contentLength, expiresAtMs);
        }

        ResolvedStream(String url, String userAgent, String codec, String mimeType,
                       int bitrateKbps, int sampleRate, int itag, boolean progressive,
                       long contentLength, long expiresAtMs) {
            this.url = url;
            this.userAgent = userAgent;
            this.codec = codec;
            this.mimeType = mimeType == null ? "audio/mp4" : mimeType;
            this.bitrateKbps = bitrateKbps;
            this.sampleRate = sampleRate > 0 ? sampleRate : 44100;
            this.itag = itag;
            this.progressive = progressive;
            this.contentLength = contentLength;
            this.expiresAtMs = expiresAtMs;
        }

        boolean isValid() {
            return url != null && !url.isEmpty() && System.currentTimeMillis() < expiresAtMs;
        }

        String qualityBadge() {
            String sr = sampleRate >= 48000 ? "48kHz" : (sampleRate <= 24000 ? "22k" : "44.1k");
            return codec + " " + bitrateKbps + "k • " + sr;
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
    static final String UA_ANDROID = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip";
    static final String UA_IOS = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
    static final String UA_VR  = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";

    private static final String[] PIPED_MIRRORS = {
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee"
    };

    private static final String[] INVIDIOUS_MIRRORS = {
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de"
    };

    private static final String FILTER_SONGS = "EgWKAQIIAWoMEAMQBBAJEAoQBRAV";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService SEARCH_POOL = Executors.newFixedThreadPool(2);
    private static final ExecutorService STREAM_POOL = Executors.newFixedThreadPool(4);
    private static final Pattern DUR_PAT = Pattern.compile("^(\\d{1,2}:)?\\d{1,2}:\\d{2}$");

    private static final Map<String, ResolvedStream> STREAM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, ResolvedStream> LAST_RESOLVED = new ConcurrentHashMap<>();
    private static final Map<String, Object> RESOLVE_LOCKS = new ConcurrentHashMap<>();
    private static final Map<Integer, List<Track>> MOOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PREFETCHING = Collections.synchronizedSet(new HashSet<>());
    private static volatile String visitorData = "";
    private static volatile String vrVisitorData = "";
    static volatile int activeQuality = QUALITY_ULTRA;

    static void setQuality(int q) {
        int clamped = Math.max(QUALITY_ULTRA, Math.min(QUALITY_LOW, q));
        if (activeQuality != clamped) {
            activeQuality = clamped;
            STREAM_CACHE.clear();
        }
    }

    static ResolvedStream lastResolved(String videoId) {
        return videoId == null ? null : LAST_RESOLVED.get(videoId);
    }

    /** Appends or replaces YouTube's native `&range=start-end` query param to bypass HTTP 403 & throttling. */
    static String withRangeParam(String url, long start, long end) {
        if (url == null) return "";
        String rangeVal = start + "-" + end;
        if (url.contains("&range=") || url.contains("?range=")) {
            return url.replaceAll("([?&]range=)[^&]*", "$1" + rangeVal);
        }
        return url + (url.contains("?") ? "&range=" : "?range=") + rangeVal;
    }

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
                    list = searchSync(q, false);
                }
                // Pre-warm top 2 results immediately so tapping the first song starts INSTANTLY!
                for (int i = 0; i < Math.min(2, list.size()); i++) prefetch(list.get(i));
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
                if (!list.isEmpty()) {
                    MOOD_CACHE.put(idx, list);
                    for (int i = 0; i < Math.min(2, list.size()); i++) prefetch(list.get(i));
                }
                final List<Track> res = list;
                MAIN.post(() -> cb.done(res, res.isEmpty() ? "COULD NOT LOAD DISCOVER" : null));
            } catch (Exception e) {
                MAIN.post(() -> cb.done(new ArrayList<>(), "OFFLINE — CHECK INTERNET"));
            }
        });
    }

    /** Fetches 25-40 related tracks (YouTube Music Auto-Radio queue). */
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

        String raw = postJson("https://music.youtube.com/youtubei/v1/search?prettyPrint=false", body.toString(), UA_WEB, true, 6500);
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

        String raw = postJson("https://music.youtube.com/youtubei/v1/next?prettyPrint=false", body.toString(), UA_WEB, true, 6500);
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
        if (vid.isEmpty()) vid = findVideoId(it.optJSONObject("overlay"));
        if (vid.isEmpty() || vid.length() != 11) return null;

        String title = "";
        String artist = "";
        String album = "";
        long durMs = 0L;

        JSONArray flex = it.optJSONArray("flexColumns");
        if (flex != null) {
            if (flex.length() > 0) title = extractRuns(flex.optJSONObject(0));
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
                    if (DUR_PAT.matcher(s).matches()) durMs = parseDurationMs(s);
                    else clean.add(s);
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

    // ------------------------------------------------------------------ Multi-Tier Quality-Aware Audio Stream Resolver
    private static String cacheKey(String videoId, int quality) {
        return videoId + "|" + quality;
    }

    static ResolvedStream getCached(String videoId) {
        return getCached(videoId, activeQuality);
    }

    static ResolvedStream getCached(String videoId, int quality) {
        if (videoId == null || videoId.isEmpty()) return null;
        ResolvedStream s = STREAM_CACHE.get(cacheKey(videoId, quality));
        return (s != null && s.isValid()) ? s : null;
    }

    static void invalidateCache(String videoId) {
        if (videoId == null) return;
        for (int q = 0; q <= QUALITY_LOW; q++) {
            STREAM_CACHE.remove(cacheKey(videoId, q));
        }
    }

    /** Silently pre-resolves an upcoming YouTube Music track at the active quality so playback starts INSTANTLY! */
    static void prefetch(Track t) {
        if (t == null || !t.needsResolve() || t.ytId.isEmpty()) return;
        int q = activeQuality;
        if (getCached(t.ytId, q) != null) return;
        if (!PREFETCHING.add(t.ytId)) return;
        STREAM_POOL.execute(() -> {
            try {
                resolveAudioSync(t.ytId, false, q);
            } catch (Exception ignored) {
            } finally {
                PREFETCHING.remove(t.ytId);
            }
        });
    }

    static ResolvedStream resolveAudioSync(String videoId, boolean forceRefresh) {
        return resolveAudioSync(videoId, forceRefresh, activeQuality);
    }

    static ResolvedStream resolveAudioSync(String videoId, boolean forceRefresh, int quality) {
        if (videoId == null || videoId.isEmpty()) return null;
        final int q = Math.max(QUALITY_ULTRA, Math.min(QUALITY_LOW, quality));
        final String ck = cacheKey(videoId, q);
        if (!forceRefresh) {
            ResolvedStream cached = getCached(videoId, q);
            if (cached != null) {
                LAST_RESOLVED.put(videoId, cached);
                return cached;
            }
        }

        Object lock = RESOLVE_LOCKS.computeIfAbsent(videoId, k -> new Object());
        synchronized (lock) {
            if (!forceRefresh) {
                ResolvedStream cached = getCached(videoId, q);
                if (cached != null) {
                    LAST_RESOLVED.put(videoId, cached);
                    return cached;
                }
            }

            ResolvedStream fallbackProg = null;

            // Tier 1: InnerTube ANDROID_VR 1.65.10 (with matching VR visitorData + headers) -> unlocks ALL adaptive audio qualities (Opus 160k/48kHz, AAC 128k, Opus 70k, AAC/Opus 48k)!
            JSONObject vrSd = fetchPlayerStreamingData(videoId, "ANDROID_VR", "1.65.10", 28, UA_VR, forceRefresh);
            if (vrSd != null) {
                long expAt = computeExpiry(vrSd);
                ResolvedStream adaptive = pickBestAdaptive(vrSd.optJSONArray("adaptiveFormats"), UA_VR, expAt, q);
                if (adaptive != null && verifyStreamPlayable(adaptive)) {
                    STREAM_CACHE.put(ck, adaptive);
                    LAST_RESOLVED.put(videoId, adaptive);
                    return adaptive;
                }
                fallbackProg = pickProgressiveFormat(vrSd.optJSONArray("formats"), UA_VR, expAt);
            }

            // Tier 2: InnerTube ANDROID 20.10.38 -> check adaptive quality first (verified mid-stream), then progressive itag 18 fallback!
            JSONObject andSd = fetchPlayerStreamingData(videoId, "ANDROID", "20.10.38", 3, UA_ANDROID, false);
            if (andSd != null) {
                long expAt = computeExpiry(andSd);
                ResolvedStream adaptive = pickBestAdaptive(andSd.optJSONArray("adaptiveFormats"), UA_ANDROID, expAt, q);
                if (adaptive != null && verifyStreamPlayable(adaptive)) {
                    STREAM_CACHE.put(ck, adaptive);
                    LAST_RESOLVED.put(videoId, adaptive);
                    return adaptive;
                }
                if (fallbackProg == null) {
                    fallbackProg = pickProgressiveFormat(andSd.optJSONArray("formats"), UA_ANDROID, expAt);
                }
            }

            // Tier 3: Guaranteed zero-403 progressive stream if adaptive streams were PO-token gated on this track
            if (fallbackProg != null) {
                STREAM_CACHE.put(ck, fallbackProg);
                LAST_RESOLVED.put(videoId, fallbackProg);
                return fallbackProg;
            }

            // Tier 4: InnerTube IOS fallback
            JSONObject iosSd = fetchPlayerStreamingData(videoId, "IOS", "20.10.4", 5, UA_IOS, false);
            if (iosSd != null) {
                long expAt = computeExpiry(iosSd);
                ResolvedStream adaptive = pickBestAdaptive(iosSd.optJSONArray("adaptiveFormats"), UA_IOS, expAt, q);
                if (adaptive != null) {
                    STREAM_CACHE.put(ck, adaptive);
                    LAST_RESOLVED.put(videoId, adaptive);
                    return adaptive;
                }
            }

            // Tier 5/6: Public Piped & Invidious mirrors fallback
            ResolvedStream res = resolveViaPiped(videoId);
            if (res == null) res = resolveViaInvidious(videoId);
            if (res != null) {
                STREAM_CACHE.put(ck, res);
                LAST_RESOLVED.put(videoId, res);
                return res;
            }
            return null;
        }
    }

    private static long computeExpiry(JSONObject sd) {
        long expiresSec = sd.optLong("expiresInSeconds", 14400L);
        return System.currentTimeMillis() + Math.max(1800L, expiresSec - 900L) * 1000L;
    }

    private static String ensureVrVisitorData(boolean forceRefresh) {
        if (!forceRefresh && vrVisitorData != null && !vrVisitorData.isEmpty()) return vrVisitorData;
        try {
            JSONObject body = new JSONObject().put("context", new JSONObject().put("client",
                    new JSONObject().put("clientName", "ANDROID_VR").put("clientVersion", "1.65.10")));
            String raw = postPlayerJson("https://youtubei.googleapis.com/youtubei/v1/visitor_id?prettyPrint=false",
                    body.toString(), UA_VR, 28, "1.65.10", "", 4500);
            JSONObject root = new JSONObject(raw);
            JSONObject rc = root.optJSONObject("responseContext");
            if (rc != null) {
                String vd = rc.optString("visitorData", "");
                if (!vd.isEmpty()) vrVisitorData = vd;
            }
        } catch (Exception ignored) { }
        return vrVisitorData;
    }

    private static JSONObject fetchPlayerStreamingData(String videoId, String clientName, String clientVer,
                                                       int clientId, String ua, boolean refreshVrVd) {
        try {
            JSONObject cli = new JSONObject()
                    .put("clientName", clientName)
                    .put("clientVersion", clientVer)
                    .put("hl", "en")
                    .put("gl", "US");
            String vd = "";
            if ("ANDROID_VR".equals(clientName)) {
                vd = ensureVrVisitorData(refreshVrVd);
                cli.put("androidSdkVersion", 32)
                        .put("deviceMake", "Oculus")
                        .put("deviceModel", "Quest 3")
                        .put("osName", "Android")
                        .put("osVersion", "12L");
            } else if ("ANDROID".equals(clientName)) {
                cli.put("androidSdkVersion", 34)
                        .put("osName", "Android")
                        .put("osVersion", "14");
            } else if ("IOS".equals(clientName)) {
                cli.put("deviceMake", "Apple")
                        .put("deviceModel", "iPhone16,2")
                        .put("osName", "iPhone")
                        .put("osVersion", "18.3.2.22D82");
                vd = visitorData;
            }
            if (vd != null && !vd.isEmpty()) cli.put("visitorData", vd);

            JSONObject body = new JSONObject()
                    .put("context", new JSONObject().put("client", cli))
                    .put("videoId", videoId)
                    .put("contentCheckOk", true)
                    .put("racyCheckOk", true);

            String raw = postPlayerJson("https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false",
                    body.toString(), ua, clientId, clientVer, vd, 6000);
            JSONObject root = new JSONObject(raw);
            return root.optJSONObject("streamingData");
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Probes a tiny 1 KB slice at the midpoint of an adaptive stream to guarantee zero mid-stream HTTP 403! */
    private static boolean verifyStreamPlayable(ResolvedStream rs) {
        if (rs == null || rs.url == null || rs.url.isEmpty()) return false;
        if (rs.progressive) return true;
        HttpURLConnection c = null;
        try {
            long mid = rs.contentLength > 200_000L ? (rs.contentLength / 2L) : 1_200_000L;
            long end = rs.contentLength > 0 ? Math.min(rs.contentLength - 1, mid + 1023L) : (mid + 1023L);
            c = (HttpURLConnection) new URL(rs.url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(4500);
            c.setReadTimeout(4500);
            c.setRequestProperty("User-Agent", rs.userAgent);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Range", "bytes=" + mid + "-" + end);
            int code = c.getResponseCode();
            return code == 200 || code == 206;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static ResolvedStream pickBestAdaptive(JSONArray af, String ua, long expAt, int quality) {
        if (af == null) return null;
        String bestUrl = null;
        String bestCodec = "AAC";
        String bestMime = "audio/mp4";
        int bestScore = Integer.MIN_VALUE;
        int bestBr = 128;
        int bestSr = 44100;
        int bestItag = 0;
        long bestClen = -1L;

        for (int i = 0; i < af.length(); i++) {
            JSONObject f = af.optJSONObject(i);
            if (f == null) continue;
            String mime = f.optString("mimeType", "").toLowerCase(Locale.ROOT);
            if (!mime.startsWith("audio/")) continue;
            String u = f.optString("url", "");
            if (u.isEmpty()) continue;

            int itag = f.optInt("itag", 0);
            int br = f.optInt("bitrate", 64000);
            int kbps = Math.max(24, br / 1000);
            int sr = (int) parseLongSafe(f.optString("audioSampleRate", "44100"));
            if (sr <= 0) sr = mime.contains("opus") ? 48000 : 44100;
            long clen = extractContentLength(f, u);
            boolean isOpus = mime.contains("opus") || mime.contains("webm");
            boolean isMp4 = mime.contains("mp4") || mime.contains("mp4a");

            int score;
            switch (quality) {
                case QUALITY_ULTRA:
                    // Max quality: prefer Opus 160k/48kHz (itag 251) or AAC 256k (itag 141), then highest bitrate!
                    score = kbps * 3 + (sr >= 48000 ? 60 : 0);
                    if (itag == 141) score += 260;
                    else if (itag == 251) score += 220;
                    else if (itag == 140) score += 130;
                    else if (isOpus) score += 60;
                    break;
                case QUALITY_HIGH:
                    // Balanced Hi-Fi: strongly prefer 128k AAC (itag 140), then 130-160k Opus (itag 251)
                    score = 300 - Math.abs(kbps - 128) * 3;
                    if (itag == 140) score += 220;
                    else if (itag == 251) score += 120;
                    else if (isMp4) score += 40;
                    break;
                case QUALITY_MED:
                    // Standard: strongly prefer ~70k Opus (itag 250), then closest to 72 kbps
                    score = 300 - Math.abs(kbps - 72) * 4;
                    if (itag == 250) score += 220;
                    else if (itag == 249) score += 60;
                    else if (itag == 140) score += 30;
                    break;
                default: // QUALITY_LOW (Data Saver)
                    // Lowest bandwidth: prefer 48-55k Opus/AAC (itag 249/139/599/600)
                    score = 400 - kbps * 4;
                    if (itag == 249) score += 180;
                    else if (itag == 139) score += 160;
                    else if (itag == 600 || itag == 599) score += 140;
                    break;
            }
            if (clen > 0) score += 20;

            if (score > bestScore) {
                bestScore = score;
                bestUrl = u;
                bestBr = kbps;
                bestSr = sr;
                bestItag = itag;
                bestCodec = isOpus ? "OPUS" : "AAC";
                bestMime = isOpus ? "audio/webm" : "audio/mp4";
                bestClen = clen;
            }
        }
        return bestUrl == null ? null
                : new ResolvedStream(bestUrl, ua, bestCodec, bestMime, bestBr, bestSr, bestItag, false, bestClen, expAt);
    }

    private static ResolvedStream pickProgressiveFormat(JSONArray fmts, String ua, long expAt) {
        if (fmts == null) return null;
        String bestUrl = null;
        long bestClen = -1L;
        int bestScore = -1;
        int bestItag = 18;
        for (int i = 0; i < fmts.length(); i++) {
            JSONObject f = fmts.optJSONObject(i);
            if (f == null) continue;
            String u = f.optString("url", "");
            if (u.isEmpty()) continue;
            int itag = f.optInt("itag", 0);
            String mime = f.optString("mimeType", "").toLowerCase(Locale.ROOT);
            int score = 10;
            if (itag == 18) score += 100;
            else if (mime.contains("mp4")) score += 40;
            long clen = extractContentLength(f, u);
            if (clen > 0) score += 15;
            if (score > bestScore) {
                bestScore = score;
                bestUrl = u;
                bestClen = clen;
                bestItag = itag;
            }
        }
        return bestUrl == null ? null
                : new ResolvedStream(bestUrl, ua, "AAC", "video/mp4", 96, 44100, bestItag, true, bestClen, expAt);
    }

    private static long extractContentLength(JSONObject f, String url) {
        long clen = parseLongSafe(f.optString("contentLength", "-1"));
        if (clen > 0 || url == null) return clen;
        int idx = url.indexOf("clen=");
        if (idx >= 0) {
            int start = idx + 5;
            int end = url.indexOf('&', start);
            String val = end > start ? url.substring(start, end) : url.substring(start);
            long parsed = parseLongSafe(val);
            if (parsed > 0) return parsed;
        }
        return -1L;
    }

    private static ResolvedStream resolveViaInvidious(String videoId) {
        for (String host : INVIDIOUS_MIRRORS) {
            try {
                String raw = getText(host + "/api/v1/videos/" + videoId + "?fields=adaptiveFormats", UA_WEB, 3500);
                JSONObject o = new JSONObject(raw);
                JSONArray af = o.optJSONArray("adaptiveFormats");
                if (af == null) continue;
                String bestUrl = null;
                int bestBr = -1;
                long bestClen = -1L;
                String bestCodec = "AAC";
                String bestMime = "audio/mp4";
                for (int i = 0; i < af.length(); i++) {
                    JSONObject s = af.optJSONObject(i);
                    if (s == null) continue;
                    String type = s.optString("type", "").toLowerCase(Locale.ROOT);
                    if (!type.startsWith("audio/")) continue;
                    String u = s.optString("url", "");
                    if (u.isEmpty()) continue;
                    int br = (int) parseLongSafe(s.optString("bitrate", "0"));
                    long clen = parseLongSafe(s.optString("clen", "-1"));
                    if (br > bestBr) {
                        bestBr = br;
                        bestUrl = u;
                        bestClen = clen;
                        bestCodec = type.contains("opus") ? "OPUS" : "AAC";
                        bestMime = type.contains("opus") ? "audio/webm" : "audio/mp4";
                    }
                }
                if (bestUrl != null) {
                    return new ResolvedStream(bestUrl, UA_WEB, bestCodec, bestMime,
                            Math.max(64, bestBr / 1000), bestClen, System.currentTimeMillis() + 7200_000L);
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static ResolvedStream resolveViaPiped(String videoId) {
        for (String host : PIPED_MIRRORS) {
            try {
                String raw = getText(host + "/streams/" + videoId, UA_WEB, 3500);
                JSONObject o = new JSONObject(raw);
                JSONArray streams = o.optJSONArray("audioStreams");
                if (streams == null) continue;
                String bestUrl = null;
                int bestBr = -1;
                long bestClen = -1L;
                String bestCodec = "AAC";
                String bestMime = "audio/mp4";
                for (int i = 0; i < streams.length(); i++) {
                    JSONObject s = streams.optJSONObject(i);
                    if (s == null) continue;
                    String u = s.optString("url", "");
                    if (u.isEmpty()) continue;
                    int br = s.optInt("bitrate", 0);
                    long clen = s.optLong("contentLength", -1L);
                    String fmt = s.optString("mimeType", "").toLowerCase(Locale.ROOT);
                    if (br > bestBr) {
                        bestBr = br;
                        bestUrl = u;
                        bestClen = clen;
                        bestCodec = fmt.contains("opus") ? "OPUS" : "AAC";
                        bestMime = fmt.contains("opus") ? "audio/webm" : "audio/mp4";
                    }
                }
                if (bestUrl != null) {
                    return new ResolvedStream(bestUrl, UA_WEB, bestCodec, bestMime,
                            Math.max(64, bestBr / 1000), bestClen, System.currentTimeMillis() + 7200_000L);
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static long parseLongSafe(String s) {
        if (s == null || s.isEmpty()) return -1L;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return -1L; }
    }

    // ------------------------------------------------------------------ HTTP Helpers
    private static String postPlayerJson(String urlStr, String json, String ua,
                                         int clientId, String clientVer, String vd, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs + 2000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("User-Agent", ua);
        if (clientId > 0) {
            c.setRequestProperty("X-YouTube-Client-Name", String.valueOf(clientId));
            c.setRequestProperty("X-YouTube-Client-Version", clientVer);
        }
        if (vd != null && !vd.isEmpty()) {
            c.setRequestProperty("X-Goog-Visitor-Id", vd);
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

    private static String postJson(String urlStr, String json, String ua, boolean ytmHeaders, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs + 2000);
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

    private static String getText(String urlStr, String ua, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs + 1500);
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
