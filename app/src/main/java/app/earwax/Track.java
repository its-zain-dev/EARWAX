package app.earwax;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.File;
import java.util.Locale;
import org.json.JSONObject;

/**
 * EARWAX 4.0 Immutable Unified Track Model.
 * Supports:
 *  - SOURCE_LOCAL:    MediaStore local audio file
 *  - SOURCE_YTM:      YouTube Music online track (streamed via InnerTube, supports auto-radio & offline download)
 *  - SOURCE_OFFLINE:  Downloaded YouTube Music track stored locally for zero-data offline playback
 *  - SOURCE_RADIO:    Live internet radio stream (Shoutcast/Icecast/HLS)
 *  - SOURCE_EXTERNAL: External audio file opened via ACTION_VIEW
 *
 * Fully JSON-serializable so queues, favorites, playlists, and exact resume points persist across restarts.
 */
final class Track {
    static final int SOURCE_LOCAL = 0;
    static final int SOURCE_YTM = 1;
    static final int SOURCE_OFFLINE = 2;
    static final int SOURCE_RADIO = 3;
    static final int SOURCE_EXTERNAL = 4;

    final int source;
    final long id, dur, albumId, dateAdded, size;
    final int year, trackNo, bitrate;
    final String title, artist, album, folder, mime, url, codec, country;
    final String ytId, thumbUrl, localPath;

    // Local MediaStore constructor
    Track(long id, String title, String artist, String album, long albumId, long dur,
          String folder, String mime, int year, int trackNo, long dateAdded, long size) {
        this.source = SOURCE_LOCAL;
        this.id = id;
        this.dur = Math.max(0, dur);
        this.albumId = albumId;
        this.dateAdded = dateAdded;
        this.size = size;
        this.year = year;
        this.trackNo = trackNo;
        this.bitrate = 0;
        this.url = null;
        this.codec = parseCodec(mime);
        this.country = "";
        this.title = clean(title, "Untitled Track");
        this.artist = cleanUnknown(artist);
        this.album = cleanUnknown(album);
        this.folder = folder == null ? "" : folder;
        this.mime = mime == null ? "" : mime;
        this.ytId = "";
        this.thumbUrl = "";
        this.localPath = "";
    }

    // Internal general constructor
    private Track(int source, long id, String title, String artist, String album, long dur,
                  String url, String codec, int bitrate, String country,
                  String ytId, String thumbUrl, String localPath) {
        this.source = source;
        this.id = id;
        this.dur = Math.max(0, dur);
        this.albumId = -1;
        this.dateAdded = System.currentTimeMillis() / 1000L;
        this.size = 0;
        this.year = 0;
        this.trackNo = 0;
        this.bitrate = bitrate;
        this.url = url;
        this.codec = codec == null ? "" : codec.toUpperCase(Locale.ROOT);
        this.country = country == null ? "" : country.toUpperCase(Locale.ROOT);
        this.title = clean(title, source == SOURCE_RADIO ? "Live Stream" : "Untitled");
        this.artist = cleanUnknown(artist);
        this.album = album == null ? "" : album.trim();
        this.folder = source == SOURCE_OFFLINE ? "Offline" : "";
        this.mime = "";
        this.ytId = ytId == null ? "" : ytId.trim();
        this.thumbUrl = thumbUrl == null ? "" : thumbUrl.trim();
        this.localPath = localPath == null ? "" : localPath.trim();
    }

    static Track ytm(String ytId, String title, String artist, String album, long durMs, String thumbUrl) {
        return new Track(SOURCE_YTM, -1, title, artist, album, durMs,
                null, "YT", 128, "", ytId, thumbUrl, "");
    }

    static Track offlineYtm(Track orig, String filePath) {
        return offlineYtm(orig, filePath, "OFFLINE", 128);
    }

