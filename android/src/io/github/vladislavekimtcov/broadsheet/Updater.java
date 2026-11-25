package io.github.vladislavekimtcov.broadsheet;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches GitHub Releases gracefully (HEAD {@code /releases/latest} without hitting the JSON API
 * rate limit), downloads and verifies {@code broadsheet.apk} in the background, and waits for the
 * user to install it from the front-page banner or Options.
 *
 * <p>Inspired by SleepPicker's AutoUpdater.
 */
final class Updater {  // feature: opt.update
    interface Listener {
        void onUpdateChanged();
    }

    enum State {
        IDLE, CHECKING, DOWNLOADING, READY, UP_TO_DATE, AVAILABLE_DEV, ERROR
    }

    private static final String TAG = "BroadsheetUpdate";
    private static final String LATEST_URL = "https://github.com/VladislavEkimtcov/broadsheet/releases/latest";
    private static final String ASSET_FMT = "https://github.com/VladislavEkimtcov/broadsheet/releases/download/v%s/broadsheet.apk";
    private static final String PREF_CHECKED_AT = "update_checked_at";
    private static final long INITIAL_DELAY_MS = 30_000L;
    private static final long CHECK_INTERVAL_MS = 4 * 3600_000L;
    private static final long MIN_APK_BYTES = 100 * 1024L;
    private static final long MAX_APK_BYTES = 64 * 1024 * 1024L;

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean();
    private final String currentVersion;
    private final int currentCode;

    private volatile State state = State.IDLE;
    private volatile String remoteVersion;
    private volatile String errorText;
    private boolean started;
    private boolean pendingInstallOnReturn;

    private final Runnable scheduledCheck = new Runnable() {
        @Override
        public void run() {
            if (!started || !BuildConfig.AUTO_UPDATE) return;
            long last = prefs.getLong(PREF_CHECKED_AT, 0L);
            long elapsed = System.currentTimeMillis() - last;
            if (elapsed >= CHECK_INTERVAL_MS) {
                check(false);
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            } else {
                handler.postDelayed(this, Math.max(INITIAL_DELAY_MS, CHECK_INTERVAL_MS - elapsed));
            }
        }
    };

    Updater(Context ctx, SharedPreferences prefs, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = prefs;
        this.listener = listener;
        String ver = "0.0.0";
        int code = 0;
        try {
            PackageInfo info = this.ctx.getPackageManager().getPackageInfo(this.ctx.getPackageName(), 0);
            if (info.versionName != null) ver = info.versionName;
            code = info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        this.currentVersion = ver;
        this.currentCode = code;
        new Thread(this::inspectStaged, "update-init").start();
    }

    void start() {
        if (started) return;
        started = true;
        if (BuildConfig.AUTO_UPDATE) {
            handler.removeCallbacks(scheduledCheck);
            handler.postDelayed(scheduledCheck, INITIAL_DELAY_MS);
        }
    }

    void stop() {
        started = false;
        handler.removeCallbacks(scheduledCheck);
    }

    void onResume(Activity activity) {
        if (pendingInstallOnReturn) {
            pendingInstallOnReturn = false;
            if (state == State.READY && canInstallPackages()) install(activity);
        }
    }

    State state() { return state; }

    String currentVersion() { return currentVersion; }

    String remoteVersion() { return remoteVersion; }

    String bannerText() {
        if (state != State.READY || remoteVersion == null) return null;
        return Ui.touch
                ? "Broadsheet v" + remoteVersion + " is ready  ·  Tap to install"
                : "Update v" + remoteVersion + " ready\nPress Menu to install";
    }

    String hintText() {
        switch (state) {
            case READY:
                return Ui.touch ? "Tap to install" : "OK opens the installer";
            case CHECKING:
                return "Checking GitHub Releases…";
            case DOWNLOADING:
                return "Downloading v" + remoteVersion + "…";
            case UP_TO_DATE:
                return "Up to date  ·  " + (Ui.touch ? "Tap to check" : "OK checks now");
            case AVAILABLE_DEV:
                return "v" + remoteVersion + " on GitHub (auto-update off in dev builds)";
            case ERROR:
                return (errorText != null ? errorText : "Check failed") + "  ·  " + (Ui.touch ? "Tap to try again" : "OK tries again");
            default:
                return BuildConfig.AUTO_UPDATE
                        ? (Ui.touch ? "Checks automatically  ·  Tap to check now" : "Checks automatically  ·  OK checks now")
                        : "Dev build  ·  " + (Ui.touch ? "Tap to check" : "OK checks now");
        }
    }

    void check(boolean manual) {
        if (!manual && !BuildConfig.AUTO_UPDATE) return;
        if (!busy.compareAndSet(false, true)) return;
        if (state == State.READY) {
            // Staged update already verified on disk; keep READY unless user forces re-check.
            if (!manual) {
                busy.set(false);
                return;
            }
        }
        if (manual && state != State.READY) setState(State.CHECKING, remoteVersion, null);
        new Thread(() -> {
            try {
                runCheck(manual);
            } catch (Exception e) {
                Log.w(TAG, "update check failed", e);
                if (manual && state != State.READY) setState(State.ERROR, null, "Couldn't reach GitHub");
            } finally {
                busy.set(false);
            }
        }, "update-check").start();
    }

    void install(Activity activity) {
        if (state != State.READY) return;
        File file = ApkProvider.stagedFile(ctx);
        if (!verifyApk(file, remoteVersion)) {
            deleteQuietly(file);
            setState(State.ERROR, null, "Staged update missing or invalid");
            return;
        }
        if (!canInstallPackages()) {
            pendingInstallOnReturn = true;
            try {
                Intent settings = new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",
                        Uri.parse("package:" + ctx.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(settings);
                Toast.makeText(activity, "Allow Broadsheet to install updates, then return.", Toast.LENGTH_LONG).show();
                return;
            } catch (ActivityNotFoundException ignored) {
                pendingInstallOnReturn = false;
            }
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(ApkProvider.CONTENT_URI, ApkProvider.MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "installer launch failed", e);
            Toast.makeText(activity, "Couldn't open the Android installer.", Toast.LENGTH_LONG).show();
        }
    }

    private void inspectStaged() {
        File dir = ApkProvider.updateDir(ctx);
        deleteQuietly(new File(dir, "broadsheet.apk.part"));
        File staged = ApkProvider.stagedFile(ctx);
        if (!staged.isFile()) return;
        String ver = readNewerApkVersion(staged);
        if (ver != null) {
            setState(State.READY, ver, null);
        } else {
            deleteQuietly(staged);
        }
    }

    private void runCheck(boolean manual) throws IOException {
        String tag = fetchLatestTag();
        prefs.edit().putLong(PREF_CHECKED_AT, System.currentTimeMillis()).apply();
        if (tag == null) {
            if (manual && state != State.READY) setState(State.ERROR, null, "No release found");
            return;
        }
        if (!Updates.isNewer(tag, currentVersion)) {
            File staged = ApkProvider.stagedFile(ctx);
            if (staged.isFile()) deleteQuietly(staged);
            setState(State.UP_TO_DATE, null, null);
            return;
        }
        if (!BuildConfig.AUTO_UPDATE) {
            if (manual) setState(State.AVAILABLE_DEV, tag, null);
            return;
        }
        File staged = ApkProvider.stagedFile(ctx);
        if (staged.isFile()) {
            String stagedVer = readNewerApkVersion(staged);
            if (stagedVer != null && !Updates.isNewer(tag, stagedVer)) {
                setState(State.READY, stagedVer, null);
                return;
            }
            deleteQuietly(staged);
        }
        setState(State.DOWNLOADING, tag, null);
        File dir = ApkProvider.updateDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir update");
        File part = new File(dir, "broadsheet.apk.part");
        deleteQuietly(part);
        try {
            download(String.format(ASSET_FMT, tag), part);
            if (!verifyApk(part, tag)) {
                deleteQuietly(part);
                if (manual) setState(State.ERROR, null, "Downloaded APK failed verification");
                return;
            }
            deleteQuietly(staged);
            if (!part.renameTo(staged)) throw new IOException("rename staged apk");
            setState(State.READY, tag, null);
        } finally {
            deleteQuietly(part);
        }
    }

    private static String fetchLatestTag() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(LATEST_URL).openConnection();
        c.setRequestMethod("HEAD");
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", Net.userAgent);
        try {
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                return Updates.tagFromLocation(c.getHeaderField("Location"));
            }
            return null;
        } finally {
            c.disconnect();
        }
    }

