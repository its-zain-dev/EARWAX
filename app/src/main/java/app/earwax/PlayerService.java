package app.earwax;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.app.ServiceCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.session.MediaSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns everything that must outlive the screen: ExoPlayer + DSP chain, the system MediaSession,
 * the 3-D notification and the floating popup. UI classes only talk to this object.
 */
@UnstableApi
public final class PlayerService extends Service {
    static PlayerService I;
    private static final ArrayList<Runnable> WAIT = new ArrayList<>();
    /** Run now if the service is up, otherwise as soon as it starts (main thread only). */
    static void with(Runnable r) { if (I != null) r.run(); else WAIT.add(r); }

    static final String[] TOOLS = {"VOL", "PITCH", "SPEED", "BASS", "TREBLE", "SLEEP"};
    static final String[] PRESET_NAMES = {"FLAT", "ROCK", "POP", "JAZZ", "CLASSIC", "BASS", "VOCAL", "PARTY"};
    static final float[][] PRESETS = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {5, 3, -3, -5, -1, 2, 5, 6, 6, 6}, {-1, 2, 4, 5, 3, 0, -1, -1, -1, -1}, {4, 3, 1, 2, -2, -2, 0, 2, 3, 4},
        {0, 0, 0, 0, 0, 0, -4, -4, -4, -6}, {8, 6, 4, 2, 0, 0, 0, 0, 0, 0}, {-2, -3, -3, 1, 4, 4, 3, 1, 0, -1}, {6, 5, 0, 0, 0, 0, 0, 0, 5, 6}};

    ExoPlayer pl; Dsp dsp; Skin strip; PopupView pv; String pvName = "";
    List<Track> lib = new ArrayList<>();
    int tool, chan, sleepMin; float vol = 1, pitch, speed = 1, sleepF;
    private MediaSession ms; private NotificationManager nm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable refreshNotif = () -> nm.notify(1, Notif.build(this)), sleepStop = () -> { pl.pause(); sleepMin = 0; };

    @Override public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("ew", "Playback", NotificationManager.IMPORTANCE_LOW));
        dsp = new Dsp(); strip = Skin.load(this, "strip");
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this) {
            @Override protected AudioSink buildAudioSink(Context c, boolean f32, boolean params) {
                return new DefaultAudioSink.Builder(c).setAudioProcessors(new AudioProcessor[]{dsp}).build();
            }
        };
        pl = new ExoPlayer.Builder(this, rf).setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true).build();
        pl.addListener(new Player.Listener() {
            @Override public void onEvents(Player p, Player.Events e) { h.removeCallbacks(refreshNotif); h.postDelayed(refreshNotif, 80); }
            @Override public void onPlayerError(PlaybackException e) { pl.seekToNextMediaItem(); pl.prepare(); }
        });
        ms = new MediaSession.Builder(this, pl).build();   // lock screen, Bluetooth, headset buttons
        ServiceCompat.startForeground(this, 1, Notif.build(this), Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
        I = this;
        for (Runnable r : WAIT) r.run();
        WAIT.clear();
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        String a = i == null ? null : i.getAction();
        if (a != null) switch (a) {
            case "prev": prev(); break;
            case "play": toggle(); break;
            case "next": next(); break;
            case "close": pl.pause(); closePopup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); break;
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { I = null; closePopup(); ms.release(); pl.release(); super.onDestroy(); }

    // ------------------------------------------------------------------ library + transport
    void setLibrary(List<Track> t) {
        lib = t;
        if (t.isEmpty() || pl.getMediaItemCount() == t.size()) return;
        ArrayList<MediaItem> it = new ArrayList<>(t.size());
        for (Track k : t) it.add(new MediaItem.Builder().setMediaId("" + k.id).setUri(k.uri())
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(k.title).setArtist(k.artist).build()).build());
        pl.setMediaItems(it); pl.prepare();
    }
    void toggle() {
        if (pl.getMediaItemCount() == 0) return;
        if (pl.getPlayWhenReady() && pl.getPlaybackState() != Player.STATE_ENDED) pl.pause();
        else { if (pl.getPlaybackState() == Player.STATE_ENDED) pl.seekTo(0, 0); pl.play(); }
    }
    void playIndex(int i) { if (i >= 0 && i < pl.getMediaItemCount()) { pl.seekTo(i, 0); pl.play(); } }
    void prev() { pl.seekToPrevious(); pl.play(); }
    void next() { pl.seekToNext(); pl.play(); }
    void seekFrac(float f) { long d = pl.getDuration(); if (d > 0) pl.seekTo((long) (Math.max(0, Math.min(1, f)) * d)); }
    boolean shuffle() { return pl.getShuffleModeEnabled(); }
    int repeat() { return pl.getRepeatMode(); }
    void toggleShuffle() { pl.setShuffleModeEnabled(!pl.getShuffleModeEnabled()); }
    void cycleRepeat() { pl.setRepeatMode((pl.getRepeatMode() + 1) % 3); }
    void cycleMode() {   // off -> shuffle -> repeat all -> repeat one -> off
        if (shuffle()) { toggleShuffle(); pl.setRepeatMode(Player.REPEAT_MODE_ALL); }
        else if (repeat() == Player.REPEAT_MODE_OFF) toggleShuffle();
        else if (repeat() == Player.REPEAT_MODE_ALL) pl.setRepeatMode(Player.REPEAT_MODE_ONE);
        else pl.setRepeatMode(Player.REPEAT_MODE_OFF);
    }

    // ------------------------------------------------------------------ tools (real DSP, no fake sliders)
    private static float cl(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
    void setVol(float v) { vol = cl(v, 0, 1); pl.setVolume(vol); }
    private void applyPb() { pl.setPlaybackParameters(new PlaybackParameters(speed, (float) Math.pow(2, pitch / 12.0))); }
    void setPitch(float v) { pitch = cl(v, -6, 6); applyPb(); }           // independent of speed (Sonic)
    void setSpeed(float v) { speed = cl(v, 0.5f, 2f); applyPb(); }
    void setBass(float v) { dsp.bass = cl(v, -10, 10); dsp.touch(); }
    void setTreble(float v) { dsp.treble = cl(v, -10, 10); dsp.touch(); }
    void setEq(int i, float v) { v = cl(v, -15, 15); if (i == 0) dsp.pre = v; else dsp.g[i - 1] = v; dsp.touch(); }
    float eq(int i) { return i == 0 ? dsp.pre : dsp.g[i - 1]; }
    void preset(int p) { System.arraycopy(PRESETS[p], 0, dsp.g, 0, 10); dsp.touch(); }
    void cycleChan() { chan = (chan + 1) % 3; dsp.mode = chan; dsp.touch(); }
    String chanName() { return new String[]{"STEREO", "MONO L", "MONO R"}[chan]; }
    void setSleep(int m) { sleepMin = m; h.removeCallbacks(sleepStop); if (m > 0) h.postDelayed(sleepStop, m * 60000L); }
    void cycleSleep() { int[] o = {0, 15, 30, 60, 90}; int i = 0; for (int k = 0; k < o.length; k++) if (o[k] == sleepMin) i = k; setSleep(o[(i + 1) % o.length]); }

    String toolText() {
        switch (tool) {
            case 0: return Math.round(vol * 100) + "%";
            case 1: return String.format(Locale.US, "%+.1f", pitch);
            case 2: return String.format(Locale.US, "%.2fx", speed);
            case 3: return String.format(Locale.US, "%+.0fdB", dsp.bass);
            case 4: return String.format(Locale.US, "%+.0fdB", dsp.treble);
            default: return sleepMin == 0 ? "OFF" : sleepMin + "m";
        }
    }
    float toolNorm() {
        switch (tool) {
            case 0: return vol; case 1: return (pitch + 6) / 12; case 2: return (speed - 0.5f) / 1.5f;
            case 3: return (dsp.bass + 10) / 20; case 4: return (dsp.treble + 10) / 20; default: return Math.min(1, sleepMin / 90f);
        }
    }
    /** d = fraction of the full range (the popup knob drags this). */
    void adjustTool(float d) {
        switch (tool) {
            case 0: setVol(vol + d); break; case 1: setPitch(pitch + d * 12); break; case 2: setSpeed(speed + d * 1.5f); break;
            case 3: setBass(dsp.bass + d * 20); break; case 4: setTreble(dsp.treble + d * 20); break;
            default: sleepF = cl(sleepF + d * 90, 0, 90); int m = Math.round(sleepF / 5f) * 5; if (m != sleepMin) setSleep(m);
        }
    }

    Hud hud() {
        Hud x = new Hud(); MediaItem m = pl.getCurrentMediaItem();
        if (m != null) {
            CharSequence t = m.mediaMetadata.title, a = m.mediaMetadata.artist;
            x.title = t == null ? "" : t.toString(); x.artist = a == null ? "" : a.toString();
        } else x.title = lib.isEmpty() ? "NO MUSIC FOUND" : "SELECT A TRACK";
        x.pos = Math.max(0, pl.getCurrentPosition()); x.dur = Math.max(0, pl.getDuration());
        x.playing = pl.getPlayWhenReady() && pl.getPlaybackState() != Player.STATE_ENDED;
        x.bars = dsp.bars; x.tool = TOOLS[tool]; x.toolText = toolText(); x.toolNorm = toolNorm();
        return x;
    }

    // ------------------------------------------------------------------ floating popup
    void cyclePopup() {   // none -> whale -> orb -> none
        String n = pvName; boolean open = pv != null; closePopup();
        if (!open) openPopup("whale"); else if (n.equals("whale")) openPopup("orb");
    }
    void openPopup(String n) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        pvName = n; pv = new PopupView(this, n);
    }
    void closePopup() { if (pv != null) { pv.dismiss(); pv = null; } }
    void popupAction(String id) {
        switch (id) {
            case "prev": prev(); break; case "play": toggle(); break; case "next": next(); break;
            case "tool": tool = (tool + 1) % TOOLS.length; break; case "mode": cycleMode(); break;
            case "app": startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); break;
            case "close": closePopup(); break;
        }
    }
}
