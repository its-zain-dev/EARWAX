package app.earwax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Toast;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * EARWAX 4.0 Early-2000s Glossy Chrome-Glass Chest Console Renderer & Controller.
 *
 * 5 Rich Studio Modes:
 *  - Mode 0: UNIFIED HUB — 7 Tabs:
 *      0 = YT (YouTube Music Search + 8 Mood Discovery + Search History + Auto-Radio + 1-Tap Offline Download!)
 *      1 = SONGS (Local Device Library + Multi-Sort + Instant Filter)
 *      2 = BROWSE (Albums / Artists / Folders drill-down)
 *      3 = OFFLINE (Downloaded YouTube Music tracks for zero-data, freeze-proof offline play!)
 *      4 = FAVS (Unified Local + YouTube Music Favorites)
 *      5 = LISTS (Custom Mixed Local+YTM Playlists + Recently Played)
 *      6 = RADIO (Live Internet Radio + 8 Genres + Curated + Custom URL)
 *  - Mode 1: 10-BAND PARAMETRIC EQ (Live Cubic Bézier Curve + 11 Machined Chrome Faders + 12 Presets)
 *  - Mode 2: STUDIO FX & RELIABILITY (Pitch/Speed/Bass/Treble/3D Width/Vol + Reverb/Limiter/Chan/XFade/Sleep
 *            + NO-STOP Ignore-Focus + YT Auto-Radio + Battery Keep-Alive Whitelist + 5 Live Themes + 3D Tilt)
 *  - Mode 3: UP-NEXT QUEUE (Live mixed queue with instant jump & remove)
 *  - Mode 4: SYNCED LYRICS (Live LRCLIB auto-scrolling time-synced lyrics — tap any line to seek!)
 */
@UnstableApi
final class ConsoleRenderer {
    static final float ROW = 76f;
    static final float LYRIC_ROW = 54f;
    static final float TAB_H = 52f;
    static final float SUB_H = 48f;
    static final float STRIP_H = 42f;

    private static final String[] TABS = {"YT", "SONGS", "BROWSE", "OFFLINE", "FAVS", "LISTS", "RADIO"};
    private static final String[] BROWSE_SUBS = {"ALBUMS", "ARTISTS", "FOLDERS"};
    private static final String[] FX_NAMES = {"PITCH", "SPEED", "BASS", "TREBLE", "3D WIDTH", "VOLUME"};
    private static final List<String> YT_SUGGESTIONS = Arrays.asList(
            "Daft Punk", "Synthwave", "Lofi Beats", "Phonk", "Arabic Hits", "The Weeknd", "Linkin Park");

    static final class Row {
        String a = "", b = "", grp;
        Track t;
        Playlist pl;
        int ti;
        boolean group;
        boolean recentGroup;
    }

    private final Context ctx;
    private final View host;
    private final RectF pr, pc;
    private final Paint Q = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint S = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint B = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path curvePath = new Path(), areaPath = new Path(), clipPath = new Path(), tileClip = new Path();
    private final RectF tmp = new RectF();

    int mode = 0; // 0=Hub, 1=EQ, 2=FX+Reliability, 3=Queue, 4=Synced Lyrics
    int tab = 0;
    int browseSub = 0; // 0=Albums, 1=Artists, 2=Folders
    int sortMode;
    int ytMood = 0;
    int radioGenre = 0;

    String grp = null;
    Playlist activePlaylist = null;
    boolean viewingRecent = false;
    String query = "";
    String ytQuery = "";

    ArrayList<Row> rows = new ArrayList<>();
    ArrayList<Track> vt = new ArrayList<>();
    String vkey = "";
    int sLib = -1, sFav = -1, sRec = -1, sPl = -1, sQ = -1, sOff = -1;

    private ArrayList<Track> ytRes;
    private String ytMsg = "";
    private boolean ytLoading;

    private ArrayList<Track> radioRes;
    private String radioMsg = "";
    private boolean radioLoading;
    private final ArrayList<Track> savedStreams;

    // Interactive state
    int eqBand = -1;
    int fxIdx = -1;
    float scroll = 0f;
    private int lastLyricIdx = -1;
    private String lastLyricKey = "";
    private long lastUserLyricScrollMs = 0L;
    private int lastEqTapBand = -1, lastFxTapIdx = -1;
    private long lastEqTapMs = 0L, lastFxTapMs = 0L;

    ConsoleRenderer(Context ctx, View host, RectF panelRect) {
        this.ctx = ctx;
        this.host = host;
        this.pr = new RectF(panelRect);
        this.pc = new RectF(pr.left + 26, pr.top + 20, pr.right - 26, pr.bottom - 18);
        this.savedStreams = Prefs.streams(ctx);
        this.sortMode = Prefs.sortMode(ctx);
        this.tab = Math.max(0, Math.min(TABS.length - 1, Prefs.lastTab(ctx)));
    }

    boolean hasStrip() {
        return mode == 0 && (tab == 0 || tab == 2 || tab == 6);
    }

    float listTop() {
        if (mode == 3 || mode == 4) return pc.top + 58f;
        return pc.top + TAB_H + SUB_H + (hasStrip() ? STRIP_H : 0f);
    }

    float listH() { return Math.max(40f, pc.bottom - listTop()); }

    float maxScroll() {
        if (mode == 4) {
            PlayerService sv = PlayerService.I;
            int n = (sv != null && sv.currentLyrics != null) ? sv.currentLyrics.lines.size() : 0;
            return Math.max(0f, n * LYRIC_ROW - listH() + 80f);
        }
        return Math.max(0f, rows.size() * ROW - listH());
    }

    float clampScroll(float v) { return Math.max(0f, Math.min(maxScroll(), v)); }

    void noteUserScroll() {
        if (mode == 4) lastUserLyricScrollMs = System.currentTimeMillis();
    }

