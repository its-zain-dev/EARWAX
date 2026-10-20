package app.earwax;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tiny SharedPreferences wrapper: tour flag, popup size/position, favourites, recents, saved streams. */
final class Prefs {
    private static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences("earwax", Context.MODE_PRIVATE); }

    static boolean tourSeen(Context c) { return sp(c).getBoolean("tour", false); }
    static void tourSeen(Context c, boolean v) { sp(c).edit().putBoolean("tour", v).apply(); }

    static float popupScale(Context c) { return sp(c).getFloat("pscale", 1f); }
    static void popupScale(Context c, float v) { sp(c).edit().putFloat("pscale", v).apply(); }
    static String popupName(Context c) { return sp(c).getString("pname", "whale"); }
    static void popupName(Context c, String n) { sp(c).edit().putString("pname", n).apply(); }
    static int popupX(Context c, int def) { return sp(c).getInt("px", def); }
    static int popupY(Context c, int def) { return sp(c).getInt("py", def); }
    static void popupPos(Context c, int x, int y) { sp(c).edit().putInt("px", x).putInt("py", y).apply(); }

    private static List<Long> longs(String s) {
        ArrayList<Long> out = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String p : s.split(",")) { try { out.add(Long.parseLong(p)); } catch (NumberFormatException ignored) { } }
        return out;
    }
    private static String join(List<Long> l) { StringBuilder b = new StringBuilder(); for (Long v : l) { if (b.length() > 0) b.append(','); b.append(v); } return b.toString(); }

    static Set<Long> favs(Context c) { return new HashSet<>(longs(sp(c).getString("favs", ""))); }
    static void favs(Context c, Set<Long> s) { sp(c).edit().putString("favs", join(new ArrayList<>(s))).apply(); }
    static ArrayList<Long> recents(Context c) { return new ArrayList<>(longs(sp(c).getString("recent", ""))); }
    static void recents(Context c, List<Long> l) { sp(c).edit().putString("recent", join(l)).apply(); }

    static ArrayList<Track> streams(Context c) {
        ArrayList<Track> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("streams", "[]"));
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); out.add(Track.stream(o.getString("n"), o.optString("s", ""), o.getString("u"))); }
        } catch (Exception ignored) { }
        return out;
    }
    static void streams(Context c, List<Track> l) {
        JSONArray a = new JSONArray();
        try { for (Track t : l) a.put(new JSONObject().put("n", t.title).put("s", t.artist).put("u", t.url)); } catch (Exception ignored) { }
        sp(c).edit().putString("streams", a.toString()).apply();
    }
}
