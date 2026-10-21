package app.earwax;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Owns everything that must outlive the screen: ExoPlayer + DSP chain, the system MediaSession, the notification and the
 * floating popup. UI classes only talk to this object.
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

    ExoPlayer pl; Dsp dsp; PopupView pv; String pvName = "";
    List<Track> lib = new ArrayList<>(), queue = new ArrayList<>();
    String queueKey = "", err = "";
    int tool, chan, sleepMin, libVer, favVer, recVer; float vol = 1, pitch, speed = 1, sleepF;
    final Set<Long> favs = new HashSet<>(); final ArrayList<Long> recents = new ArrayList<>();
    MediaSession ms;
    private NotificationManager nm;
    private Bitmap art; private String artKey = ""; private int viewers, fails;
    private final Hud hud = new Hud();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable refreshNotif = () -> { if (nm != null) nm.notify(1, Notif.build(this)); }, sleepStop = () -> { pl.pause(); sleepMin = 0; };

    @Override public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("ew", getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW));
        favs.addAll(Prefs.favs(this)); recents.addAll(Prefs.recents(this));
        dsp = new Dsp();
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this) {
            @Override protected AudioSink buildAudioSink(Context c, boolean f32, boolean params) {
                return new DefaultAudioSink.Builder(c).setAudioProcessors(new AudioProcessor[]{dsp}).build();
            }
        };
        pl = new ExoPlayer.Builder(this, rf).setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true).build();
        pl.addListener(new Player.Listener() {
            @Override public void onEvents(Player p, Player.Events e) { h.removeCallbacks(refreshNotif); h.postDelayed(refreshNotif, 80); }
            @Override public void onMediaItemTransition(MediaItem m, int reason) { onTrackChanged(); }
            @Override public void onIsPlayingChanged(boolean playing) { if (playing) { fails = 0; err = ""; } }
            @Override public void onPlayerError(PlaybackException e) {
                Track t = current();
                if (t != null && t.stream()) { err = "STREAM ERROR"; return; }          // a dead radio link: say so, do not skip around
                if (++fails > 5) { pl.pause(); err = "PLAYBACK ERROR"; fails = 0; return; }
                pl.seekToNextMediaItem(); pl.prepare();
            }
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
            case "popup": togglePopup(); break;
            case "close": pl.pause(); closePopup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); break;
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onDestroy() { I = null; h.removeCallbacksAndMessages(null); closePopup(); ms.release(); pl.release(); super.onDestroy(); }

    // ------------------------------------------------------------------ who is watching (the FFT only runs for visible screens)
    void viewer(boolean on) { viewers = Math.max(0, viewers + (on ? 1 : -1)); dsp.want = viewers > 0; }

    // ------------------------------------------------------------------ library + queue
    private static ArrayList<MediaItem> items(List<Track> l) {
        ArrayList<MediaItem> it = new ArrayList<>(l.size());
        for (Track k : l) it.add(new MediaItem.Builder().setMediaId(k.key()).setUri(k.uri())
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(k.title).setArtist(k.artist.isEmpty() ? null : k.artist).setAlbumTitle(k.album.isEmpty() ? null : k.album).build()).build());
        return it;
    }

    void setLibrary(List<Track> t) {
        lib = t; libVer++;
        if (t.isEmpty() || pl.getMediaItemCount() != 0) return;
        queue = new ArrayList<>(t); queueKey = "all|"; pl.setMediaItems(items(queue)); pl.prepare();
    }

    /** Play `list[idx]`; the list becomes the queue (so next/prev stay inside the album, favourites, radio list...). */
    void playList(List<Track> list, int idx, String key) {
        if (idx < 0 || idx >= list.size()) return;
        err = ""; pl.setWakeMode(list.get(idx).stream() ? C.WAKE_MODE_NETWORK : C.WAKE_MODE_LOCAL);   // wifi lock only while streaming
        if (key.equals(queueKey) && queue.size() == list.size() && pl.getMediaItemCount() == list.size()) { pl.seekTo(idx, 0); pl.prepare(); pl.play(); return; }
        queue = new ArrayList<>(list); queueKey = key;
        pl.setMediaItems(items(queue), idx, 0); pl.prepare(); pl.play();
    }

    void playStream(Track t) { ArrayList<Track> one = new ArrayList<>(); one.add(t); playList(one, 0, "stream|" + t.url); }

    Track current() { int i = pl.getCurrentMediaItemIndex(); return i >= 0 && i < queue.size() ? queue.get(i) : null; }

    private void onTrackChanged() {
        Track t = current(); if (t == null) return;
        if (!t.stream()) { recents.remove(Long.valueOf(t.id)); recents.add(0, t.id); while (recents.size() > 60) recents.remove(recents.size() - 1); Prefs.recents(this, recents); recVer++; }
        final String k = t.key(); if (k.equals(artKey)) return;
        artKey = k; art = null;
        new Thread(() -> { Bitmap b = Art.load(getApplicationContext(), t); h.post(() -> { if (k.equals(artKey)) { art = b; h.removeCallbacks(refreshNotif); h.post(refreshNotif); } }); }, "art").start();
    }

    Bitmap art() { return art; }
    boolean fav(Track t) { return t != null && !t.stream() && favs.contains(t.id); }
    void toggleFav(Track t) { if (t == null || t.stream()) return; if (!favs.remove(t.id)) favs.add(t.id); Prefs.favs(this, favs); favVer++; }

    void toggle() {
        if (pl.getMediaItemCount() == 0) return;
        if (pl.getPlayWhenReady() && pl.getPlaybackState() != Player.STATE_ENDED && pl.getPlaybackState() != Player.STATE_IDLE) pl.pause();
        else { err = ""; if (pl.getPlaybackState() == Player.STATE_IDLE) pl.prepare(); if (pl.getPlaybackState() == Player.STATE_ENDED) pl.seekTo(0, 0); pl.play(); }
    }
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
    /** d = fraction of the full range (the popup knob / dial turns this). */
    void adjustTool(float d) {
        switch (tool) {
            case 0: setVol(vol + d); break; case 1: setPitch(pitch + d * 12); break; case 2: setSpeed(speed + d * 1.5f); break;
            case 3: setBass(dsp.bass + d * 20); break; case 4: setTreble(dsp.treble + d * 20); break;
            default: sleepF = cl(sleepF + d * 90, 0, 90); int m = Math.round(sleepF / 5f) * 5; if (m != sleepMin) setSleep(m);
        }
    }

    /** Fills and returns the shared Hud (main thread only). */
    Hud hud() {
        Hud x = hud; Track t = current(); MediaMetadata m = pl.getMediaMetadata();
        if (t != null) {
            x.title = err.isEmpty() ? (m.title != null ? m.title.toString() : t.title) : err;
            x.artist = m.artist != null ? m.artist.toString() : t.artist;
        } else { x.title = lib.isEmpty() ? "NO MUSIC FOUND" : "SELECT A TRACK"; x.artist = ""; }
        x.pos = Math.max(0, pl.getCurrentPosition()); x.dur = Math.max(0, pl.getDuration());
        int st = pl.getPlaybackState();
        x.playing = pl.getPlayWhenReady() && st != Player.STATE_ENDED && st != Player.STATE_IDLE;
        x.buffering = pl.getPlayWhenReady() && st == Player.STATE_BUFFERING; x.live = t != null && t.stream(); x.fav = fav(t);
        System.arraycopy(dsp.bars, 0, x.bars, 0, 32);
<<<<<<< HEAD
        x.beat(dsp.band, x.playing);
=======
        float b = 0; for (int i = 0; i < 6; i++) b += x.bars[i];
        x.bass = x.playing ? Math.min(1f, b / 6f * 1.5f) : 0f;
>>>>>>> 4460531ec2b35e77ddd0b879a2aafe8e2800ba10
        x.tool = TOOLS[tool]; x.toolText = toolText(); x.toolNorm = toolNorm();
        return x;
    }

    // ------------------------------------------------------------------ floating popup
    void cyclePopup() {   // none -> whale -> orb -> none
        String n = pvName; boolean open = pv != null; closePopup();
        if (!open) openPopup("whale"); else if (n.equals("whale")) openPopup("orb");
    }
    void togglePopup() { if (pv != null) closePopup(); else openPopup(Prefs.popupName(this)); }
    void openPopup(String n) {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(new Intent(this, MainActivity.class).setAction("popup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return;
        }
        if (pv != null) closePopup();
        pvName = n; Prefs.popupName(this, n); pv = new PopupView(this, n);
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