    boolean handleBack(PlayerService sv) {
        if (mode != 0) {
            mode = 0;
            if (sv != null) rebuild(sv, false);
            return true;
        }
        if (grp != null || activePlaylist != null || viewingRecent) {
            grp = null;
            activePlaylist = null;
            viewingRecent = false;
            if (sv != null) rebuild(sv, false);
            return true;
        }
        if (tab == 0 && !ytQuery.isEmpty()) {
            ytQuery = "";
            loadYtMood(ytMood);
            return true;
        }
        if (!query.isEmpty()) {
            query = "";
            if (sv != null) rebuild(sv, false);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ Data model
    private static boolean match(Track t, String q) {
        return q.isEmpty()
                || t.title.toLowerCase(Locale.ROOT).contains(q)
                || t.artist.toLowerCase(Locale.ROOT).contains(q)
                || t.album.toLowerCase(Locale.ROOT).contains(q)
                || t.folderName().toLowerCase(Locale.ROOT).contains(q);
    }

    private static String groupKey(Track t, int bSub) {
        String k = bSub == 0 ? t.album : (bSub == 1 ? t.artist : t.folderName());
        return k == null || k.isEmpty() ? "Unknown" : k;
    }

    void syncIfNeeded(PlayerService sv) {
        if (sv == null) return;
        if (mode == 4) {
            Track cur = sv.current();
            String ck = cur == null ? "" : cur.key();
            if (!ck.equals(sv.lyricsKey) && !sv.lyricsLoading && cur != null) {
                sv.loadLyricsForCurrent();
            }
            return;
        }
        if (mode == 3) {
            if (sQ != sv.queueVer) rebuild(sv, true);
            return;
        }
        // Auto-load YouTube Music trending right away when YT tab is active so app is NEVER empty!
        if (tab == 0 && ytRes == null && !ytLoading) {
            loadYtMood(ytMood);
        }
        if (tab == 6 && radioRes == null && !radioLoading) {
            loadRadioGenre(radioGenre);
        }
        if (sLib != sv.libVer
                || (tab == 3 && sOff != sv.offVer)
                || (tab == 4 && sFav != sv.favVer)
                || (tab == 5 && (sPl != sv.plVer || sRec != sv.recVer))) {
            // If user opened app for the first time with 0 local songs while on SONGS tab, switch smoothly to YT!
            if (sLib == -1 && sv.lib.isEmpty() && tab == 1) {
                tab = 0;
                Prefs.lastTab(ctx, 0);
                if (ytRes == null && !ytLoading) loadYtMood(0);
            }
            rebuild(sv, true);
        }
    }

    void rebuild(PlayerService sv, boolean keepScroll) {
        rows = new ArrayList<>();
        vt = new ArrayList<>();
        String q = query.toLowerCase(Locale.ROOT);
        sLib = sv.libVer; sFav = sv.favVer; sRec = sv.recVer; sPl = sv.plVer; sQ = sv.queueVer; sOff = sv.offVer;

        if (mode == 3) { // Live Queue
            vt.addAll(sv.queue);
            vkey = "queue|" + sv.queueVer;
            for (int i = 0; i < vt.size(); i++) {
                Row r = new Row();
                r.t = vt.get(i);
                r.ti = i;
                rows.add(r);
            }
            scroll = keepScroll ? clampScroll(scroll) : 0f;
            return;
        }

        switch (tab) {
            case 0: // YOUTUBE MUSIC
                if (ytRes != null) vt.addAll(ytRes);
                vkey = ytQuery.isEmpty() ? ("ytmood|" + ytMood) : ("ytq|" + ytQuery);
                break;

            case 1: // LOCAL SONGS
                for (Track t : sv.lib) if (match(t, q)) vt.add(t);
                Library.sort(vt, sortMode);
                vkey = "all|" + sortMode + "|" + q;
                break;

            case 2: // BROWSE (ALBUMS / ARTISTS / FOLDERS)
                if (grp == null) {
                    HashMap<String, int[]> cnt = new HashMap<>();
                    for (Track t : sv.lib) {
                        if (!match(t, q)) continue;
                        String k = groupKey(t, browseSub);
                        int[] n = cnt.get(k);
                        if (n == null) { n = new int[1]; cnt.put(k, n); }
                        n[0]++;
                    }
                    ArrayList<String> keys = new ArrayList<>(cnt.keySet());
                    Collections.sort(keys, String.CASE_INSENSITIVE_ORDER);
                    for (String k : keys) {
                        Row r = new Row();
                        r.group = true;
                        r.grp = k;
                        r.a = k;
                        r.b = cnt.get(k)[0] + " tracks";
                        rows.add(r);
                    }
                } else {
                    for (Track t : sv.lib) if (groupKey(t, browseSub).equals(grp) && match(t, q)) vt.add(t);
                    Library.sort(vt, browseSub == 0 ? 2 : sortMode);
                    vkey = "grp|" + browseSub + "|" + grp + "|" + q;
                }
                break;

            case 3: // OFFLINE DOWNLOADED YT MUSIC TRACKS
                for (Track t : sv.offlineList) if (match(t, q)) vt.add(t);
                vkey = "off|" + sv.offVer + "|" + q;
                break;

            case 4: // UNIFIED FAVORITES (Local + YouTube Music!)
                for (Track t : sv.favList) if (match(t, q)) vt.add(t);
                vkey = "fav|" + sv.favVer + "|" + q;
                break;

            case 5: // PLAYLISTS + RECENTLY PLAYED
                if (viewingRecent) {
                    for (Track t : sv.recentList) if (match(t, q)) vt.add(t);
                    vkey = "rec|" + sv.recVer + "|" + q;
                } else if (activePlaylist == null) {
                    // Always pin Recently Played right at top of Lists for instant access
                    Row recRow = new Row();
                    recRow.group = true;
                    recRow.recentGroup = true;
                    recRow.a = "RECENTLY PLAYED";
                    recRow.b = sv.recentList.size() + " tracks";
                    rows.add(recRow);

                    for (Playlist p : sv.playlists) {
                        if (!q.isEmpty() && !p.name.toLowerCase(Locale.ROOT).contains(q)) continue;
                        Row r = new Row();
                        r.group = true;
                        r.pl = p;
                        r.a = p.name;
                        r.b = p.tracks.size() + " tracks";
                        rows.add(r);
                    }
                } else {
                    for (Track t : activePlaylist.tracks) if (match(t, q)) vt.add(t);
                    vkey = "pl|" + activePlaylist.id + "|" + sv.plVer;
                }
                break;

            default: // LIVE RADIO
                vt.addAll(savedStreams);
                List<Track> src = radioRes != null ? radioRes : Radio.curated();
                for (Track t : src) if (!isSaved(t)) vt.add(t);
                vkey = "radio|" + radioGenre;
                break;
        }

        for (int i = 0; i < vt.size(); i++) {
            Row r = new Row();
            r.t = vt.get(i);
            r.ti = i;
            rows.add(r);
        }
        scroll = keepScroll ? clampScroll(scroll) : 0f;
    }

    private boolean isSaved(Track t) {
        if (t == null || t.url == null) return false;
        for (Track k : savedStreams) if (t.url.equals(k.url)) return true;
        return false;
    }

    void selectTab(PlayerService sv, int i) {
        tab = i;
        Prefs.lastTab(ctx, i);
        grp = null;
        activePlaylist = null;
        viewingRecent = false;
        if (i != 6) query = "";
        scroll = 0f;
        rebuild(sv, false);
        if (i == 0 && ytRes == null && !ytLoading) loadYtMood(ytMood);
        if (i == 6 && radioRes == null && !radioLoading) loadRadioGenre(radioGenre);
    }

    private void loadYtMood(int m) {
        ytMood = m;
        ytQuery = "";
        ytLoading = true;
        ytMsg = "LOADING " + YtMusic.MOODS[m] + " ON YT MUSIC...";
        host.invalidate();
        YtMusic.discover(m, (list, e) -> {
            ytLoading = false;
            ytRes = new ArrayList<>(list);
            ytMsg = e == null ? "" : e;
            PlayerService v = PlayerService.I;
            if (v != null && tab == 0) rebuild(v, false);
            host.invalidate();
        });
    }

    private void searchYt(PlayerService sv, String q) {
        ytQuery = q;
        Prefs.addSearchHistory(ctx, q);
        ytLoading = true;
        ytMsg = "SEARCHING YT MUSIC: \"" + q + "\"...";
        ytRes = new ArrayList<>();
        rebuild(sv, false);
        host.invalidate();
        YtMusic.search(q, true, (list, e) -> {
            ytLoading = false;
            ytRes = new ArrayList<>(list);
            ytMsg = e == null ? "" : e;
            if (tab == 0) rebuild(sv, false);
            host.invalidate();
        });
    }

    private void loadRadioGenre(int g) {
        radioGenre = g;
        radioLoading = true;
        radioMsg = "TUNING RADIO...";
        host.invalidate();
        Radio.genre(g, (l, e) -> {
            radioLoading = false;
            radioRes = new ArrayList<>(l);
            radioMsg = e == null ? "" : e;
            PlayerService v = PlayerService.I;
            if (v != null && tab == 6) rebuild(v, false);
            host.invalidate();
        });
    }

    // ------------------------------------------------------------------ Drawing
    void draw(Canvas c, PlayerService sv, GyroParallax gyro) {
        syncIfNeeded(sv);
        c.save();
        clipPath.reset();
        clipPath.addRoundRect(pr.left + 4, pr.top + 4, pr.right - 4, pr.bottom - 4, 46, 46, Path.Direction.CW);
        c.clipPath(clipPath);

        // Deep glossy obsidian-sapphire Y2K glass interior
        Q.setStyle(Paint.Style.FILL);
        Q.setShader(Theme.glassGrad(pr.top, pr.bottom));
        c.drawRect(pr, Q);
        Q.setShader(null);

        // Subtle curved top Y2K glass reflection sheen
        Q.setShader(Theme.y2kGlossSheen(pr.top, pr.top + 86f));
        c.drawRect(pr.left, pr.top, pr.right, pr.top + 86f, Q);
        Q.setShader(null);

        // Crisp glowing inner chrome bezel rim
        S.setStyle(Paint.Style.STROKE);
        S.setStrokeWidth(2f);
        S.setColor(Theme.alpha(Theme.primary(), 85));
        c.drawRoundRect(pr.left + 6, pr.top + 6, pr.right - 6, pr.bottom - 6, 44, 44, S);

        switch (mode) {
            case 0: drawHub(c, sv); break;
            case 1: drawEq(c, sv); break;
            case 2: drawFx(c, sv, gyro); break;
            case 3: drawQueue(c, sv); break;
            default: drawLyrics(c, sv); break;
        }
        c.restore();
    }

    /** Glossy Early-2000s Y2K Aqua/Chrome tactile pill chip. */
    private void drawChip(Canvas c, RectF r, String label, boolean hot) {
        drawChipColored(c, r, label, hot, Theme.primary());
    }

    private void drawChipColored(Canvas c, RectF r, String label, boolean hot, int accent) {
        float rad = Math.min(15f, r.height() * 0.45f);
        Q.setStyle(Paint.Style.FILL);
        if (hot) {
            Q.setShader(Theme.aquaPillGrad(r.top, r.bottom, accent));
        } else {
            Q.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xff14253e, 0xff091325, Shader.TileMode.CLAMP));
        }
        c.drawRoundRect(r, rad, rad, Q);
        Q.setShader(null);

        // Subtle Y2K glossy upper meniscus glint on pill
        Q.setColor(hot ? 0x44ffffff : 0x18ffffff);
        c.drawRoundRect(r.left + 3, r.top + 2, r.right - 3, r.centerY(), rad * 0.7f, rad * 0.7f, Q);

        S.setStyle(Paint.Style.STROKE);
        S.setStrokeWidth(hot ? 2.0f : 1.3f);
        S.setColor(hot ? Theme.lerp(accent, 0xffffffff, 0.5f) : Theme.alpha(Theme.CHROME_MID, 115));
        c.drawRoundRect(r, rad, rad, S);

        Skin.text(c, Skin.fit(label, 16.5f, r.width() - 10f, Theme.TF_BOLD),
                r.centerX(), r.centerY() + 6f, 16.5f,
                hot ? 0xff03101d : Theme.TEXT_MAIN, Paint.Align.CENTER, Theme.TF_BOLD, false);
    }