    private static void download(String startUrl, File dest) throws IOException {
        String url = startUrl;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("User-Agent", Net.userAgent);
            try {
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || !loc.startsWith("https://")) throw new IOException("bad redirect");
                    url = loc;
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                long total = 0;
                byte[] buf = new byte[16384];
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(dest)) {
                    for (int n; (n = in.read(buf)) > 0; ) {
                        total += n;
                        if (total > MAX_APK_BYTES) throw new IOException("APK too large");
                        out.write(buf, 0, n);
                    }
                }
                return;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private boolean verifyApk(File file, String expectedVersion) {
        if (!file.isFile() || file.length() < MIN_APK_BYTES) return false;
        byte[] hdr = new byte[2];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(hdr) != 2 || hdr[0] != 'P' || hdr[1] != 'K') return false;
        } catch (IOException e) {
            return false;
        }
        String ver = readNewerApkVersion(file);
        if (ver == null) return false;
        return expectedVersion == null || !Updates.isNewer(expectedVersion, ver);
    }

    private String readNewerApkVersion(File file) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo installed = pm.getPackageInfo(ctx.getPackageName(), PackageManager.GET_SIGNATURES);
            PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (archive == null || !ctx.getPackageName().equals(archive.packageName)) return null;
            if (archive.versionCode <= currentCode) return null;
            if (archive.versionName == null || !Updates.isNewer(archive.versionName, currentVersion)) return null;
            if (!sameSignatures(installed.signatures, archive.signatures)) return null;
            return archive.versionName;
        } catch (Exception e) {
            Log.w(TAG, "verify apk failed", e);
            return null;
        }
    }

    private static boolean sameSignatures(Signature[] a, Signature[] b) {
        return a != null && b != null && a.length > 0 && Arrays.equals(a, b);
    }

    private boolean canInstallPackages() {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            Method m = PackageManager.class.getMethod("canRequestPackageInstalls");
            Object res = m.invoke(ctx.getPackageManager());
            return !(res instanceof Boolean) || (Boolean) res;
        } catch (Exception e) {
            return true;
        }
    }

    private void setState(State next, String remote, String err) {
        state = next;
        remoteVersion = remote;
        errorText = err;
        handler.post(listener::onUpdateChanged);
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
