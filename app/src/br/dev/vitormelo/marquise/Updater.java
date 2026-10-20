package br.dev.vitormelo.marquise;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Updates from the project's GitHub releases: the settings screen asks whether there is a newer version and, on OK,
 * the APK is streamed straight into Android's own installer (PackageInstaller), which asks the person to confirm.
 * Releases are signed with the same key, so the update installs on top. State lives here for the settings screen
 * (same :home process); the installer's answer comes back through UpdateReceiver.
 */
final class Updater {
    private Updater() {}

    private static final String LATEST = "https://api.github.com/repos/vitoramaral10/marquise/releases/latest";
    /** The recent releases, betas (pre-releases) included: for a TV that opted in to them. */
    private static final String RECENT = "https://api.github.com/repos/vitoramaral10/marquise/releases?per_page=20";
    private static final long CHECK_EVERY = 6 * 3600_000L;

    static volatile String available = "";   // a newer version ("1.3.1"), or "" when this one is the latest
    static volatile int progress = -1;        // 0–100 while downloading, -1 otherwise
    static volatile String error = "";
    private static volatile String apkUrl = "";
    private static volatile long checkedAt;
    private static volatile boolean checking;
    /** Bumped when the channel changes: a check that started before it is out of date and publishes nothing. */
    private static int generation;

    /**
     * Asks GitHub for the newest release (at most every few hours), in the background: the latest stable one, or,
     * on a TV that takes betas, the newest of the recent releases, betas included.
     */
    static void check(Context c) {
        final int mine;
        synchronized (Updater.class) {
            long now = System.currentTimeMillis();
            if (checking || (checkedAt != 0 && now - checkedAt < CHECK_EVERY && now >= checkedAt)) return;
            checking = true;
            mine = generation;
        }
        final boolean betas = Prefs.betas(c);
        new Thread(() -> {
            try {
                JSONArray releases = betas ? new JSONArray(get(RECENT)) : new JSONArray().put(new JSONObject(get(LATEST)));
                String best = "", url = "";
                for (int r = 0; r < releases.length(); r++) {
                    JSONObject o = releases.getJSONObject(r);
                    if (o.optBoolean("draft")) continue;
                    String tag = o.optString("tag_name").replaceFirst("^v", ""), apk = "";
                    JSONArray assets = o.optJSONArray("assets");
                    for (int i = 0; assets != null && i < assets.length(); i++) {
                        JSONObject a = assets.getJSONObject(i);
                        if ("marquise.apk".equals(a.optString("name"))) apk = a.optString("browser_download_url");
                    }
                    if (!apk.isEmpty() && (best.isEmpty() || newer(tag, best))) {
                        best = tag;
                        url = apk;
                    }
                }
                synchronized (Updater.class) {
                    if (mine != generation) return;   // the channel changed meanwhile: this answer is for the old one
                    available = !best.isEmpty() && newer(best, Config.VERSION) ? best : "";
                    apkUrl = url;
                    checkedAt = System.currentTimeMillis();
                }
            } catch (Exception e) {
                AppLog.w("update check: " + e.getMessage());
            } finally {
                synchronized (Updater.class) {
                    if (mine == generation) checking = false;
                }
            }
        }, "marquise-update-check").start();
    }

    /**
     * The channel changed: forgets the answer of the old one (a beta must not stay on offer after going back to
     * stable, nor wait for the cache to show up), drops a check still running for it, and asks again.
     */
    static void recheck(Context c) {
        synchronized (Updater.class) {
            generation++;
            checking = false;
            checkedAt = 0;
            available = "";
            apkUrl = "";
        }
        check(c);
    }

    /**
     * Semantic versions: "1.10.0" is newer than "1.9.2", and a release is newer than its betas ("1.10.0" >
     * "1.10.0-beta.2" > "1.10.0-beta.1"). Parts are compared one by one, numbers by value.
     */
    static boolean newer(String a, String b) {
        String[] x = a.split("-", 2), y = b.split("-", 2);
        int core = compare(x[0], y[0]);
        if (core != 0) return core > 0;
        if (x.length != y.length) return x.length < y.length;   // no suffix is newer than a beta of it
        return x.length == 2 && compare(x[1], y[1]) > 0;
    }

    private static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d{1,9}") && q.matches("\\d{1,9}") ? Integer.compare(Integer.parseInt(p), Integer.parseInt(q))
                    : p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }

    /** Downloads the new APK into an install session and commits it: Android then asks to confirm. */
    static void install(Context context) {
        if (progress >= 0 || available.isEmpty() || apkUrl.isEmpty()) return;
        final Context app = context.getApplicationContext();
        progress = 0;
        error = "";
        new Thread(() -> {
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            int id = -1;
            try {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    long total = c.getContentLengthLong();
                    try (InputStream in = c.getInputStream(); OutputStream out = session.openWrite("marquise.apk", 0, total)) {
                        byte[] buffer = new byte[65536];
                        long done = 0;
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            out.write(buffer, 0, n);
                            done += n;
                            if (total > 0) progress = (int) Math.min(99, done * 100 / total);
                        }
                        session.fsync(out);
                    } finally {
                        c.disconnect();
                    }
                    Intent done = new Intent(app, UpdateReceiver.class);
                    PendingIntent result = PendingIntent.getBroadcast(app, id, done,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(result.getIntentSender());
                }
                progress = 100;
            } catch (Exception e) {
                if (id >= 0) {
                    try {
                        installer.abandonSession(id);
                    } catch (Exception ignored) {
                    }
                }
                failed(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }, "marquise-update").start();
    }

    static void failed(String message) {
        progress = -1;
        error = message == null ? "" : message;
        AppLog.w("update: " + error);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Marquise/" + Config.VERSION);
        try {
            if (c.getResponseCode() != 200) throw new IOException("GitHub HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