    // ---- Mode 0: UNIFIED MUSIC HUB
    private RectF chipSearch()   { float y = pc.top + TAB_H; tmp.set(pc.right - 140, y + 5, pc.right, y + SUB_H - 5); return tmp; }
    private RectF chipAction()   { float y = pc.top + TAB_H; tmp.set(pc.right - 252, y + 5, pc.right - 148, y + SUB_H - 5); return tmp; }
    private RectF chipQueueBtn() { float y = pc.top + TAB_H; tmp.set(pc.right - 348, y + 5, pc.right - 260, y + SUB_H - 5); return tmp; }
    private RectF chipLrcBtn()   { float y = pc.top + TAB_H; tmp.set(pc.right - 434, y + 5, pc.right - 356, y + SUB_H - 5); return tmp; }

    private void drawHub(Canvas c, PlayerService sv) {
        // 1. 7 Glossy Y2K Aqua Navigation Tabs
        float tw = pc.width() / TABS.length;
        float top = pc.top;
        for (int i = 0; i < TABS.length; i++) {
            float x0 = pc.left + i * tw;
            boolean on = (i == tab);
            tmp.set(x0 + 2, top + 3, x0 + tw - 2, top + TAB_H - 7);
            if (on) {
                int col = (i == 0) ? Theme.YT_BADGE : (i == 3 ? Theme.OFF_BADGE : Theme.primary());
                Q.setStyle(Paint.Style.FILL);
                Q.setShader(Theme.aquaPillGrad(tmp.top, tmp.bottom, col));
                c.drawRoundRect(tmp, 14f, 14f, Q);
                Q.setShader(null);
                S.setStyle(Paint.Style.STROKE); S.setStrokeWidth(1.6f);
                S.setColor(Theme.lerp(col, 0xffffffff, 0.55f));
                c.drawRoundRect(tmp, 14f, 14f, S);
            } else {
                Q.setStyle(Paint.Style.FILL);
                Q.setColor(0x550b182c);
                c.drawRoundRect(tmp, 14f, 14f, Q);
            }
            Skin.text(c, TABS[i], tmp.centerX(), tmp.centerY() + 6f, 16.5f,
                    on ? 0xff03101d : (i == 0 ? 0xffffa6b8 : Theme.TEXT_SUB), Paint.Align.CENTER, Theme.TF_BOLD, false);
        }

        // 2. Sub-header: Breadcrumb/count + [LRC] + [QUEUE] + [Action] + [SEARCH]
        float sy = top + TAB_H;
        String lead;
        if (tab == 0) lead = ytQuery.isEmpty() ? ("YT • " + YtMusic.MOODS[ytMood]) : ("‹ " + ytQuery);
        else if (grp != null) lead = "‹ " + grp;
        else if (activePlaylist != null) lead = "‹ " + activePlaylist.name;
        else if (viewingRecent) lead = "‹ RECENT";
        else lead = TABS[tab] + " (" + rows.size() + ")";

        float leadMax = Math.max(90f, pc.width() - 446f);
        Skin.text(c, Skin.fit(lead, 19f, leadMax, Theme.TF_BOLD),
                pc.left + 4, sy + 30f, 19f,
                (grp != null || activePlaylist != null || viewingRecent || !ytQuery.isEmpty()) ? Theme.primary() : Theme.TEXT_SUB,
                Paint.Align.LEFT, Theme.TF_BOLD, false);

        drawChip(c, chipLrcBtn(), "LRC", false);
        drawChip(c, chipQueueBtn(), "QUEUE", false);
        if (tab == 0) {
            Track cur = sv.current();
            drawChipColored(c, chipAction(), "RADIO+", cur != null && cur.isYouTube(), Theme.YT_BADGE);
        } else if (tab == 6) {
            drawChip(c, chipAction(), "+ URL", false);
        } else if (tab == 5) {
            drawChip(c, chipAction(), "+ NEW", false);
        } else {
            drawChip(c, chipAction(), Library.SORT_NAMES[sortMode], false);
        }

        if (tab == 0) {
            drawChipColored(c, chipSearch(), ytQuery.isEmpty() ? "SEARCH YT" : "× CLEAR", !ytQuery.isEmpty(), Theme.YT_BADGE);
        } else {
            drawChip(c, chipSearch(), query.isEmpty() ? "SEARCH" : "× " + Skin.fit(query, 15f, 82f), !query.isEmpty());
        }

        // 3. Context Strip: YT Moods (tab 0) | Browse Sub-Tabs (tab 2) | Radio Genres (tab 6)
        float gy = sy + SUB_H + 3f;
        if (tab == 0) {
            float gw = pc.width() / YtMusic.MOODS.length;
            for (int i = 0; i < YtMusic.MOODS.length; i++) {
                float gx = pc.left + i * gw;
                tmp.set(gx + 2, gy, gx + gw - 2, gy + 33f);
                boolean hot = ytQuery.isEmpty() && (i == ytMood);
                drawMiniPill(c, tmp, YtMusic.MOODS[i], hot, Theme.primary());
            }
        } else if (tab == 2) {
            float gw = pc.width() / BROWSE_SUBS.length;
            for (int i = 0; i < BROWSE_SUBS.length; i++) {
                float gx = pc.left + i * gw;
                tmp.set(gx + 4, gy, gx + gw - 4, gy + 33f);
                drawMiniPill(c, tmp, BROWSE_SUBS[i], i == browseSub, Theme.primary());
            }
        } else if (tab == 6) {
            float gw = pc.width() / Radio.GENRES.length;
            for (int i = 0; i < Radio.GENRES.length; i++) {
                float gx = pc.left + i * gw;
                tmp.set(gx + 2, gy, gx + gw - 2, gy + 33f);
                drawMiniPill(c, tmp, Radio.GENRES[i], i == radioGenre, Theme.secondary());
            }
        }

        drawTrackRows(c, sv, false);
    }

