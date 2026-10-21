package app.earwax;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

/** One playable thing: a file from MediaStore, or an internet stream (url != null). */
final class Track {
    final long id, dur, albumId;
    final String title, artist, album, url;

    Track(long id, String title, String artist, String album, long albumId, long dur) {
        this.id = id; this.dur = dur; this.albumId = albumId; this.url = null;
        this.title = title == null || title.isEmpty() ? "?" : title;
        this.artist = artist == null || artist.equals("<unknown>") ? "" : artist;
        this.album = album == null || album.equals("<unknown>") ? "" : album;
    }

    private Track(String name, String sub, String url) {
        this.id = -1; this.dur = 0; this.albumId = -1; this.url = url; this.title = name; this.artist = sub; this.album = "";
    }

    static Track stream(String name, String sub, String url) { return new Track(name, sub == null ? "" : sub, url); }

    boolean stream() { return url != null; }
    Uri uri() { return stream() ? Uri.parse(url) : ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id); }
    Uri artUri() { return ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId); }
    String key() { return stream() ? url : Long.toString(id); }
    String label() { return artist.isEmpty() ? title : artist + " - " + title; }
}
