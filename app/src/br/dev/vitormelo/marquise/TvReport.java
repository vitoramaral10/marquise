package br.dev.vitormelo.marquise;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import io.nayuki.qrcodegen.QrCode;

/**
 * "This TV": what Marquise needs to learn another brand, as a link to a pre-filled GitHub issue (the "Support for my
 * TV" form), opened on the phone through a QR code. Only the TV and what came with it: brand, model, Android, the
 * factory home screen, screensaver and antenna input, and the system apps with an icon (the promo candidates). Never
 * the apps the person installed, accounts, network or location.
 */
final class TvReport {
    private TvReport() {}

    private static final String NEW_ISSUE = "https://github.com/vitoramaral10/marquise/issues/new?template=tv_support.yml";
    /** Past this, the QR code gets too dense for a phone to read off the TV (version 20, 97 modules): the app list is cut. */
    static final int MAX_URL = 850;

    static String brandModel() {
        String brand = Build.BRAND == null ? "" : Build.BRAND.toUpperCase(java.util.Locale.ROOT);
        return (brand + " " + (Build.MODEL == null ? "" : Build.MODEL)).trim();
    }

    /** The issue link, with the form's fields filled in. */
    static String url(Context c) {
        String tv = brandModel() + " (" + Build.MANUFACTURER + ", " + Build.DEVICE + ") · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ") · Marquise " + version(c);
        JSONObject s = HomeMode.state(c);
        StringBuilder home = new StringBuilder();
        home.append("home: ").append(or(s.optString("originalHome"), "?")).append('\n');
        home.append("screensaver: ").append(or(s.optString("originalDream"),
                or(Settings.Secure.getString(c.getContentResolver(), "screensaver_default_component"), "?"))).append('\n');
        home.append("tuner: ").append(or(tuners(c), "none"));
        return fit(brandModel(), tv, home.toString(), factoryApps(c, s), recentErrors(2, 80),
                Ui.tr("(lista de apps cortada para caber no QR code)", "(app list cut to fit the QR code)"));
    }

    /**
     * The link until it fits MAX_URL: a scannable code over the whole list. What goes first: the recent problems
     * (oldest first, down to the latest one), then the apps from the end (never under three), then that last problem.
     */
    static String fit(String brandModel, String tv, String home, List<String> apps, List<String> errors, String cutNote) {
        boolean cut = false;
        String link = link(brandModel, tv, home, String.join("\n", apps), String.join("\n", errors), "");
        while (link.length() > MAX_URL) {
            if (errors.size() > 1) {
                errors = errors.subList(1, errors.size());
            } else if (apps.size() > 3) {
                apps = apps.subList(0, apps.size() - 1);
                cut = true;
            } else if (!errors.isEmpty()) {
                errors = Collections.emptyList();
            } else {
                break;
            }
            link = link(brandModel, tv, home, String.join("\n", apps) + (cut ? "\n…" : ""), String.join("\n", errors),
                    cut ? cutNote : "");
        }
        return link;
    }

    /** A crisp black-on-white QR code, one pixel per module (draw it scaled without filtering). */
    static Bitmap qr(String text) {
        QrCode code = QrCode.encodeText(text, QrCode.Ecc.LOW);
        int border = 3, n = code.size + 2 * border;
        int[] pixels = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                boolean dark = code.getModule(x - border, y - border);
                pixels[y * n + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888);
    }

    /** How many factory apps Marquise keeps off (for the screen). */
    static int turnedOff(Context c) {
        JSONArray off = HomeMode.state(c).optJSONArray("disabled");
        return off == null ? 0 : off.length();
    }

    /** The issue link's URL, with these fields percent-encoded into the form's query parameters. */
    private static String link(String brandModel, String tv, String home, String packages, String errors, String notes) {
        return NEW_ISSUE + "&title=" + enc("Support for my TV: " + brandModel) + "&tv=" + enc(tv) + "&home=" + enc(home)
                + "&packages=" + enc(packages) + (errors.isEmpty() ? "" : "&errors=" + enc(errors))
                + (notes.isEmpty() ? "" : "&notes=" + enc(notes));
    }

    /**
     * System apps with an icon on the TV (what came with it, where the promo apps are), and the ones Marquise turned
     * off (they no longer have an icon), marked "(off)". Apps the person installed are left out.
     */
    private static List<String> factoryApps(Context c, JSONObject state) {
        List<String> out = new ArrayList<>(systemApps(c));
        JSONArray off = state.optJSONArray("disabled");
        List<String> disabled = new ArrayList<>();
        for (int i = 0; off != null && i < off.length(); i++) disabled.add(off.optString(i) + " (off)");
        Collections.sort(disabled);
        out.addAll(disabled);
        return out;
    }

    /** The system apps with an icon on the TV (what came with it), sorted by package; never Marquise. */
    static TreeSet<String> systemApps(Context c) {
        PackageManager pm = c.getPackageManager();
        TreeSet<String> pkgs = new TreeSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                    && !ai.packageName.equals(c.getPackageName())) {
                pkgs.add(ai.packageName);
            }
        }
        return pkgs;
    }

    /** The last n problems AppLog kept (oldest first), each cut to max characters: what the report says went wrong. */
    static List<String> recentErrors(int n, int max) {
        List<String> out = new ArrayList<>();
        for (AppLog.Entry e : AppLog.recent(n)) out.add(e.text.length() > max ? e.text.substring(0, max - 1) + "…" : e.text);
        return out;
    }

    /** The TV's antenna and other hardware inputs ("…/HW<id>"), which tell how its live TV is reached. */
    private static String tuners(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return "";
        List<String> ids = new ArrayList<>();
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_TUNER) ids.add(info.getId());
            }
        } catch (RuntimeException ignored) {
        }
        return String.join(", ", ids);
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static String or(String s, String fallback) {
        return s == null || s.isEmpty() || s.equals("null") ? fallback : s;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }
}