    static Track offlineYtm(Track orig, String filePath, String codec, int bitrate) {
        String c = (codec == null || codec.isEmpty()) ? "OFFLINE" : codec;
        return new Track(SOURCE_OFFLINE, -1, orig.title, orig.artist, orig.album, orig.dur,
                null, c, Math.max(32, bitrate), "", orig.ytId, orig.thumbUrl, filePath);
    }

    static Track stream(String name, String sub, String url) {
        return new Track(SOURCE_RADIO, -1, name, sub, "INTERNET RADIO", 0, url, "", 0, "", "", "", "");
    }

    static Track stream(String name, String sub, String url, String codec, int bitrate, String country) {
        return new Track(SOURCE_RADIO, -1, name, sub, "INTERNET RADIO", 0, url, codec, bitrate, country, "", "", "");
    }

    static Track external(String name, Uri uri) {
        return new Track(SOURCE_EXTERNAL, -1, name, "External Audio", "", 0, uri.toString(), "AUDIO", 0, "", "", "", "");
    }

    private static String clean(String s, String def) {
        if (s == null) return def;
        s = s.trim();
        return s.isEmpty() ? def : s;
    }

    private static String cleanUnknown(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.equalsIgnoreCase("<unknown>") || s.equalsIgnoreCase("unknown") ? "" : s;
    }

