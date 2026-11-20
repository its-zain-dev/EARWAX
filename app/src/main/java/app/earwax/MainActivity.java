package app.earwax;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Window;
import androidx.core.content.ContextCompat;
import androidx.media3.common.util.UnstableApi;
import java.util.List;

/**
 * EARWAX 4.0 Main Activity.
 *  - Hosts the Y2K 3D Liquid-Chrome StageView
 *  - Ensures foreground PlayerService boots immediately & never loses state on return
 *  - Supports 1-tap Battery Optimization exemption for weak / aggressive-killer Android phones
 *  - Trims non-critical bitmap caches on backgrounding so audio service stays ultra-lean
 */
@UnstableApi
public class MainActivity extends Activity {
    private StageView stage;
    private boolean booted;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme.set(Prefs.theme(this));

        Window w = getWindow();
        w.setStatusBarColor(Theme.BG_DEEP);
        w.setNavigationBarColor(Theme.BG_DEEP);

        stage = new StageView(this);
        setContentView(stage);
        stage.post(() -> w.setBackgroundDrawable(new ColorDrawable(Theme.BG_DEEP)));

        String[] need = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        boolean ok = true;
        for (String p : need) ok &= checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        if (ok) boot();
        else {
            // Start foreground service right away even before local permission so YouTube Music works immediately!
            ContextCompat.startForegroundService(this, new Intent(this, PlayerService.class));
            requestPermissions(need, 1);
        }

        handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppUpdater.checkOnEnter(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (PlayerService.I != null && PlayerService.I.requestedMode >= 0) {
            int m = PlayerService.I.requestedMode;
            PlayerService.I.requestedMode = -1;
            stage.setMode(m);
        }
        if (stage != null) stage.invalidate();
        AppUpdater.onResume(this);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        boot();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            Art.trimCache();
        }
    }

    @Override
    public void onBackPressed() {
        if (stage != null && stage.handleBack()) return;
        moveTaskToBack(true);
    }

    static boolean isBatteryWhitelisted(android.content.Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static void requestBatteryWhitelist(android.content.Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if ("popup".equals(i.getAction())) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } else {
                PlayerService.with(() -> PlayerService.I.togglePopup());
            }
            return;
        }
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) {
            final Uri data = i.getData();
            PlayerService.with(() -> {
                PlayerService.I.playExternalUri(data);
                if (stage != null) stage.invalidate();
            });
        }
    }

    private void boot() {
        if (booted) return;
        booted = true;
        ContextCompat.startForegroundService(this, new Intent(this, PlayerService.class));
        new Thread(() -> {
            List<Track> t = Library.scan(getApplicationContext());
            runOnUiThread(() -> PlayerService.with(() -> {
                PlayerService.I.setLibrary(t);
                if (stage != null) stage.invalidate();
            }));
        }, "earwax-scan").start();
    }
}
