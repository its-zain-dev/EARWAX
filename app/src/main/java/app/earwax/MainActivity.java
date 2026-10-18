package app.earwax;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.core.content.ContextCompat;
import java.util.List;

/** Hosts the 3-D head stage. Asks for permissions, starts the player service, scans the library. */
public class MainActivity extends Activity {
    private StageView stage;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        stage = new StageView(this);
        setContentView(stage);
        getWindow().setStatusBarColor(0xff10141c);
        getWindow().setNavigationBarColor(0xff10141c);
        String[] need = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        boolean ok = true;
        for (String p : need) ok &= checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        if (ok) boot(); else requestPermissions(need, 1);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) { boot(); }
    @Override public void onBackPressed() { moveTaskToBack(true); }   // keep playing, just hide

    private void boot() {
        ContextCompat.startForegroundService(this, new Intent(this, PlayerService.class));
        new Thread(() -> {
            List<Track> t = Library.scan(this);
            runOnUiThread(() -> PlayerService.with(() -> { PlayerService.I.setLibrary(t); stage.refresh(); }));
        }).start();
    }
}
