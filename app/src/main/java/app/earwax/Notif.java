package app.earwax;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.RemoteViews;
import androidx.core.app.NotificationCompat;

/** The 3-D "strip" notification: one rendered bitmap with transparent buttons laid over the artwork. */
final class Notif {
    static Notification build(PlayerService s) {
        Hud x = s.hud();
        Bitmap art = Bitmap.createBitmap(s.strip.w, s.strip.h, Bitmap.Config.ARGB_8888);
        s.strip.hud(new Canvas(art), x, true);
        RemoteViews big = new RemoteViews(s.getPackageName(), R.layout.notif_big), small = new RemoteViews(s.getPackageName(), R.layout.notif_small);
        for (RemoteViews rv : new RemoteViews[]{big, small}) {
            rv.setImageViewBitmap(R.id.art, art);
            rv.setOnClickPendingIntent(R.id.b_prev, svc(s, "prev"));
            rv.setOnClickPendingIntent(R.id.b_play, svc(s, "play"));
            rv.setOnClickPendingIntent(R.id.b_next, svc(s, "next"));
            rv.setOnClickPendingIntent(R.id.b_close, svc(s, "close"));
            rv.setOnClickPendingIntent(R.id.b_app, app(s));
        }
        return new NotificationCompat.Builder(s, "ew").setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(x.title).setContentText(x.artist)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small).setCustomBigContentView(big)
                .setOngoing(x.playing).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setContentIntent(app(s))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build();
    }

    private static PendingIntent svc(PlayerService s, String a) {
        return PendingIntent.getService(s, a.hashCode(), new Intent(s, PlayerService.class).setAction(a), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private static PendingIntent app(PlayerService s) {
        return PendingIntent.getActivity(s, 7, new Intent(s, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
