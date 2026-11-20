package app.earwax;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * EARWAX Automatic In-Place GitHub Release Updater.
 *
 * Checks the GitHub Releases section when the app is launched/entered.
 * If a newer build exists:
 *  - Automatically downloads earwax.apk in the background with a sleek Y2K progress popup
 *  - Automatically launches the system in-place package installer (preserving all data, songs, playlists & settings)
 *  - Handles Android 8+ "Install unknown apps" permission seamlessly and resumes install on return
 */
final class AppUpdater {
    private static final String REPO = "its-zain-dev/EARWAX";
    private static final String VERSION_JSON_URL =
            "https://github.com/" + REPO + "/releases/latest/download/version.json";
    private static final String RELEASES_API_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long RECHECK_INTERVAL_MS = 2 * 60_000L; // 2 minutes
    private static final Pattern VC_BODY_PAT = Pattern.compile("versionCode\\s*=\\s*(\\d+)");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean checking;
    private static volatile boolean downloading;
    private static volatile long lastCheckMs;
    private static volatile File pendingApk;
    private static volatile boolean awaitingInstallPermission;
    private static volatile long lastPromptedCode = -1L;

    private static final class ReleaseInfo {
        final long versionCode;
        final String versionName;
        final String tag;
        final String apkUrl;

        ReleaseInfo(long versionCode, String versionName, String tag, String apkUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName.trim();
            this.tag = tag == null ? "" : tag.trim();
            this.apkUrl = apkUrl == null ? "" : apkUrl.trim();
        }
    }

    private static final class UpdateUi {
        final Dialog dialog;
        final ProgressBar bar;
        final TextView status;

        UpdateUi(Dialog dialog, ProgressBar bar, TextView status) {
            this.dialog = dialog;
            this.bar = bar;
            this.status = status;
        }
    }

    private AppUpdater() { }

    static void checkOnEnter(Activity act) {
        if (act == null) return;
        triggerCheck(act, false);
    }