    private static String parseCodec(String mime) {
        if (mime == null || mime.isEmpty()) return "AUDIO";
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.contains("flac")) return "FLAC";
        if (m.contains("mpeg") || m.contains("mp3")) return "MP3";
        if (m.contains("mp4") || m.contains("aac") || m.contains("m4a")) return "AAC";
        if (m.contains("opus")) return "OPUS";
        if (m.contains("ogg") || m.contains("vorbis")) return "OGG";
        if (m.contains("wav") || m.contains("pcm")) return "WAV";
        if (m.contains("alac")) return "ALAC";
        if (m.contains("amr")) return "AMR";
        return "AUDIO";
    }

    boolean isLocal() { return source == SOURCE_LOCAL; }
    boolean isYouTube() { return source == SOURCE_YTM; }
    boolean isOfflineYtm() { return source == SOURCE_OFFLINE; }
    boolean isRadio() { return source == SOURCE_RADIO; }
    boolean isExternalContent() { return source == SOURCE_EXTERNAL; }
    boolean stream() { return source == SOURCE_RADIO || source == SOURCE_YTM || source == SOURCE_EXTERNAL; }
    boolean needsResolve() { return source == SOURCE_YTM && !hasValidLocalFile(); }

    boolean hasValidLocalFile() {
        if (localPath == null || localPath.isEmpty()) return false;
        File f = new File(localPath);
        return f.exists() && f.length() > 1024;
    }

    Uri uri() {
        if (source == SOURCE_LOCAL) {
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
        }
        if (hasValidLocalFile()) {
            return Uri.fromFile(new File(localPath));
        }
        if (url != null && !url.isEmpty()) {
            return Uri.parse(url);
        }
        if (!ytId.isEmpty()) {
            return Uri.parse("earwax-yt://" + ytId);
        }
        return Uri.EMPTY;
    }

    Uri artUri() {
        if (source == SOURCE_LOCAL && albumId > 0) {
            return ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId);
        }
        return null;
    }

    String hiResThumbUrl() {
        if (thumbUrl == null || thumbUrl.isEmpty()) {
            return ytId.isEmpty() ? "" : ("https://i.ytimg.com/vi/" + ytId + "/hqdefault.jpg");
        }
        // Upgrade Google lh3/yt3 square thumbs from w60/w120 to crisp w480
        return thumbUrl.replaceAll("=w\\d+-h\\d+", "=w480-h480");
    }

    String smallThumbUrl() {
        if (thumbUrl == null || thumbUrl.isEmpty()) {
            return ytId.isEmpty() ? "" : ("https://i.ytimg.com/vi/" + ytId + "/mqdefault.jpg");
        }
        return thumbUrl.replaceAll("=w\\d+-h\\d+", "=w160-h160");
    }

    String key() {
        switch (source) {
            case SOURCE_LOCAL: return "loc:" + id;
            case SOURCE_YTM: return "yt:" + ytId;
            case SOURCE_OFFLINE: return "yt:" + ytId; // shares identity with YTM track for favs/resume
            case SOURCE_RADIO: return "rad:" + (url == null ? title : url);
            default: return "ext:" + (url == null ? title : url);
        }
    }

    String label() { return artist.isEmpty() ? title : title + " — " + artist; }

    String subLabel() {
        if (source == SOURCE_RADIO) return artist.isEmpty() ? "LIVE STREAM" : artist;
        if (source == SOURCE_YTM) {
            if (!artist.isEmpty() && !album.isEmpty()) return artist + " • " + album;
            return !artist.isEmpty() ? artist : "YouTube Music";
        }
        if (source == SOURCE_OFFLINE) {
            return (!artist.isEmpty() ? artist + " • " : "") + "SAVED OFFLINE";
        }
        if (!artist.isEmpty() && !album.isEmpty()) return artist + " • " + album;
        return !artist.isEmpty() ? artist : (!album.isEmpty() ? album : folderName());
    }

    String folderName() {
        if (folder.isEmpty()) return "Music";
        String f = folder;
        while (f.endsWith("/") && f.length() > 1) f = f.substring(0, f.length() - 1);
        int idx = f.lastIndexOf('/');
        return idx >= 0 && idx < f.length() - 1 ? f.substring(idx + 1) : f;
    }

    String formatBadge() {
        if (source == SOURCE_YTM) return "YT MUSIC";
        if (source == SOURCE_OFFLINE) {
            if (!codec.isEmpty() && !"OFFLINE".equals(codec) && bitrate > 0) {
                return "OFFLINE • " + codec + " " + bitrate + "k";
            }
            return "OFFLINE";
        }
        if (source == SOURCE_RADIO) {
            StringBuilder sb = new StringBuilder("LIVE");
            if (!codec.isEmpty()) sb.append(" • ").append(codec);
            if (bitrate > 0) sb.append(" ").append(bitrate).append("k");
            return sb.toString();
        }
        return codec.isEmpty() ? "AUDIO" : codec;
    }

    // ------------------------------------------------------------------ JSON Persistence
    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("src", source);
            o.put("id", id);
            o.put("dur", dur);
            o.put("aid", albumId);
            o.put("da", dateAdded);
            o.put("sz", size);
            o.put("yr", year);
            o.put("tn", trackNo);
            o.put("br", bitrate);
            o.put("t", title);
            o.put("ar", artist);
            o.put("al", album);
            o.put("f", folder);
            o.put("m", mime);
            if (url != null) o.put("u", url);
            o.put("c", codec);
            o.put("cc", country);
            o.put("yt", ytId);
            o.put("th", thumbUrl);
            o.put("lp", localPath);
        } catch (Exception ignored) { }
        return o;
    }

    static Track fromJson(JSONObject o) {
        if (o == null) return null;
        int src = o.optInt("src", SOURCE_LOCAL);
        if (src == SOURCE_LOCAL) {
            return new Track(
                    o.optLong("id", -1),
                    o.optString("t", "Untitled"),
                    o.optString("ar", ""),
                    o.optString("al", ""),
                    o.optLong("aid", -1),
                    o.optLong("dur", 0),
                    o.optString("f", ""),
                    o.optString("m", ""),
                    o.optInt("yr", 0),
                    o.optInt("tn", 0),
                    o.optLong("da", 0),
                    o.optLong("sz", 0));
        }
        String u = o.has("u") ? o.optString("u", null) : null;
        return new Track(
                src,
                o.optLong("id", -1),
                o.optString("t", "Untitled"),
                o.optString("ar", ""),
                o.optString("al", ""),
                o.optLong("dur", 0),
                u,
                o.optString("c", ""),
                o.optInt("br", 0),
                o.optString("cc", ""),
                o.optString("yt", ""),
                o.optString("th", ""),
                o.optString("lp", ""));
    }
}
