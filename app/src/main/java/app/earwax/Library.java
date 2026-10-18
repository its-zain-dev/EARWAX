package app.earwax;

import android.content.Context;
import android.database.Cursor;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;

/** Scans MediaStore for every music file on the phone (internal + SD card). */
final class Library {
    static List<Track> scan(Context c) {
        ArrayList<Track> out = new ArrayList<>();
        try (Cursor q = c.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                new String[]{"_id", "title", "artist", "duration"}, "is_music!=0 AND duration>=15000", null, "title_key")) {
            if (q != null) while (q.moveToNext()) out.add(new Track(q.getLong(0), q.getString(1), q.getString(2), q.getLong(3)));
        } catch (Exception ignored) { }
        return out;
    }
}