    private void drawMiniPill(Canvas c, RectF r, String text, boolean hot, int col) {
        Q.setStyle(Paint.Style.FILL);
        Q.setColor(hot ? Theme.alpha(col, 62) : 0x77091426);
        c.drawRoundRect(r, 10f, 10f, Q);
        if (hot) {
            S.setStyle(Paint.Style.STROKE); S.setStrokeWidth(1.6f); S.setColor(col);
            c.drawRoundRect(r, 10f, 10f, S);
        }
        Skin.text(c, text, r.centerX(), r.centerY() + 5f, 13.5f,
                hot ? Theme.TEXT_MAIN : Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
    }

    private void drawQueue(Canvas c, PlayerService sv) {
        long totMs = sv.queueDurationMs();
        String durPart = totMs > 0 ? (" • " + (totMs / 60_000L) + "m") : "";
        Skin.text(c, "UP NEXT (" + sv.queue.size() + durPart + ")", pc.left + 6, pc.top + 36f, 21f,
                Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, true);
        tmp.set(pc.right - 368, pc.top + 7, pc.right - 258, pc.top + 47);
        drawChip(c, tmp, "SHUF", false);
        tmp.set(pc.right - 248, pc.top + 7, pc.right - 144, pc.top + 47);
        drawChip(c, tmp, "LYRICS", false);
        tmp.set(pc.right - 134, pc.top + 7, pc.right, pc.top + 47);
        drawChip(c, tmp, "‹ BACK", true);
        drawTrackRows(c, sv, true);
    }

    // ---- Mode 4: LIVE SYNCED LYRICS (LRCLIB)
    private void drawLyrics(Canvas c, PlayerService sv) {
        Track cur = sv.current();
        String hdr = cur == null ? "LYRICS" : ("LYRICS • " + Skin.fit(cur.title, 19f, pc.width() - 270f, Theme.TF_BOLD));
        Skin.text(c, hdr, pc.left + 4, pc.top + 36f, 20f, Theme.glow(), Paint.Align.LEFT, Theme.TF_BOLD, true);

        tmp.set(pc.right - 246, pc.top + 7, pc.right - 142, pc.top + 47);
        drawChip(c, tmp, "RETRY", false);
        tmp.set(pc.right - 132, pc.top + 7, pc.right, pc.top + 47);
        drawChip(c, tmp, "‹ BACK", true);

        float y0 = listTop();
        float lh = listH();
        Lyrics.Sheet sh = sv.currentLyrics;
        if (sh == null || sh.lines.isEmpty()) {
            String msg = sv.lyricsLoading ? "SEARCHING LIVE SYNCED LYRICS..."
                    : (sv.lyricsMsg.isEmpty() ? "NO LYRICS FOUND FOR THIS TRACK" : sv.lyricsMsg);
            Skin.text(c, Skin.fit(msg, 20f, pc.width() - 20f, Theme.TF_MEDIUM),
                    pc.centerX(), y0 + lh * 0.45f, 20f, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MEDIUM, false);
            return;
        }

        long pos = sv.pl != null ? sv.pl.getCurrentPosition() : 0L;
        int active = sh.synced ? sh.activeIndex(pos) : -1;

        // Continuously ease synced lyrics toward dead-center unless user just manually scrolled!
        if (sh.synced && active >= 0) {
            boolean trackChanged = !sh.trackKey.equals(lastLyricKey);
            float target = clampScroll(active * LYRIC_ROW - lh * 0.38f);
            if (trackChanged) {
                lastLyricKey = sh.trackKey;
                lastLyricIdx = active;
                scroll = target;
            } else if (System.currentTimeMillis() - lastUserLyricScrollMs > 3200L) {
                lastLyricIdx = active;
                float diff = target - scroll;
                if (Math.abs(diff) > 0.8f) {
                    scroll += diff * 0.24f;
                } else {
                    scroll = target;
                }
            }
        }

        c.save();
        c.clipRect(pc.left - 4, y0, pc.right + 4, pc.bottom);
        int first = Math.max(0, (int) (scroll / LYRIC_ROW) - 1);
        for (int i = first; i < sh.lines.size(); i++) {
            float y = y0 + i * LYRIC_ROW - scroll;
            if (y > pc.bottom + LYRIC_ROW) break;
            Lyrics.Line line = sh.lines.get(i);
            boolean hot = (i == active);
            if (hot) {
                tmp.set(pc.left, y + 3, pc.right, y + LYRIC_ROW - 5);
                Q.setStyle(Paint.Style.FILL);
                Q.setColor(Theme.alpha(Theme.primary(), 42));
                c.drawRoundRect(tmp, 14f, 14f, Q);
                S.setStyle(Paint.Style.STROKE); S.setStrokeWidth(1.8f);
                S.setColor(Theme.alpha(Theme.primary(), 175));
                c.drawRoundRect(tmp, 14f, 14f, S);
            }
            float sz = hot ? 23f : 19f;
            int col = hot ? 0xffffffff : (active >= 0 && i < active ? Theme.TEXT_MUTED : Theme.TEXT_SUB);
            Skin.text(c, Skin.fit(line.text, sz, pc.width() - 28f, hot ? Theme.TF_BOLD : Theme.TF_MEDIUM),
                    pc.centerX(), y + LYRIC_ROW * 0.62f, sz, col, Paint.Align.CENTER,
                    hot ? Theme.TF_BOLD : Theme.TF_MEDIUM, hot);
        }
        c.restore();
    }

    private void drawTrackRows(Canvas c, PlayerService sv, boolean queueView) {
        float y0 = listTop();
        if (rows.isEmpty()) {
            Skin.text(c, Skin.fit(emptyText(sv), 20f, pc.width() - 24f, Theme.TF_MEDIUM),
                    pc.centerX(), y0 + listH() * 0.45f, 20f, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MEDIUM, false);
            return;
        }

        Track cur = sv.current();
        int curQ = sv.currentQueueIndex();
        int first = Math.max(0, (int) (scroll / ROW));

        c.save();
        c.clipRect(pc.left - 8, y0, pc.right + 8, pc.bottom);

        long now = System.currentTimeMillis();
        for (int i = first; i < rows.size(); i++) {
            float y = y0 + i * ROW - scroll;
            if (y > pc.bottom) break;
            Row r = rows.get(i);

            if (r.group) {
                tmp.set(pc.left, y + 4, pc.right, y + ROW - 6);
                Q.setStyle(Paint.Style.FILL);
                Q.setColor(r.recentGroup ? Theme.alpha(Theme.secondary(), 44) : 0x990c1a30);
                c.drawRoundRect(tmp, 16f, 16f, Q);
                S.setStyle(Paint.Style.STROKE); S.setStrokeWidth(1.3f);
                S.setColor(r.recentGroup ? Theme.alpha(Theme.secondary(), 145) : 0x387fb8eb);
                c.drawRoundRect(tmp, 16f, 16f, S);

                Skin.text(c, Skin.fit(r.a, 22.5f, pc.width() - 210f, Theme.TF_BOLD),
                        pc.left + 18, y + 43f, 22.5f, Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, r.recentGroup);
                Skin.text(c, r.b, pc.right - 42, y + 42f, 17.5f, Theme.primary(), Paint.Align.RIGHT, Theme.TF_MEDIUM, false);
                Skin.text(c, "›", pc.right - 16, y + 45f, 26f, Theme.TEXT_SUB, Paint.Align.RIGHT, Theme.TF_BOLD, false);
                continue;
            }

            Track t = r.t;
            boolean on = queueView ? (i == curQ) : (cur != null && cur.key().equals(t.key()));

            // Glossy obsidian-sapphire track card
            tmp.set(pc.left, y + 3, pc.right, y + ROW - 5);
            Q.setStyle(Paint.Style.FILL);
            Q.setColor(on ? Theme.alpha(Theme.primary(), 42) : 0x7c0a1528);
            c.drawRoundRect(tmp, 15f, 15f, Q);
            if (on) {
                S.setStyle(Paint.Style.STROKE);
                S.setStrokeWidth(2f);
                S.setColor(Theme.alpha(Theme.primary(), 175));
                c.drawRoundRect(tmp, 15f, 15f, S);
            }

            // Left album art tile (works for Local AND YouTube Music!)
            float tile = 52f;
            float tx0 = pc.left + 11f, ty0 = y + (ROW - tile) * 0.5f - 1f;
            tmp.set(tx0, ty0, tx0 + tile, ty0 + tile);
            Bitmap th = Art.thumb(ctx, t, host::postInvalidateOnAnimation);
            if (th != null && !th.isRecycled()) {
                c.save();
                tileClip.reset();
                tileClip.addRoundRect(tmp, 11f, 11f, Path.Direction.CW);
                c.clipPath(tileClip);
                c.drawBitmap(th, null, tmp, B);
                c.restore();
            } else {
                Q.setColor(on ? Theme.alpha(Theme.primary(), 80) : 0xff11223a);
                c.drawRoundRect(tmp, 11f, 11f, Q);
                String tag = t.isRadio() ? "LIVE" : (t.isYouTube() ? "YT" : String.format(Locale.US, "%02d", (r.ti + 1) % 100));
                Skin.text(c, tag, tmp.centerX(), tmp.centerY() + 6f, t.stream() ? 13.5f : 17.5f,
                        t.isYouTube() ? Theme.YT_BADGE : (on ? Theme.TEXT_MAIN : Theme.primary()),
                        Paint.Align.CENTER, Theme.TF_MONO, false);
            }

            // Live 3-bar equalizer overlay on currently playing tile
            if (on && sv.pl.isPlaying()) {
                Q.setColor(0x99040b16);
                c.drawRoundRect(tmp, 11f, 11f, Q);
                Q.setColor(Theme.primary());
                for (int b = 0; b < 3; b++) {
                    float bh = 10f + 20f * (float) Math.abs(Math.sin(now / 150.0 + b * 1.4));
                    float bx = tmp.left + 13f + b * 10f;
                    c.drawRoundRect(bx, tmp.bottom - 10f - bh, bx + 5.5f, tmp.bottom - 10f, 3f, 3f, Q);
                }
            }

            // Title & Subtitle
            float textX = tx0 + tile + 13f;
            boolean showDlBtn = !queueView && t.isYouTube();
            float rightPad = queueView ? 74f : (showDlBtn ? 164f : (t.isRadio() ? 132f : 124f));
            float avail = Math.max(80f, pc.right - textX - rightPad);

            Skin.text(c, Skin.fit(t.title, 22f, avail, Theme.TF_BOLD),
                    textX, y + 33f, 22f, on ? Theme.glow() : Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, on);
            Skin.text(c, Skin.fit(t.subLabel(), 16.5f, avail, Theme.TF_MEDIUM),
                    textX, y + 57f, 16.5f, Theme.TEXT_SUB, Paint.Align.LEFT, Theme.TF_MEDIUM, false);

            // Right actions & telemetry badges
            if (queueView) {
                Skin.text(c, "×", pc.right - 26f, y + 45f, 28f, Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_BOLD, false);
            } else if (showDlBtn) {
                // 1-tap Offline Download button [↓ / ✓ / ...] on every YouTube Music track!
                boolean off = sv.isOffline(t);
                boolean dl = Downloader.isDownloading(t.ytId);
                tmp.set(pc.right - 54f, y + 18f, pc.right - 10f, y + ROW - 20f);
                Q.setStyle(Paint.Style.FILL);
                Q.setColor(off ? Theme.alpha(Theme.OFF_BADGE, 52) : (dl ? Theme.alpha(Theme.primary(), 56) : 0x55122640));
                c.drawRoundRect(tmp, 11f, 11f, Q);
                S.setStyle(Paint.Style.STROKE); S.setStrokeWidth(1.4f);
                S.setColor(off ? Theme.OFF_BADGE : Theme.alpha(Theme.primary(), 140));
                c.drawRoundRect(tmp, 11f, 11f, S);
                Skin.text(c, off ? "✓" : (dl ? "…" : "↓"), tmp.centerX(), tmp.centerY() + 6.5f, 19f,
                        off ? Theme.OFF_BADGE : Theme.glow(), Paint.Align.CENTER, Theme.TF_BOLD, false);

                if (sv.fav(t)) Skin.heart(c, pc.right - 144f, y + 37f, 8f, true, Theme.HEART_ON);
                String durTxt = t.dur > 0 ? Skin.mmss(t.dur) : "YT";
                Skin.text(c, durTxt, pc.right - 64f, y + 42f, 16.5f, Theme.TEXT_SUB, Paint.Align.RIGHT, Theme.TF_MONO, false);
            } else if (t.isRadio()) {
                if (isSaved(t)) Skin.heart(c, pc.right - 116f, y + 37f, 8f, true, Theme.HEART_ON);
                Skin.text(c, Skin.fit(t.formatBadge(), 14.5f, 102f, Theme.TF_MONO),
                        pc.right - 12f, y + 42f, 14.5f, Theme.primary(), Paint.Align.RIGHT, Theme.TF_MONO, false);
            } else {
                if (sv.fav(t)) Skin.heart(c, pc.right - 98f, y + 37f, 8f, true, Theme.HEART_ON);
                if (t.isOfflineYtm()) {
                    Skin.text(c, "OFF", pc.right - 12f, y + 29f, 12.5f, Theme.OFF_BADGE, Paint.Align.RIGHT, Theme.TF_MONO, false);
                    Skin.text(c, t.dur > 0 ? Skin.mmss(t.dur) : "", pc.right - 12f, y + 51f, 15.5f, Theme.TEXT_SUB, Paint.Align.RIGHT, Theme.TF_MONO, false);
                } else {
                    Skin.text(c, Skin.mmss(t.dur), pc.right - 12f, y + 42f, 17.5f, Theme.TEXT_SUB, Paint.Align.RIGHT, Theme.TF_MONO, false);
                }
            }
        }
        c.restore();
    }

    private String emptyText(PlayerService sv) {
        if (tab == 0) return ytMsg.isEmpty() ? "TAP SEARCH YT TO STREAM ANY SONG" : ytMsg;
        if (!query.isEmpty()) return "NO MATCHES FOR \"" + query + "\"";
        switch (tab) {
            case 1: case 2: return sv.lib.isEmpty() ? "NO LOCAL FILES — USE YT TAB TO STREAM!" : "EMPTY";
            case 3: return "NO OFFLINE SONGS — TAP ↓ ON ANY YT TRACK";
            case 4: return "NO FAVOURITES — LONG-PRESS OR TAP ♥";
            case 5: return viewingRecent ? "NO RECENTLY PLAYED TRACKS"
                    : (activePlaylist == null ? "TAP + NEW TO CREATE A PLAYLIST" : "EMPTY — LONG-PRESS TRACKS TO ADD");
            default: return radioMsg.isEmpty() ? "NO STATIONS" : radioMsg;
        }
    }

    // ---- Mode 1: 10-BAND PARAMETRIC EQUALIZER
    private float eqCol() { return pc.width() / 11f; }
    private float eqTop() { return pc.top + 60f; }
    private float eqBot() { return pc.top + pc.height() * 0.56f; }
    private RectF chipEqOn()   { tmp.set(pc.right - 224, pc.top + 6, pc.right - 118, pc.top + 44); return tmp; }
    private RectF chipEqFlat() { tmp.set(pc.right - 108, pc.top + 6, pc.right, pc.top + 44); return tmp; }

    private RectF chipPreset(int i) {
        float cw = (pc.width() - 36f) / 4f;
        float rowH = Math.min(46f, (pc.bottom - (eqBot() + 44f) - 16f) / 3f);
        float x = pc.left + (i % 4) * (cw + 12f);
        float y = eqBot() + 42f + (i / 4) * (rowH + 8f);
        tmp.set(x, y, x + cw, y + rowH);
        return tmp;
    }

    private void drawEq(Canvas c, PlayerService sv) {
        Skin.text(c, "10-BAND PARAMETRIC EQ", pc.left + 4, pc.top + 33f, 21.5f, Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, true);
        drawChip(c, chipEqOn(), sv.dsp.eqEnabled ? "EQ ON" : "BYPASS", sv.dsp.eqEnabled);
        drawChip(c, chipEqFlat(), "FLAT", false);

        float top = eqTop(), bot = eqBot(), mid = (top + bot) * 0.5f, half = (bot - top) * 0.5f;
        float colW = eqCol();

        S.setStyle(Paint.Style.STROKE);
        S.setStrokeWidth(1.2f);
        S.setColor(0x288eb4dc);
        c.drawLine(pc.left, top, pc.right, top, S);
        c.drawLine(pc.left, bot, pc.right, bot, S);
        S.setColor(Theme.alpha(Theme.primary(), 75));
        c.drawLine(pc.left, mid, pc.right, mid, S);

        float[] xs = new float[10], ys = new float[10];
        for (int b = 0; b < 10; b++) {
            xs[b] = pc.left + (b + 1.5f) * colW;
            float g = sv.dsp.eqEnabled ? sv.eq(b + 1) : 0f;
            ys[b] = mid - (g / 15f) * half;
        }
        curvePath.reset();
        areaPath.reset();
        curvePath.moveTo(xs[0], ys[0]);
        areaPath.moveTo(xs[0], mid);
        areaPath.lineTo(xs[0], ys[0]);
        for (int b = 0; b < 9; b++) {
            float mx = (xs[b] + xs[b + 1]) * 0.5f;
            curvePath.cubicTo(mx, ys[b], mx, ys[b + 1], xs[b + 1], ys[b + 1]);
            areaPath.cubicTo(mx, ys[b], mx, ys[b + 1], xs[b + 1], ys[b + 1]);
        }
        areaPath.lineTo(xs[9], mid);
        areaPath.close();

        Q.setStyle(Paint.Style.FILL);
        Q.setShader(new LinearGradient(0, top, 0, bot,
                Theme.alpha(Theme.primary(), sv.dsp.eqEnabled ? 68 : 20),
                Theme.alpha(Theme.secondary(), sv.dsp.eqEnabled ? 42 : 10),
                Shader.TileMode.CLAMP));
        c.drawPath(areaPath, Q);
        Q.setShader(null);

        S.setStyle(Paint.Style.STROKE);
        S.setStrokeWidth(3.5f);
        S.setShader(Theme.accentGrad(xs[0], mid, xs[9], mid));
        if (sv.dsp.eqEnabled) Theme.applyGlow(S, 10f, Theme.primary(), 185);
        c.drawPath(curvePath, S);
        S.setShader(null);
        S.clearShadowLayer();

        Q.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 11; i++) {
            float cx = pc.left + (i + 0.5f) * colW;
            float v = sv.eq(i);
            float ky = mid - (v / 15f) * half;
            boolean active = (i == eqBand);

            Q.setColor(0xff081222);
            tmp.set(cx - 7, top, cx + 7, bot);
            c.drawRoundRect(tmp, 7f, 7f, Q);

            int barCol = (i == 0) ? Theme.secondary() : (v >= 0 ? Theme.primary() : Theme.lerp(Theme.primary(), Theme.HEART_ON, 0.55f));
            Q.setColor(sv.dsp.eqEnabled || i == 0 ? barCol : Theme.TEXT_MUTED);
            tmp.set(cx - 5, Math.min(mid, ky), cx + 5, Math.max(mid, ky));
            c.drawRoundRect(tmp, 5f, 5f, Q);

            Q.setShader(Theme.chromeGrad(cx - 18, ky - 10, cx + 18, ky + 10));
            tmp.set(cx - 17, ky - 9.5f, cx + 17, ky + 9.5f);
            c.drawRoundRect(tmp, 8f, 8f, Q);
            Q.setShader(null);
            S.setStyle(Paint.Style.STROKE);
            S.setStrokeWidth(active ? 2.5f : 1.3f);
            S.setColor(active ? 0xffffffff : Theme.alpha(Theme.primary(), 185));
            c.drawRoundRect(tmp, 8f, 8f, S);

            if (active) {
                Skin.text(c, String.format(Locale.US, "%+.0f", v), cx, Math.max(top + 20f, ky - 16f),
                        16.5f, 0xffffffff, Paint.Align.CENTER, Theme.TF_MONO, true);
            }

            String lbl = i == 0 ? "PRE" : Dsp.LABELS[i - 1];
            Skin.text(c, lbl, cx, bot + 28f, 15.5f, active ? Theme.glow() : Theme.TEXT_SUB, Paint.Align.CENTER, Theme.TF_MONO, false);
        }

        for (int i = 0; i < PlayerService.PRESET_NAMES.length; i++) {
            drawChip(c, chipPreset(i), PlayerService.PRESET_NAMES[i], sv.presetIdx == i);
        }
    }