    static void onResume(Activity act) {
        if (act == null) return;
        if (awaitingInstallPermission && pendingApk != null && pendingApk.exists()) {
            if (canInstallPackages(act)) {
                awaitingInstallPermission = false;
                File apk = pendingApk;
                pendingApk = null;
                launchInstaller(act, apk);
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (lastCheckMs > 0 && now - lastCheckMs >= RECHECK_INTERVAL_MS) {
            triggerCheck(act, false);
        }
    }

    private static void triggerCheck(Activity act, boolean manual) {
        if (checking || downloading) return;
        long now = System.currentTimeMillis();
        if (!manual && lastCheckMs > 0 && now - lastCheckMs < RECHECK_INTERVAL_MS) return;

        checking = true;
        lastCheckMs = now;
        final WeakReference<Activity> ref = new WeakReference<>(act);
        final Context app = act.getApplicationContext();

        new Thread(() -> {
            try {
                PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
                long localCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                String localName = pi.versionName == null ? "0" : pi.versionName;

                ReleaseInfo rel = fetchLatestRelease();
                if (rel == null || rel.apkUrl.isEmpty()) return;

                boolean hasUpdate = rel.versionCode > 0
                        ? rel.versionCode > localCode
                        : compareVersions(rel.versionName.isEmpty() ? rel.tag : rel.versionName, localName) > 0;

                if (!hasUpdate) {
                    cleanupOldApks(app);
                    return;
                }
                if (!manual && rel.versionCode > 0 && rel.versionCode == lastPromptedCode && awaitingInstallPermission) {
                    return;
                }
                lastPromptedCode = rel.versionCode;

                MAIN.post(() -> {
                    Activity a = ref.get();
                    if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                        startDownloadAndInstall(a, localName, rel);
                    }
                });
            } catch (Exception ignored) {
            } finally {
                checking = false;
            }
        }, "earwax-update-check").start();
    }

    private static ReleaseInfo fetchLatestRelease() {
        // 1. Rate-limit-free direct version.json asset on latest release
        try {
            String raw = readUrlText(VERSION_JSON_URL, 6500);
            if (raw != null && !raw.isEmpty()) {
                JSONObject o = new JSONObject(raw);
                long vc = o.optLong("versionCode", -1L);
                String vn = o.optString("versionName", "");
                String tag = o.optString("tag", "");
                String apkUrl = o.optString("apkUrl", "");
                if (!apkUrl.isEmpty()) {
                    return new ReleaseInfo(vc, vn, tag, apkUrl);
                }
            }
        } catch (Exception ignored) { }

        // 2. Fallback to GitHub Releases API
        try {
            String raw = readUrlText(RELEASES_API_URL, 7500);
            if (raw == null || raw.isEmpty()) return null;
            JSONObject o = new JSONObject(raw);
            String tag = o.optString("tag_name", "");
            String body = o.optString("body", "");
            long vc = -1L;
            Matcher m = VC_BODY_PAT.matcher(body);
            if (m.find()) {
                try { vc = Long.parseLong(m.group(1)); } catch (Exception ignored) { }
            }
            String apkUrl = "";
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "").toLowerCase(Locale.ROOT);
                    if (name.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", "");
                        break;
                    }
                }
            }
            if (apkUrl.isEmpty()) return null;
            String vn = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            return new ReleaseInfo(vc, vn, tag, apkUrl);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void startDownloadAndInstall(Activity act, String localName, ReleaseInfo rel) {
        if (downloading) return;
        downloading = true;

        final WeakReference<Activity> ref = new WeakReference<>(act);
        final Context app = act.getApplicationContext();
        final String targetLabel = !rel.versionName.isEmpty() ? rel.versionName : rel.tag;
        final File dir = new File(app.getCacheDir(), "updates");
        if (!dir.exists()) dir.mkdirs();

        String safeTag = (rel.versionCode > 0 ? String.valueOf(rel.versionCode) : targetLabel)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        final File dest = new File(dir, "earwax-" + safeTag + ".apk");

        // If already downloaded & valid, install immediately without re-downloading!
        if (isValidApkFile(dest)) {
            downloading = false;
            onApkReady(act, dest);
            return;
        }

        final UpdateUi ui = showProgressDialog(act, localName, targetLabel);

        new Thread(() -> {
            File tmp = new File(dir, "earwax-" + safeTag + ".apk.part");
            HttpURLConnection conn = null;
            try {
                conn = openFollowingRedirects(rel.apkUrl, 8, 12_000, 25_000);
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new Exception("HTTP " + code);
                }
                long total = conn.getContentLengthLong();
                long downloaded = 0L;
                int lastPct = -1;
                byte[] buf = new byte[32768];

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        downloaded += n;
                        if (total > 0) {
                            int pct = (int) Math.max(0, Math.min(100, (downloaded * 100L) / total));
                            if (pct != lastPct) {
                                lastPct = pct;
                                final long dl = downloaded;
                                MAIN.post(() -> updateProgressUi(ui, pct, dl, total));
                            }
                        } else if ((downloaded & 0x3ffff) < 32768) {
                            final long dl = downloaded;
                            MAIN.post(() -> updateProgressUi(ui, -1, dl, -1));
                        }
                    }
                    out.flush();
                }

                if (total > 0 && tmp.length() != total) {
                    throw new Exception("Incomplete download");
                }
                if (!isValidApkFile(tmp)) {
                    throw new Exception("Invalid APK file");
                }
                if (dest.exists()) dest.delete();
                if (!tmp.renameTo(dest)) {
                    throw new Exception("Rename failed");
                }

                MAIN.post(() -> {
                    dismissQuietly(ui.dialog);
                    Activity a = ref.get();
                    if (a != null && !a.isFinishing() && !a.isDestroyed()) {
                        onApkReady(a, dest);
                    } else {
                        pendingApk = dest;
                        awaitingInstallPermission = true;
                    }
                });
            } catch (Exception e) {
                if (tmp.exists()) tmp.delete();
                MAIN.post(() -> {
                    dismissQuietly(ui.dialog);
                    Activity a = ref.get();
                    if (a != null && !a.isFinishing()) {
                        Toast.makeText(a, "Update download failed — will retry next launch", Toast.LENGTH_SHORT).show();
                    }
                });
            } finally {
                if (conn != null) conn.disconnect();
                downloading = false;
            }
        }, "earwax-update-dl").start();
    }

    private static void onApkReady(Activity act, File apkFile) {
        if (apkFile == null || !apkFile.exists()) return;
        if (canInstallPackages(act)) {
            awaitingInstallPermission = false;
            pendingApk = null;
            launchInstaller(act, apkFile);
            return;
        }
        pendingApk = apkFile;
        awaitingInstallPermission = true;
        showPermissionDialog(act);
    }

    private static boolean canInstallPackages(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    private static void launchInstaller(Activity act, File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", apkFile);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            act.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(act, "Unable to open update installer", Toast.LENGTH_SHORT).show();
        }
    }

    private static void showPermissionDialog(Activity act) {
        if (act.isFinishing() || act.isDestroyed()) return;
        ChromeDialog.actions(act,
                "Install EARWAX Update",
                "Allow EARWAX to install updates in place without deleting your data",
                new String[]{"Allow & Install Now"},
                idx -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + act.getPackageName()));
                        act.startActivity(i);
                    } catch (Exception e) {
                        Toast.makeText(act, "Enable 'Install unknown apps' in Settings", Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ---- Y2K Liquid-Chrome Update Progress Dialog
    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static UpdateUi showProgressDialog(Activity act, String localVer, String remoteVer) {
        LinearLayout outer = new LinearLayout(act);
        outer.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bezel = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xffeaf5ff, 0xff7089a8, 0xffd2e5fb, 0xff283952, 0xff8fa9c8});
        bezel.setCornerRadius(dp(act, 24));
        bezel.setStroke(dp(act, 1.2f), Theme.alpha(Theme.primary(), 200));

        GradientDrawable glass = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xf710223d, 0xf7071122, 0xf7040a16});
        glass.setCornerRadius(dp(act, 21));
        glass.setStroke(dp(act, 1f), Theme.alpha(Theme.primary(), 110));

        LayerDrawable bg = new LayerDrawable(new android.graphics.drawable.Drawable[]{bezel, glass});
        int inset = dp(act, 3.2f);
        bg.setLayerInset(1, inset, inset, inset, inset);
        outer.setBackground(bg);
        int pad = dp(act, 18);
        outer.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(act);
        title.setText("UPDATING EARWAX");
        title.setTextColor(Theme.TEXT_MAIN);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Theme.TF_BOLD);
        outer.addView(title);

        TextView sub = new TextView(act);
        sub.setText("v" + localVer + "  →  v" + remoteVer);
        sub.setTextColor(Theme.glow());
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTypeface(Theme.TF_MONO);
        sub.setPadding(0, dp(act, 4), 0, dp(act, 14));
        outer.addView(sub);

        ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.getProgressDrawable().setColorFilter(Theme.primary(), android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 12));
        blp.bottomMargin = dp(act, 10);
        outer.addView(bar, blp);

        TextView status = new TextView(act);
        status.setText("Connecting to GitHub Release…");
        status.setTextColor(Theme.TEXT_SUB);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        status.setTypeface(Theme.TF_MEDIUM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = dp(act, 14);
        outer.addView(status, slp);

        final Dialog dlg = new Dialog(act);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setContentView(outer);
        dlg.setCancelable(true);

        TextView hideBtn = new TextView(act);
        hideBtn.setText("HIDE IN BACKGROUND");
        hideBtn.setGravity(Gravity.CENTER);
        hideBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        hideBtn.setTypeface(Theme.TF_BOLD);
        hideBtn.setTextColor(Theme.TEXT_MAIN);
        hideBtn.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));
        GradientDrawable btnBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xff172a46, 0xff0c182c, 0xff091324});
        btnBg.setCornerRadius(dp(act, 14));
        btnBg.setStroke(dp(act, 1.1f), Theme.alpha(Theme.CHROME_MID, 145));
        hideBtn.setBackground(btnBg);
        hideBtn.setOnClickListener(v -> dlg.dismiss());
        outer.addView(hideBtn);

        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = (int) (act.getResources().getDisplayMetrics().widthPixels * 0.88f);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.dimAmount = 0.70f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dlg.show();
        return new UpdateUi(dlg, bar, status);
    }

    private static void updateProgressUi(UpdateUi ui, int pct, long downloaded, long total) {
        if (ui == null || ui.dialog == null || !ui.dialog.isShowing()) return;
        if (pct >= 0) {
            ui.bar.setIndeterminate(false);
            ui.bar.setProgress(pct);
            ui.status.setText(String.format(Locale.US, "%d%%  •  %.1f / %.1f MB",
                    pct, downloaded / (1024f * 1024f), total / (1024f * 1024f)));
        } else {
            ui.bar.setIndeterminate(true);
            ui.status.setText(String.format(Locale.US, "Downloading… %.1f MB", downloaded / (1024f * 1024f)));
        }
    }

    private static void dismissQuietly(Dialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Exception ignored) { }
    }

    // ---- Network & File Helpers
    private static HttpURLConnection openFollowingRedirects(String urlStr, int maxHops, int connMs, int readMs) throws Exception {
        String current = urlStr;
        for (int hop = 0; hop < maxHops; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", "EARWAX-Updater");
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.isEmpty()) throw new Exception("Empty redirect");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return c;
        }
        throw new Exception("Too many redirects");
    }

    private static String readUrlText(String urlStr, int timeoutMs) throws Exception {
        HttpURLConnection c = openFollowingRedirects(urlStr, 8, timeoutMs, timeoutMs + 2000);
        try {
            if (c.getResponseCode() >= 400) return "";
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder(4096);
                char[] buf = new char[2048];
                int n;
                while ((n = r.read(buf)) > 0 && sb.length() < 200_000) sb.append(buf, 0, n);
                return sb.toString();
            }
        } finally {
            c.disconnect();
        }
    }

    private static boolean isValidApkFile(File f) {
        if (f == null || !f.exists() || f.length() < 100_000L) return false;
        try (FileInputStream in = new FileInputStream(f)) {
            int b0 = in.read(), b1 = in.read();
            return b0 == 'P' && b1 == 'K';
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanupOldApks(Context app) {
        try {
            File dir = new File(app.getCacheDir(), "updates");
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) f.delete();
        } catch (Exception ignored) { }
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] pb = b.replaceAll("[^0-9.]", "").split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length && !pa[i].isEmpty() ? parseIntSafe(pa[i]) : 0;
            int vb = i < pb.length && !pb[i].isEmpty() ? parseIntSafe(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
