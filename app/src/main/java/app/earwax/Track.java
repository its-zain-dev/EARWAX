package app.earwax;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

/** One audio file found on the device. */
final class Track {
    final long id, dur;
    final String title, artist;

    Track(long id, String title, String artist, long dur) {
        this.id = id; this.dur = dur;
        this.title = title == null ? "?" : title;
        this.artist = artist == null || artist.equals("<unknown>") ? "" : artist;
    }

    Uri uri() { return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id); }
    String label() { return artist.isEmpty() ? title : artist + " - " + title; }
}
