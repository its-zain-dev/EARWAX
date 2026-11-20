package app.earwax;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Full-featured MediaStore audio library scanner with folder extraction, codec/year/size metadata,
 * multi-mode sorting, and external audio file URI resolution.
 */
final class Library {
    static final String[] SORT_NAMES = {"A-Z", "ARTIST", "ALBUM", "TIME", "NEW"};

    static List<Track> scan(Context c) {
        ArrayList<Track> out = new ArrayList<>();
        String folderCol = Build.VERSION.SDK_INT >= 29
                ? MediaStore.Audio.Media.RELATIVE_PATH
                : MediaStore.Audio.Media.DATA;
        String[] proj = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                folderCol,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.SIZE
        };
        try (Cursor q = c.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                proj,
                "is_music!=0 AND duration>=10000",
                null,
                MediaStore.Audio.Media.TITLE_KEY)) {
            if (q != null) {
                while (q.moveToNext()) {
                    String rawFolder = q.getString(6);
                    if (Build.VERSION.SDK_INT < 29 && rawFolder != null) {
                        int slash = rawFolder.lastIndexOf('/');
                        rawFolder = slash > 0 ? rawFolder.substring(0, slash) : rawFolder;
                    }
                    out.add(new Track(
                            q.getLong(0),
                            q.getString(1),
                            q.getString(2),
                            q.getString(3),
                            q.getLong(4),
                            q.getLong(5),
                            rawFolder,
                            q.getString(7),
                            q.getInt(8),
                            q.getInt(9),
                            q.getLong(10),
                            q.getLong(11)));
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    static void sort(List<Track> list, int mode) {
        if (list == null || list.size() < 2) return;
        Comparator<Track> cmp;
        switch (mode) {
            case 1: // Artist
                cmp = (a, b) -> {
                    int r = a.artist.compareToIgnoreCase(b.artist);
                    return r != 0 ? r : a.title.compareToIgnoreCase(b.title);
                };
                break;
            case 2: // Album
                cmp = (a, b) -> {
                    int r = a.album.compareToIgnoreCase(b.album);
                    if (r != 0) return r;
                    int tr = Integer.compare(a.trackNo, b.trackNo);
                    return tr != 0 ? tr : a.title.compareToIgnoreCase(b.title);
                };
                break;
            case 3: // Duration (longest first)
                cmp = (a, b) -> Long.compare(b.dur, a.dur);
                break;
            case 4: // Recently added (newest first)
                cmp = (a, b) -> Long.compare(b.dateAdded, a.dateAdded);
                break;
            default: // Title A-Z
                cmp = (a, b) -> a.title.compareToIgnoreCase(b.title);
                break;
        }
        Collections.sort(list, cmp);
    }

    /** Resolves an external audio file tapped in a file manager into a playable Track. */
    static Track fromExternalUri(Context c, Uri uri) {
        String name = "Audio File";
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (q != null && q.moveToFirst()) {
                    String n = q.getString(0);
                    if (n != null && !n.trim().isEmpty()) name = stripExt(n.trim());
                }
            } catch (Exception ignored) { }
        } else if (uri.getLastPathSegment() != null) {
            name = stripExt(uri.getLastPathSegment());
        }
        return Track.external(name, uri);
    }

    private static String stripExt(String s) {
        int dot = s.lastIndexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
