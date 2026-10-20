package app.earwax;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import java.util.List;

/** Hosts the 3-D bust. Asks for permissions, starts the player service, scans the library. */
public class MainActivity extends Activity {
    private StageView stage;
    private boolean booted;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        stage = new StageView(this);
        setContentView(stage);
        getWindow().setStatusBarColor(0xff10141c);
        getWindow().setNavigationBarColor(0xff10141c);
        stage.post(() -> getWindow().setBackgroundDrawable(new ColorDrawable(0xff10141c)));   // drop the splash bitmap: less overdraw
        String[] need = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        boolean ok = true;
        for (String p : need) ok &= checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        if (ok) boot(); else requestPermissions(need, 1);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handle(i); }
    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) { boot(); }
    @Override public void onBackPressed() { moveTaskToBack(true); }   // keep playing, just hide

    /** The notification's popup button lands here only when the overlay permission is still missing. */
    private void handle(Intent i) {
        if (i == null || !"popup".equals(i.getAction())) return;
        if (!Settings.canDrawOverlays(this)) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        else PlayerService.with(() -> PlayerService.I.togglePopup());
    }

    private void boot() {
        if (booted) return; booted = true;
        ContextCompat.startForegroundService(this, new Intent(this, PlayerService.class));
        new Thread(() -> {
            List<Track> t = Library.scan(this);
            runOnUiThread(() -> PlayerService.with(() -> { PlayerService.I.setLibrary(t); stage.invalidate(); }));
        }).start();
    }
}
