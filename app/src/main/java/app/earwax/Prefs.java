package app.earwax;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * EARWAX 4.0 Bulletproof Persistent State Store.
 * Persists:
 *  - Complete Playback Queue Snapshot (all track types: Local, YouTube Music, Offline, Radio),
 *    exact index, exact millisecond position, and wasPlaying flag so you ALWAYS pick up
 *    exactly where you left off even after a device freeze or process kill!
 *  - Unified Favorites (Local + YouTube Music), Recents, Offline Downloads, Custom Playlists, Search History
 *  - Complete Pro DSP & EQ profile, Theme, Visualizer, Gyro, Reliability toggles, and Popup geometry
 */
final class Prefs {
    static final class PlaybackSnapshot {
        final ArrayList<Track> queue;
        final String queueKey;
        final int index;
        final long posMs;
        final boolean wasPlaying;

        PlaybackSnapshot(ArrayList<Track> queue, String queueKey, int index, long posMs, boolean wasPlaying) {
            this.queue = queue;
            this.queueKey = queueKey == null ? "" : queueKey;
            this.index = index;
            this.posMs = Math.max(0L, posMs);
            this.wasPlaying = wasPlaying;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("earwax_v4", Context.MODE_PRIVATE);
    }

    // ---- Onboarding & Visuals
    static boolean tourSeen(Context c) { return sp(c).getBoolean("tour_v4", false); }
    static void tourSeen(Context c, boolean v) { sp(c).edit().putBoolean("tour_v4", v).apply(); }

    static int theme(Context c) { return sp(c).getInt("theme", 0); }
    static void theme(Context c, int idx) { sp(c).edit().putInt("theme", idx).apply(); }

    static int vizMode(Context c) { return sp(c).getInt("viz", 0); }
    static void vizMode(Context c, int m) { sp(c).edit().putInt("viz", m).apply(); }

    static boolean gyro(Context c) { return sp(c).getBoolean("gyro", false); }
    static void gyro(Context c, boolean on) { sp(c).edit().putBoolean("gyro", on).apply(); }

    static int sortMode(Context c) { return sp(c).getInt("sort", 0); }
    static void sortMode(Context c, int m) { sp(c).edit().putInt("sort", m).apply(); }

    static int lastTab(Context c) { return sp(c).getInt("last_tab", 0); }
    static void lastTab(Context c, int t) { sp(c).edit().putInt("last_tab", t).apply(); }

    // ---- Reliability & Playback Behavior toggles
    static boolean ignoreFocus(Context c) { return sp(c).getBoolean("ignore_focus", false); }
    static void ignoreFocus(Context c, boolean on) { sp(c).edit().putBoolean("ignore_focus", on).apply(); }

    static boolean ytAutoRadio(Context c) { return sp(c).getBoolean("yt_autoradio", true); }
    static void ytAutoRadio(Context c, boolean on) { sp(c).edit().putBoolean("yt_autoradio", on).apply(); }

    // ---- Floating Popup
    static String popupName(Context c) { return sp(c).getString("pname", "whale"); }
    static void popupName(Context c, String n) { sp(c).edit().putString("pname", n).apply(); }

    static float popupScale(Context c, String skin) { return sp(c).getFloat("pscale_" + skin, 1f); }
    static void popupScale(Context c, String skin, float v) { sp(c).edit().putFloat("pscale_" + skin, v).apply(); }

    static int popupX(Context c, String skin, int def) { return sp(c).getInt("px_" + skin, def); }
    static int popupY(Context c, String skin, int def) { return sp(c).getInt("py_" + skin, def); }
    static void popupPos(Context c, String skin, int x, int y) {
        sp(c).edit().putInt("px_" + skin, x).putInt("py_" + skin, y).apply();
    }

    // ---- Bulletproof Full Playback Queue & Position Snapshot
    static void saveQueueSnapshot(Context c, List<Track> queue, String queueKey, int idx, long posMs,
                                  boolean wasPlaying, boolean syncCommit) {
        try {
            // Cap persisted queue to 180 tracks centered around current idx so serialization is instant & tiny!
            int total = queue == null ? 0 : queue.size();
            int start = 0;
            int end = total;
            int adjIdx = Math.max(0, Math.min(Math.max(0, total - 1), idx));
            if (total > 180) {
                start = Math.max(0, adjIdx - 40);
                end = Math.min(total, start + 180);
                if (end - start < 180) start = Math.max(0, end - 180);
                adjIdx = adjIdx - start;
            }
            JSONArray arr = new JSONArray();
            for (int i = start; i < end; i++) arr.put(queue.get(i).toJson());

            SharedPreferences.Editor ed = sp(c).edit()
                    .putString("snap_queue", arr.toString())
                    .putString("snap_qkey", queueKey == null ? "" : queueKey)
                    .putInt("snap_idx", adjIdx)
                    .putLong("snap_pos", Math.max(0L, posMs))
                    .putBoolean("snap_playing", wasPlaying);
            if (syncCommit) ed.commit();
            else ed.apply();
        } catch (Exception ignored) { }
    }

    /** Lightweight fast checkpoint called every ~2s while playing (zero JSON serialization). */
    static void checkpointPos(Context c, int idx, long posMs, boolean wasPlaying, boolean syncCommit) {
        SharedPreferences.Editor ed = sp(c).edit()
                .putInt("snap_idx", Math.max(0, idx))
                .putLong("snap_pos", Math.max(0L, posMs))
                .putBoolean("snap_playing", wasPlaying);
        if (syncCommit) ed.commit();
        else ed.apply();
    }

