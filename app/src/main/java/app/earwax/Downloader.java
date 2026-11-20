package app.earwax;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * EARWAX 4.0 Offline Audio Downloader for YouTube Music Tracks.
 * Streams direct AAC/Opus audio cleanly to app-scoped Music/EARWAX_Offline storage
 * so saved songs play instantaneously with zero network usage and survive device freezes.
 */
final class Downloader {
    interface Callback {
        void onDone(Track offlineTrack, String error);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> ACTIVE = Collections.synchronizedSet(new HashSet<>());

    static File offlineDir(Context c) {
        File base = c.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = c.getFilesDir();
        File dir = new File(base, "EARWAX_Offline");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File fileFor(Context c, String ytId) {
        if (ytId == null || ytId.isEmpty()) return null;
        String safe = ytId.replaceAll("[^A-Za-z0-9_-]", "");
        return new File(offlineDir(c), safe + ".m4a");
    }

    static boolean isDownloaded(Context c, Track t) {
        if (t == null || t.ytId == null || t.ytId.isEmpty()) return false;
        File f = fileFor(c, t.ytId);
        return f != null && f.exists() && f.length() > 4096;
    }

    static boolean isDownloading(String ytId) {
        return ytId != null && ACTIVE.contains(ytId);
    }

    static void download(Context c, Track track, Callback cb) {
        if (track == null || track.ytId == null || track.ytId.isEmpty()) {
            MAIN.post(() -> cb.onDone(null, "CANNOT DOWNLOAD THIS TRACK"));
            return;
        }
        final Context app = c.getApplicationContext();
        final String ytId = track.ytId;
        if (!ACTIVE.add(ytId)) {
            MAIN.post(() -> cb.onDone(null, "ALREADY DOWNLOADING..."));
            return;
        }

        POOL.execute(() -> {
            File dest = fileFor(app, ytId);
            File tmp = new File(dest.getParentFile(), ytId + ".part");
            try {
                YtMusic.ResolvedStream rs = YtMusic.resolveAudioSync(ytId, true);
                if (rs == null || rs.url == null || rs.url.isEmpty()) {
                    MAIN.post(() -> cb.onDone(null, "STREAM UNAVAILABLE"));
                    return;
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(rs.url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", rs.userAgent);
                conn.setRequestProperty("Accept", "*/*");
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } finally {
                    conn.disconnect();
                }
                if (tmp.length() < 4096) {
                    tmp.delete();
                    MAIN.post(() -> cb.onDone(null, "DOWNLOAD INCOMPLETE"));
                    return;
                }
                if (dest.exists()) dest.delete();
                if (!tmp.renameTo(dest)) {
                    MAIN.post(() -> cb.onDone(null, "STORAGE ERROR"));
                    return;
                }
                Track offline = Track.offlineYtm(track, dest.getAbsolutePath());
                MAIN.post(() -> cb.onDone(offline, null));
            } catch (Exception e) {
                if (tmp.exists()) tmp.delete();
                MAIN.post(() -> cb.onDone(null, "DOWNLOAD FAILED"));
            } finally {
                ACTIVE.remove(ytId);
            }
        });
    }

    static boolean deleteOffline(Context c, Track t) {
        if (t == null || t.ytId == null || t.ytId.isEmpty()) return false;
        File f = fileFor(c, t.ytId);
        return f != null && f.exists() && f.delete();
    }
}
