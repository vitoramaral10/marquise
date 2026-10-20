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
    private static final long CHECK_EVERY = 6 * 3600_000L;

    static volatile String available = "";   // a newer version ("1.3.1"), or "" when this one is the latest
    static volatile int progress = -1;        // 0–100 while downloading, -1 otherwise
    static volatile String error = "";
    private static volatile String apkUrl = "";
    private static volatile long checkedAt;
    private static volatile boolean checking;

    /** Asks GitHub for the latest release (at most every few hours), in the background. */
    static void check() {
        long now = System.currentTimeMillis();
        if (checking || (checkedAt != 0 && now - checkedAt < CHECK_EVERY && now >= checkedAt)) return;
        checking = true;
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(get(LATEST));
                String tag = o.optString("tag_name").replaceFirst("^v", "");
                JSONArray assets = o.optJSONArray("assets");
                String url = "";
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if ("marquise.apk".equals(a.optString("name"))) url = a.optString("browser_download_url");
                }
                available = !url.isEmpty() && newer(tag, Config.VERSION) ? tag : "";
                apkUrl = url;
                checkedAt = System.currentTimeMillis();
            } catch (Exception e) {
                AppLog.w("update check: " + e.getMessage());
            } finally {
                checking = false;
            }
        }, "marquise-update-check").start();
    }

    /** "1.10.0" is newer than "1.9.2": compared number by number. */
    static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
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