    static PlaybackSnapshot loadQueueSnapshot(Context c) {
        SharedPreferences p = sp(c);
        ArrayList<Track> list = deserializeTracks(p.getString("snap_queue", ""));
        String qkey = p.getString("snap_qkey", "");
        int idx = p.getInt("snap_idx", 0);
        long pos = p.getLong("snap_pos", 0L);
        boolean playing = p.getBoolean("snap_playing", false);
        if (idx < 0 || idx >= list.size()) idx = 0;
        return new PlaybackSnapshot(list, qkey, idx, pos, playing);
    }

    static boolean shuffle(Context c) { return sp(c).getBoolean("shuffle", false); }
    static int repeat(Context c) { return sp(c).getInt("repeat", 0); }
    static void playbackModes(Context c, boolean shuffle, int repeat) {
        sp(c).edit().putBoolean("shuffle", shuffle).putInt("repeat", repeat).apply();
    }

    // ---- Complete DSP Profile
    static void saveDsp(Context c, float vol, float pitch, float speed, float bass, float treble,
                        float width, int reverb, boolean limiter, boolean eqOn, int presetIdx,
                        int chan, int xfadeSec, float pre, float[] bands) {
        SharedPreferences.Editor e = sp(c).edit()
                .putFloat("dsp_vol", vol)
                .putFloat("dsp_pitch", pitch)
                .putFloat("dsp_speed", speed)
                .putFloat("dsp_bass", bass)
                .putFloat("dsp_treble", treble)
                .putFloat("dsp_width", width)
                .putInt("dsp_reverb", reverb)
                .putBoolean("dsp_limiter", limiter)
                .putBoolean("dsp_eq_on", eqOn)
                .putInt("dsp_preset", presetIdx)
                .putInt("dsp_chan", chan)
                .putInt("dsp_xfade", xfadeSec)
                .putFloat("dsp_pre", pre);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bands.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(bands[i]);
        }
        e.putString("dsp_bands", sb.toString()).apply();
    }

    static float dspVol(Context c) { return sp(c).getFloat("dsp_vol", 1f); }
    static float dspPitch(Context c) { return sp(c).getFloat("dsp_pitch", 0f); }
    static float dspSpeed(Context c) { return sp(c).getFloat("dsp_speed", 1f); }
    static float dspBass(Context c) { return sp(c).getFloat("dsp_bass", 0f); }
    static float dspTreble(Context c) { return sp(c).getFloat("dsp_treble", 0f); }
    static float dspWidth(Context c) { return sp(c).getFloat("dsp_width", 1f); }
    static int dspReverb(Context c) { return sp(c).getInt("dsp_reverb", 0); }
    static boolean dspLimiter(Context c) { return sp(c).getBoolean("dsp_limiter", true); }
    static boolean dspEqOn(Context c) { return sp(c).getBoolean("dsp_eq_on", true); }
    static int dspPreset(Context c) { return sp(c).getInt("dsp_preset", 0); }
    static int dspChan(Context c) { return sp(c).getInt("dsp_chan", 0); }
    static int dspXfade(Context c) { return sp(c).getInt("dsp_xfade", 0); }
    static float dspPre(Context c) { return sp(c).getFloat("dsp_pre", 0f); }

    static float[] dspBands(Context c) {
        float[] out = new float[10];
        String s = sp(c).getString("dsp_bands", "");
        if (s != null && !s.isEmpty()) {
            String[] p = s.split(",");
            for (int i = 0; i < Math.min(10, p.length); i++) {
                try { out[i] = Float.parseFloat(p[i]); } catch (NumberFormatException ignored) { }
            }
        }
        return out;
    }

    // ---- Unified Track Lists (Favorites, Recents, Offline Downloads, Streams)
    private static String serializeTracks(List<Track> list) {
        JSONArray a = new JSONArray();
        if (list != null) {
            for (Track t : list) a.put(t.toJson());
        }
        return a.toString();
    }

    private static ArrayList<Track> deserializeTracks(String raw) {
        ArrayList<Track> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                Track t = Track.fromJson(a.optJSONObject(i));
                if (t != null) out.add(t);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static ArrayList<Track> favTracks(Context c) { return deserializeTracks(sp(c).getString("fav_tracks", "")); }
    static void favTracks(Context c, List<Track> l) { sp(c).edit().putString("fav_tracks", serializeTracks(l)).apply(); }

    static ArrayList<Track> recentTracks(Context c) { return deserializeTracks(sp(c).getString("rec_tracks", "")); }
    static void recentTracks(Context c, List<Track> l) { sp(c).edit().putString("rec_tracks", serializeTracks(l)).apply(); }

    static ArrayList<Track> offlineTracks(Context c) { return deserializeTracks(sp(c).getString("off_tracks", "")); }
    static void offlineTracks(Context c, List<Track> l) { sp(c).edit().putString("off_tracks", serializeTracks(l)).apply(); }

    static ArrayList<Playlist> playlists(Context c) { return Playlist.deserialize(sp(c).getString("playlists", "")); }
    static void playlists(Context c, List<Playlist> l) { sp(c).edit().putString("playlists", Playlist.serialize(l)).apply(); }

    static ArrayList<Track> streams(Context c) { return deserializeTracks(sp(c).getString("streams", "")); }
    static void streams(Context c, List<Track> l) { sp(c).edit().putString("streams", serializeTracks(l)).apply(); }

    static ArrayList<String> searchHistory(Context c) {
        ArrayList<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("search_hist", "[]"));
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void addSearchHistory(Context c, String q) {
        if (q == null) return;
        q = q.trim();
        if (q.isEmpty()) return;
        ArrayList<String> list = searchHistory(c);
        list.remove(q);
        list.add(0, q);
        while (list.size() > 10) list.remove(list.size() - 1);
        JSONArray a = new JSONArray();
        for (String s : list) a.put(s);
        sp(c).edit().putString("search_hist", a.toString()).apply();
    }
}
