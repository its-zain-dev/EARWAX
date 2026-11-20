package app.earwax;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * EARWAX 4.0 Unified User Playlist.
 * Supports mixing Local MediaStore songs, YouTube Music tracks, Offline tracks, and Radio stations
 * cleanly inside the same playlist with full JSON persistence.
 */
final class Playlist {
    final long id;
    String name;
    final ArrayList<Track> tracks = new ArrayList<>();

    Playlist(long id, String name) {
        this.id = id;
        this.name = name == null || name.trim().isEmpty() ? "Playlist" : name.trim();
    }

    boolean contains(String trackKey) {
        if (trackKey == null) return false;
        for (Track t : tracks) if (t.key().equals(trackKey)) return true;
        return false;
    }

    boolean add(Track t) {
        if (t == null || contains(t.key())) return false;
        tracks.add(t);
        return true;
    }

    boolean remove(String trackKey) {
        if (trackKey == null) return false;
        for (int i = tracks.size() - 1; i >= 0; i--) {
            if (tracks.get(i).key().equals(trackKey)) {
                tracks.remove(i);
                return true;
            }
        }
        return false;
    }

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            JSONArray arr = new JSONArray();
            for (Track t : tracks) arr.put(t.toJson());
            o.put("tracks", arr);
        } catch (Exception ignored) { }
        return o;
    }

    static Playlist fromJson(JSONObject o) {
        Playlist p = new Playlist(o.optLong("id", System.currentTimeMillis()), o.optString("name", "Playlist"));
        JSONArray arr = o.optJSONArray("tracks");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Track t = Track.fromJson(arr.optJSONObject(i));
                if (t != null) p.tracks.add(t);
            }
        }
        return p;
    }

    static String serialize(List<Playlist> list) {
        JSONArray a = new JSONArray();
        for (Playlist p : list) a.put(p.toJson());
        return a.toString();
    }

    static ArrayList<Playlist> deserialize(String raw) {
        ArrayList<Playlist> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(fromJson(o));
            }
        } catch (Exception ignored) { }
        return out;
    }
}
