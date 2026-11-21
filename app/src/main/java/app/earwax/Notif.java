package app.earwax;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.provider.Settings;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaStyleNotificationHelper;

/**
 * Next-gen Cyber-Chrome MediaStyle notification synced with the active Theme & album cover.
 * Actions: Prev, Play/Pause, Next, Floating Popup Toggle, Clean Exit.
 *
 * Uses PendingIntent.getForegroundService() on API 26+ so transport buttons can ALWAYS
 * wake and restart PlayerService even if Android's low-memory killer stopped the background process.
 */
@UnstableApi
final class Notif {
    static Notification build(PlayerService s) {
        Hud x = s.hud();
        Bitmap art = s.artFramed();
        String sub = null;
        if (x.live) sub = x.badge.isEmpty() ? "LIVE STREAM" : x.badge;
        else if (x.sleepSec > 0) sub = "SLEEP " + ((x.sleepSec + 59) / 60) + "m";
        else if (!x.badge.isEmpty()) sub = x.badge;

        NotificationCompat.Builder b = new NotificationCompat.Builder(s, "ew")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(x.title)
                .setContentText(x.artist.isEmpty() ? s.getString(R.string.app_name) : x.artist)
                .setSubText(sub)
                .setLargeIcon(art)
                .setColor(Theme.get().notifBg)
                .setColorized(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(x.playing)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(app(s))
                .addAction(R.drawable.ic_prev, s.getString(R.string.act_prev), fgSvc(s, "prev"))
                .addAction(x.playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        s.getString(x.playing ? R.string.act_pause : R.string.act_play), fgSvc(s, "play"))
                .addAction(R.drawable.ic_next, s.getString(R.string.act_next), fgSvc(s, "next"))
                .addAction(R.drawable.ic_popup, s.getString(R.string.act_popup), popup(s))
                .addAction(R.drawable.ic_close, s.getString(R.string.act_close), bgSvc(s, "close"))
                .setStyle(new MediaStyleNotificationHelper.MediaStyle(s.ms).setShowActionsInCompactView(0, 1, 2))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    /**
     * Persistent non-foreground Quick-Resume notification posted if the OS destroys PlayerService
     * under heavy memory pressure (so the user can always pull down the shade and resume EARWAX!).
     */
    static Notification buildStandby(PlayerService s) {
        Track cur = s.current();
        String title = (cur != null && !cur.title.isEmpty()) ? cur.title : s.getString(R.string.app_name);
        String artist = (cur != null && !cur.artist.isEmpty()) ? cur.artist : "Tap to resume EARWAX";
        Bitmap art = s.artFramed();

        return new NotificationCompat.Builder(s, "ew")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(artist)
                .setSubText("TAP TO RESUME")
                .setLargeIcon(art)
                .setColor(Theme.get().notifBg)
                .setColorized(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setOngoing(false)
                .setAutoCancel(false)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(app(s))
                .addAction(R.drawable.ic_play, s.getString(R.string.act_play), fgSvc(s, "play"))
                .addAction(R.drawable.ic_popup, s.getString(R.string.act_popup), popup(s))
                .addAction(R.drawable.ic_close, s.getString(R.string.act_close), bgSvc(s, "close"))
                .build();
    }

    private static PendingIntent fgSvc(PlayerService s, String a) {
        Intent i = new Intent(s, PlayerService.class).setAction(a);
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 26) {
            return PendingIntent.getForegroundService(s, a.hashCode(), i, flags);
        }
        return PendingIntent.getService(s, a.hashCode(), i, flags);
    }

    private static PendingIntent bgSvc(PlayerService s, String a) {
        return PendingIntent.getService(s, a.hashCode(),
                new Intent(s, PlayerService.class).setAction(a),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent popup(PlayerService s) {
        if (Settings.canDrawOverlays(s)) return fgSvc(s, "popup");
        return PendingIntent.getActivity(s, 12,
                new Intent(s, MainActivity.class).setAction("popup")
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent app(PlayerService s) {
        return PendingIntent.getActivity(s, 7,
                new Intent(s, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
