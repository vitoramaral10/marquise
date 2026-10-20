package br.dev.vitormelo.marquise;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.UUID;

/**
 * What was chosen on the TV (the setup wizard and the settings screen): the Jellyfin server and account, and the
 * city. config.properties gives the defaults, so a build made for one TV works without the wizard.
 * Read and written in the :home process only (the home screen, the settings and the wizard all live there).
 */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // MARK: - Jellyfin

    /** "http://192.168.1.10:8096", without a trailing slash; empty = not set. */
    static String server(Context c) {
        return p(c).getString("server", Config.JELLYFIN_URL.trim()).replaceAll("/+$", "");
    }

    /** The server's own name ("HomeLab"), when known. */
    static String serverName(Context c) {
        return p(c).getString("serverName", "");
    }

    /** An access token (from signing in on the TV) or the API key from config.properties. */
    static String token(Context c) {
        return p(c).getString("token", Config.JELLYFIN_KEY.trim());
    }

    /** The signed-in user's id; empty with an API key (then the user is looked up by name). */
    static String userId(Context c) {
        return p(c).getString("userId", "");
    }

    static String userName(Context c) {
        return p(c).getString("userName", Config.JELLYFIN_USER.trim());
    }

    static boolean jellyfinReady(Context c) {
        return !server(c).isEmpty() && !token(c).isEmpty();
    }

    static void saveJellyfin(Context c, String server, String serverName, String token, String userId, String userName) {
        p(c).edit().putString("server", server).putString("serverName", serverName).putString("token", token)
                .putString("userId", userId).putString("userName", userName).apply();
    }

    /** This TV's id for Jellyfin (its sessions and Quick Connect), made once. */
    static String deviceId(Context c) {
        String id = p(c).getString("deviceId", null);
        if (id == null) {
            id = "marquise-" + UUID.randomUUID().toString().substring(0, 13);
            p(c).edit().putString("deviceId", id).apply();
        }
        return id;
    }

    // MARK: - City (weather and the sunset)

    static String latitude(Context c) {
        return p(c).getString("latitude", Config.LATITUDE);
    }

    static String longitude(Context c) {
        return p(c).getString("longitude", Config.LONGITUDE);
    }

    /** The name on the weather card; empty = "Weather". */
    static String city(Context c) {
        return p(c).getString("city", Config.CITY.trim());
    }

    static void saveCity(Context c, double latitude, double longitude, String name) {
        p(c).edit().putString("latitude", String.format(Locale.US, "%.4f", latitude))
                .putString("longitude", String.format(Locale.US, "%.4f", longitude)).putString("city", name).apply();
    }

    // MARK: - Setup

    /** The wizard ran (or the build already came configured): the app's icon opens the settings, not the wizard. */
    static boolean setupDone(Context c) {
        return p(c).getBoolean("setupDone", false) || jellyfinReady(c);
    }

    static void finishSetup(Context c) {
        p(c).edit().putBoolean("setupDone", true).apply();
    }
}