    // ---- Mode 2: STUDIO DSP & BULLETPROOF RELIABILITY COCKPIT
    private float fxY(int i) { return pc.top + 68f + i * Math.min(54f, (pc.height() - 252f) / 6f); }
    private float fxX0() { return pc.left + 205f; }
    private float fxX1() { return pc.right - 18f; }

    private float fxVal(PlayerService v, int i) {
        switch (i) {
            case 0: return (v.pitch + 12f) / 24f;
            case 1: return (v.speed - 0.5f) / 1.5f;
            case 2: return (v.dsp.bass + 12f) / 24f;
            case 3: return (v.dsp.treble + 12f) / 24f;
            case 4: return v.dsp.width / 2f;
            default: return v.vol;
        }
    }

    private String fxTxt(PlayerService v, int i) {
        switch (i) {
            case 0: return String.format(Locale.US, "%+.1f st", v.pitch);
            case 1: return String.format(Locale.US, "%.2fx", v.speed);
            case 2: return String.format(Locale.US, "%+.0f dB", v.dsp.bass);
            case 3: return String.format(Locale.US, "%+.0f dB", v.dsp.treble);
            case 4: return Math.round(v.dsp.width * 100) + "%";
            default: return Math.round(v.vol * 100) + "%";
        }
    }

    private RectF fxStudioChip(int idx) {
        float y = pc.bottom - 168f;
        float cw = (pc.width() - 32f) / 5f;
        float x = pc.left + idx * (cw + 8f);
        tmp.set(x, y, x + cw, y + 42f);
        return tmp;
    }

    private RectF fxRelChip(int idx) {
        float y = pc.bottom - 116f;
        float cw = (pc.width() - 16f) / 3f;
        float x = pc.left + idx * (cw + 8f);
        tmp.set(x, y, x + cw, y + 42f);
        return tmp;
    }

    private float themeRowY() { return pc.bottom - 34f; }
    private float themeOrbX(int i) { return pc.left + 112f + i * 56f; }
    private RectF chipGyro() { float y = themeRowY(); tmp.set(pc.right - 156f, y - 21f, pc.right, y + 21f); return tmp; }

    private void drawFx(Canvas c, PlayerService sv, GyroParallax gyro) {
        Skin.text(c, "STUDIO DSP & ENGINE", pc.left + 4, pc.top + 33f, 21.5f, Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, true);
        tmp.set(pc.right - 134, pc.top + 6, pc.right, pc.top + 44);
        drawChip(c, tmp, "RESET FX", false);

        float x0 = fxX0(), x1 = fxX1();
        for (int i = 0; i < FX_NAMES.length; i++) {
            float y = fxY(i);
            float f = Math.max(0f, Math.min(1f, fxVal(sv, i)));
            float kx = x0 + (x1 - x0) * f;
            boolean active = (i == fxIdx);

            Skin.text(c, FX_NAMES[i], pc.left + 4, y - 1f, 17.5f, active ? Theme.glow() : Theme.TEXT_MAIN, Paint.Align.LEFT, Theme.TF_BOLD, active);
            Skin.text(c, fxTxt(sv, i), pc.left + 4, y + 19f, 15.5f, Theme.primary(), Paint.Align.LEFT, Theme.TF_MONO, false);

            if (i != 1 && i != 5) {
                float mx = (x0 + x1) * 0.5f;
                S.setStyle(Paint.Style.STROKE); S.setStrokeWidth(2f); S.setColor(0x558eb4dc);
                c.drawLine(mx, y - 12, mx, y + 12, S);
            }

            S.setStyle(Paint.Style.STROKE);
            S.setStrokeCap(Paint.Cap.ROUND);
            S.setStrokeWidth(11f);
            S.setColor(0xff091426);
            c.drawLine(x0, y, x1, y, S);

            S.setShader(Theme.accentGrad(x0, y, x1, y));
            if (active) Theme.applyGlow(S, 10f, Theme.primary(), 180);
            c.drawLine(x0, y, kx, y, S);
            S.setShader(null);
            S.clearShadowLayer();

            Q.setStyle(Paint.Style.FILL);
            Q.setShader(Theme.chromeGrad(kx - 15, y - 15, kx + 15, y + 15));
            c.drawCircle(kx, y, active ? 17f : 14.5f, Q);
            Q.setShader(null);
            S.setStrokeWidth(2f);
            S.setColor(active ? 0xffffffff : Theme.primary());
            c.drawCircle(kx, y, active ? 17f : 14.5f, S);
        }

        // Row 1: 5 Studio DSP chips
        drawChip(c, fxStudioChip(0), "REV " + Dsp.REVERB_NAMES[sv.dsp.reverbMode], sv.dsp.reverbMode > 0);
        drawChip(c, fxStudioChip(1), sv.dsp.limiterEnabled ? "LIMIT ON" : "LIMIT OFF", sv.dsp.limiterEnabled);
        drawChip(c, fxStudioChip(2), sv.chanName(), sv.chan != 0);
        drawChip(c, fxStudioChip(3), sv.xfadeSec > 0 ? "XFADE " + sv.xfadeSec + "s" : "XFADE OFF", sv.xfadeSec > 0);
        drawChip(c, fxStudioChip(4), "SLP " + sv.sleepLabel(), sv.sleepMin > 0 || sv.sleepEndOfTrack);

        // Row 2: 3 Bulletproof Reliability & YT Engine chips!
        boolean whitelisted = MainActivity.isBatteryWhitelisted(ctx);
        drawChipColored(c, fxRelChip(0), sv.ignoreFocus ? "NO-STOP: ON" : "NO-STOP: AUTO", sv.ignoreFocus, Theme.OFF_BADGE);
        drawChipColored(c, fxRelChip(1), sv.ytAutoRadio ? "YT RADIO: ON" : "YT RADIO: OFF", sv.ytAutoRadio, Theme.YT_BADGE);
        drawChipColored(c, fxRelChip(2), whitelisted ? "KEEP ALIVE ✓" : "KEEP ALIVE !", whitelisted, Theme.OFF_BADGE);

        // Bottom row: 5 Live Theme Orbs + 3D Gyro Toggle
        float ty = themeRowY();
        Skin.text(c, "THEME", pc.left + 4, ty + 6f, 16.5f, Theme.TEXT_SUB, Paint.Align.LEFT, Theme.TF_BOLD, false);
        for (int i = 0; i < Theme.PALETTES.length; i++) {
            float ox = themeOrbX(i);
            boolean sel = (i == Theme.index());
            Q.setStyle(Paint.Style.FILL);
            Q.setColor(Theme.PALETTES[i].primary);
            if (sel) Theme.applyGlow(Q, 13f, Theme.PALETTES[i].primary, 220);
            c.drawCircle(ox, ty, sel ? 17f : 13f, Q);
            Q.clearShadowLayer();
            if (sel) {
                S.setStyle(Paint.Style.STROKE); S.setStrokeWidth(2.4f); S.setColor(0xffffffff);
                c.drawCircle(ox, ty, 20.5f, S);
            }
        }
        drawChip(c, chipGyro(), gyro.isEnabled() ? "3D TILT ON" : "3D TILT OFF", gyro.isEnabled());
    }

    // ------------------------------------------------------------------ Touch Handling
    void headerTap(PlayerService sv, float x, float y) {
        if (mode == 3) {
            if (x > pc.right - 138 && y < pc.top + 54) {
                mode = 0;
                rebuild(sv, false);
                host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } else if (x > pc.right - 252 && x <= pc.right - 138 && y < pc.top + 54) {
                mode = 4;
                scroll = 0f;
                sv.loadLyricsForCurrent();
                host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } else if (x > pc.right - 372 && x <= pc.right - 252 && y < pc.top + 54) {
                sv.shuffleQueueNow();
                rebuild(sv, false);
                Toast.makeText(ctx, "Upcoming queue shuffled", Toast.LENGTH_SHORT).show();
                host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            return;
        }
        if (mode == 4) {
            if (x > pc.right - 136 && y < pc.top + 54) {
                mode = 0;
                rebuild(sv, false);
                host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } else if (x > pc.right - 252 && x <= pc.right - 136 && y < pc.top + 54) {
                sv.lyricsKey = "";
                sv.loadLyricsForCurrent();
                host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            return;
        }

        // Top 7-tab navigation bar
        if (y < pc.top + TAB_H) {
            int i = (int) ((x - pc.left) / (pc.width() / TABS.length));
            if (i >= 0 && i < TABS.length) {
                host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                selectTab(sv, i);
            }
            return;
        }

        // Context strip (YT Moods / Browse Sub / Radio Genres)
        if (hasStrip() && y >= pc.top + TAB_H + SUB_H) {
            if (tab == 0) {
                int m = (int) ((x - pc.left) / (pc.width() / YtMusic.MOODS.length));
                if (m >= 0 && m < YtMusic.MOODS.length) {
                    host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    loadYtMood(m);
                }
            } else if (tab == 2) {
                int b = (int) ((x - pc.left) / (pc.width() / BROWSE_SUBS.length));
                if (b >= 0 && b < BROWSE_SUBS.length) {
                    browseSub = b;
                    grp = null;
                    rebuild(sv, false);
                    host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            } else if (tab == 6) {
                int g = (int) ((x - pc.left) / (pc.width() / Radio.GENRES.length));
                if (g >= 0 && g < Radio.GENRES.length) {
                    host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    loadRadioGenre(g);
                }
            }
            return;
        }

        // Sub-header chips
        if (chipSearch().contains(x, y)) {
            host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (tab == 0) {
                if (!ytQuery.isEmpty()) loadYtMood(ytMood);
                else promptYtSearch(sv);
            } else if (!query.isEmpty() && tab != 6) {
                query = "";
                rebuild(sv, false);
            } else {
                promptSearch(sv);
            }
        } else if (chipAction().contains(x, y)) {
            host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (tab == 0) {
                Track cur = sv.current();
                if (cur != null && cur.isYouTube()) {
                    sv.playYtWithRadio(cur);
                    Toast.makeText(ctx, "Starting YT Auto-Radio…", Toast.LENGTH_SHORT).show();
                } else if (!vt.isEmpty()) {
                    sv.playYtWithRadio(vt.get(0));
                    Toast.makeText(ctx, "Starting YT Auto-Radio…", Toast.LENGTH_SHORT).show();
                }
            } else if (tab == 6) {
                promptUrl(sv);
            } else if (tab == 5) {
                promptCreatePlaylist(sv);
            } else {
                sortMode = (sortMode + 1) % Library.SORT_NAMES.length;
                Prefs.sortMode(ctx, sortMode);
                rebuild(sv, false);
            }
        } else if (chipQueueBtn().contains(x, y)) {
            host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            mode = 3;
            rebuild(sv, false);
        } else if (chipLrcBtn().contains(x, y)) {
            host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            mode = 4;
            scroll = 0f;
            sv.loadLyricsForCurrent();
        } else if (x < pc.left + 320f) {
            if (tab == 0 && !ytQuery.isEmpty()) {
                loadYtMood(ytMood);
            } else if (grp != null || activePlaylist != null || viewingRecent) {
                grp = null;
                activePlaylist = null;
                viewingRecent = false;
                rebuild(sv, false);
            }
        }
    }

    void tapList(PlayerService sv, float x, float y) {
        if (sv == null || y < listTop() || !pr.contains(x, y)) return;

        if (mode == 4) {
            // Tap any synced lyric line to jump smoothly to that exact moment!
            Lyrics.Sheet sh = sv.currentLyrics;
            if (sh != null && sh.synced) {
                int i = (int) ((y - listTop() + scroll) / LYRIC_ROW);
                if (i >= 0 && i < sh.lines.size()) {
                    long ms = sh.lines.get(i).timeMs;
                    if (ms >= 0) {
                        sv.seekToMs(ms);
                        host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
            }
            return;
        }

        int i = (int) ((y - listTop() + scroll) / ROW);
        if (i < 0 || i >= rows.size()) return;
        Row r = rows.get(i);

        if (mode == 3) {
            if (x > pc.right - 68) {
                sv.removeFromQueue(i);
                host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            } else {
                sv.jumpToQueue(i);
                host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            return;
        }

        if (r.group) {
            if (r.recentGroup) viewingRecent = true;
            else if (r.pl != null) activePlaylist = r.pl;
            else grp = r.grp;
            rebuild(sv, false);
            host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            return;
        }

        Track t = r.t;
        // 1-tap Offline Download button hit on right edge of YouTube Music rows!
        if (t.isYouTube() && x >= pc.right - 60f) {
            host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (sv.isOffline(t)) {
                Toast.makeText(ctx, "Already saved in OFFLINE tab", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(ctx, "Downloading for offline playback…", Toast.LENGTH_SHORT).show();
                sv.downloadTrack(t, (off, err) -> {
                    Toast.makeText(ctx, err != null ? err : ("Saved offline: " + t.title), Toast.LENGTH_SHORT).show();
                    host.invalidate();
                });
            }
            return;
        }

        // Queue full visible list so Next/Prev smoothly glide across every track in the active view!
        sv.playList(vt, r.ti, vkey);
        host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    void longList(PlayerService sv, float x, float y) {
        if (sv == null || mode == 4 || y < listTop() || !pr.contains(x, y)) return;
        int i = (int) ((y - listTop() + scroll) / ROW);
        if (i < 0 || i >= rows.size()) return;
        Row r = rows.get(i);
        host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

        if (r.group && r.pl != null) {
            final Playlist target = r.pl;
            ChromeDialog.actions(ctx, target.name, target.tracks.size() + " tracks",
                    new String[]{"Delete Playlist"}, idx -> {
                        sv.deletePlaylist(target);
                        rebuild(sv, true);
                        host.invalidate();
                    });
            return;
        }
        if (r.group || r.t == null) return;

        final Track t = r.t;
        if (t.isRadio()) {
            boolean saved = isSaved(t);
            ChromeDialog.actions(ctx, t.title, t.formatBadge(),
                    new String[]{saved ? "Remove Saved Station" : "Save Station", "Play Next", "Add to Queue"},
                    idx -> {
                        if (idx == 0) {
                            if (saved) {
                                for (int k = savedStreams.size() - 1; k >= 0; k--)
                                    if (t.url != null && t.url.equals(savedStreams.get(k).url)) savedStreams.remove(k);
                            } else savedStreams.add(0, t);
                            Prefs.streams(ctx, savedStreams);
                            rebuild(sv, true);
                        } else if (idx == 1) sv.playNext(t);
                        else if (idx == 2) sv.addToQueue(t);
                        host.invalidate();
                    });
            return;
        }

        boolean isFav = sv.fav(t);
        boolean isYt = t.isYouTube() || t.isOfflineYtm();
        boolean isOff = sv.isOffline(t);
        ArrayList<String> opts = new ArrayList<>();
        opts.add("▶  Play Next");
        opts.add("+  Add to Queue");
        if (isYt) opts.add("◎  Start YT Auto-Radio Mix");
        if (isYt) opts.add(isOff ? "✕  Delete Offline File" : "↓  Download for Offline");
        opts.add(isFav ? "♥  Remove Favourite" : "♡  Add to Favourites");
        opts.add(activePlaylist != null ? "✕  Remove from " + activePlaylist.name : "+  Add to Playlist…");
        opts.add("ⓘ  Track Details");

        ChromeDialog.actions(ctx, t.title, t.subLabel(), opts.toArray(new String[0]), idx -> {
            String pick = opts.get(idx);
            if (pick.contains("Play Next")) {
                sv.playNext(t);
                Toast.makeText(ctx, "Playing next", Toast.LENGTH_SHORT).show();
            } else if (pick.contains("Add to Queue")) {
                sv.addToQueue(t);
                Toast.makeText(ctx, "Added to queue", Toast.LENGTH_SHORT).show();
            } else if (pick.contains("Auto-Radio")) {
                sv.playYtWithRadio(t);
                Toast.makeText(ctx, "Building YT Auto-Radio queue…", Toast.LENGTH_SHORT).show();
            } else if (pick.contains("Download for Offline")) {
                Toast.makeText(ctx, "Downloading offline…", Toast.LENGTH_SHORT).show();
                sv.downloadTrack(t, (off, err) -> {
                    Toast.makeText(ctx, err != null ? err : ("Saved offline: " + t.title), Toast.LENGTH_SHORT).show();
                    host.invalidate();
                });
            } else if (pick.contains("Delete Offline")) {
                sv.deleteOfflineTrack(t);
                rebuild(sv, true);
                Toast.makeText(ctx, "Removed offline file", Toast.LENGTH_SHORT).show();
            } else if (pick.contains("Favourite")) {
                sv.toggleFav(t);
                if (tab == 4) rebuild(sv, true);
            } else if (pick.contains("Remove from ")) {
                if (activePlaylist != null) {
                    sv.removeFromPlaylist(activePlaylist, t);
                    rebuild(sv, true);
                }
            } else if (pick.contains("Add to Playlist")) {
                pickPlaylistForTrack(sv, t);
            } else if (pick.contains("Track Details")) {
                showTrackInfo(t);
            }
            host.invalidate();
        });
    }

    private void pickPlaylistForTrack(PlayerService sv, Track t) {
        if (sv.playlists.isEmpty()) {
            ChromeDialog.input(ctx, "New Playlist", "Playlist name", "", InputType.TYPE_CLASS_TEXT, name -> {
                if (name.isEmpty()) return;
                Playlist p = sv.createPlaylist(name);
                sv.addToPlaylist(p, t);
                Toast.makeText(ctx, "Added to " + p.name, Toast.LENGTH_SHORT).show();
            });
            return;
        }
        String[] names = new String[sv.playlists.size() + 1];
        for (int i = 0; i < sv.playlists.size(); i++) names[i] = sv.playlists.get(i).name;
        names[sv.playlists.size()] = "+ Create New Playlist…";
        ChromeDialog.actions(ctx, "Add to Playlist", t.title, names, idx -> {
            if (idx == sv.playlists.size()) {
                ChromeDialog.input(ctx, "New Playlist", "Playlist name", "", InputType.TYPE_CLASS_TEXT, name -> {
                    if (name.isEmpty()) return;
                    Playlist p = sv.createPlaylist(name);
                    sv.addToPlaylist(p, t);
                });
            } else {
                Playlist p = sv.playlists.get(idx);
                sv.addToPlaylist(p, t);
                Toast.makeText(ctx, "Added to " + p.name, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showTrackInfo(Track t) {
        ArrayList<String[]> kv = new ArrayList<>();
        kv.add(new String[]{"TITLE", t.title});
        kv.add(new String[]{"ARTIST", t.artist.isEmpty() ? "—" : t.artist});
        kv.add(new String[]{"ALBUM", t.album.isEmpty() ? "—" : t.album});
        kv.add(new String[]{"SOURCE", t.formatBadge()});
        if (t.dur > 0) kv.add(new String[]{"LENGTH", Skin.mmss(t.dur)});
        if (!t.ytId.isEmpty()) kv.add(new String[]{"YT ID", t.ytId});
        if (!t.folder.isEmpty()) kv.add(new String[]{"FOLDER", t.folderName()});
        if (t.year > 0) kv.add(new String[]{"YEAR", Integer.toString(t.year)});
        if (t.size > 0) kv.add(new String[]{"SIZE", String.format(Locale.US, "%.2f MB", t.size / (1024f * 1024f))});
        ChromeDialog.info(ctx, t.title, kv);
    }

    void eqTouch(PlayerService sv, float x, float y, boolean first) {
        if (first) {
            if (chipEqOn().contains(x, y)) { sv.toggleEq(); host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }
            if (chipEqFlat().contains(x, y)) { sv.resetEq(); host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }
            for (int i = 0; i < PlayerService.PRESET_NAMES.length; i++) {
                if (chipPreset(i).contains(x, y)) {
                    sv.preset(i);
                    host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return;
                }
            }
            if (y < eqTop() - 22 || y > eqBot() + 22) return;
            eqBand = Math.max(0, Math.min(10, (int) ((x - pc.left) / eqCol())));
            long now = System.currentTimeMillis();
            if (eqBand == lastEqTapBand && now - lastEqTapMs < 320L) {
                // Double-tap any EQ fader to snap cleanly back to 0 dB!
                sv.setEq(eqBand, 0f);
                eqBand = -1;
                lastEqTapBand = -1;
                lastEqTapMs = 0L;
                host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return;
            }
            lastEqTapBand = eqBand;
            lastEqTapMs = now;
        }
        if (eqBand < 0) return;
        float mid = (eqTop() + eqBot()) * 0.5f, half = (eqBot() - eqTop()) * 0.5f;
        sv.setEq(eqBand, (mid - y) / half * 15f);
    }

    void fxTouch(PlayerService sv, GyroParallax gyro, float x, float y, boolean first) {
        if (first) {
            fxIdx = -1;
            tmp.set(pc.right - 134, pc.top + 6, pc.right, pc.top + 44);
            if (tmp.contains(x, y)) {
                sv.setPitch(0f); sv.setSpeed(1f); sv.setBass(0f); sv.setTreble(0f); sv.setWidth(1f); sv.setReverb(0);
                host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                return;
            }
            for (int i = 0; i < FX_NAMES.length; i++) {
                if (Math.abs(y - fxY(i)) < 26f && x >= fxX0() - 24f) { fxIdx = i; break; }
            }
            if (fxIdx >= 0) {
                long now = System.currentTimeMillis();
                if (fxIdx == lastFxTapIdx && now - lastFxTapMs < 320L) {
                    // Double-tap any Studio FX slider to snap cleanly to its neutral default!
                    switch (fxIdx) {
                        case 0: sv.setPitch(0f); break;
                        case 1: sv.setSpeed(1f); break;
                        case 2: sv.setBass(0f); break;
                        case 3: sv.setTreble(0f); break;
                        case 4: sv.setWidth(1f); break;
                        default: sv.setVol(1f); break;
                    }
                    fxIdx = -1;
                    lastFxTapIdx = -1;
                    lastFxTapMs = 0L;
                    host.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    return;
                }
                lastFxTapIdx = fxIdx;
                lastFxTapMs = now;
            }
            if (fxIdx < 0) {
                if (fxStudioChip(0).contains(x, y)) { sv.cycleReverb(); host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }
                if (fxStudioChip(1).contains(x, y)) { sv.toggleLimiter(); host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }
                if (fxStudioChip(2).contains(x, y)) { sv.cycleChan(); host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }
                if (fxStudioChip(3).contains(x, y)) { sv.cycleXfade(); host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }
                if (fxStudioChip(4).contains(x, y)) { sv.cycleSleep(); host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); return; }

                if (fxRelChip(0).contains(x, y)) {
                    sv.toggleIgnoreFocus();
                    Toast.makeText(ctx, sv.ignoreFocus ? "NO-STOP ON: Never pauses for other apps" : "NO-STOP AUTO: Smart ducking & auto-resume", Toast.LENGTH_SHORT).show();
                    host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return;
                }
                if (fxRelChip(1).contains(x, y)) {
                    sv.toggleYtAutoRadio();
                    host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return;
                }
                if (fxRelChip(2).contains(x, y)) {
                    MainActivity.requestBatteryWhitelist(ctx);
                    host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return;
                }

                float ty = themeRowY();
                for (int i = 0; i < Theme.PALETTES.length; i++) {
                    if (Math.hypot(x - themeOrbX(i), y - ty) < 26f) {
                        sv.setTheme(i);
                        host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        return;
                    }
                }
                if (chipGyro().contains(x, y)) {
                    boolean next = !gyro.isEnabled();
                    gyro.setEnabled(next);
                    Prefs.gyro(ctx, next);
                    host.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return;
                }
            }
        }
        if (fxIdx < 0) return;
        float f = Math.max(0f, Math.min(1f, (x - fxX0()) / (fxX1() - fxX0())));
        switch (fxIdx) {
            case 0: sv.setPitch(f * 24f - 12f); break;
            case 1: sv.setSpeed(0.5f + f * 1.5f); break;
            case 2: sv.setBass(f * 24f - 12f); break;
            case 3: sv.setTreble(f * 24f - 12f); break;
            case 4: sv.setWidth(f * 2f); break;
            default: sv.setVol(f); break;
        }
    }

    // ---- Custom Y2K Chrome Dialog Prompts
    private void promptYtSearch(PlayerService sv) {
        ArrayList<String> chips = Prefs.searchHistory(ctx);
        for (String s : YT_SUGGESTIONS) {
            if (!chips.contains(s) && chips.size() < 10) chips.add(s);
        }
        ChromeDialog.inputWithChips(ctx,
                ctx.getString(R.string.dlg_yt_title),
                "Stream any song directly from YouTube Music",
                ctx.getString(R.string.dlg_yt_hint),
                ytQuery,
                InputType.TYPE_CLASS_TEXT,
                chips,
                q -> {
                    if (q.isEmpty()) loadYtMood(ytMood);
                    else searchYt(sv, q);
                });
    }

    private void promptSearch(PlayerService sv) {
        ChromeDialog.input(ctx,
                ctx.getString(tab == 6 ? R.string.dlg_radio_title : R.string.dlg_search_title),
                ctx.getString(R.string.dlg_search_hint),
                query,
                InputType.TYPE_CLASS_TEXT,
                q -> {
                    if (tab == 6) {
                        if (q.isEmpty()) { loadRadioGenre(0); return; }
                        radioLoading = true;
                        radioMsg = "SEARCHING RADIO...";
                        radioRes = new ArrayList<>();
                        rebuild(sv, false);
                        host.invalidate();
                        Radio.search(q, (l, e) -> {
                            radioLoading = false;
                            radioRes = new ArrayList<>(l);
                            radioMsg = e == null ? "" : e;
                            rebuild(sv, false);
                            host.invalidate();
                        });
                    } else {
                        query = q;
                        if (tab == 2) grp = null;
                        rebuild(sv, false);
                        host.invalidate();
                    }
                });
    }

    private void promptUrl(PlayerService sv) {
        ChromeDialog.input(ctx,
                ctx.getString(R.string.dlg_url_title),
                ctx.getString(R.string.dlg_url_hint),
                "",
                InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT,
                raw -> {
                    if (raw.isEmpty()) return;
                    String u = raw.contains("://") ? raw : "http://" + raw;
                    Radio.resolve(u, (l, e) -> {
                        if (e != null || l.isEmpty()) {
                            Toast.makeText(ctx, R.string.bad_link, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Track t = l.get(0);
                        if (!isSaved(t)) {
                            savedStreams.add(0, t);
                            Prefs.streams(ctx, savedStreams);
                        }
                        sv.playStream(t);
                        if (tab == 6) rebuild(sv, false);
                        host.invalidate();
                    });
                });
    }

    private void promptCreatePlaylist(PlayerService sv) {
        ChromeDialog.input(ctx, "New Playlist", "Enter playlist name", "", InputType.TYPE_CLASS_TEXT, name -> {
            if (name.isEmpty()) return;
            sv.createPlaylist(name);
            rebuild(sv, false);
            host.invalidate();
        });
    }
}
