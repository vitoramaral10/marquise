package br.dev.vitormelo.marquise;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;
import java.util.UUID;

/**
 * What was chosen on the TV (the setup wizard and the settings screen): where the posters come from (Jellyfin or
 * TMDB), the Jellyfin server and account, the TMDB key and country, and the city. config.properties gives the defaults, so a build made for one TV works without the wizard.
 * Written in the :home process only (the home screen, the settings and the wizard all live there); the switches in
 * the main process read it.
 */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences p(Context c) {
        // shortcut: MODE_MULTI_PROCESS (deprecated) re-reads the file the :home process wrote, so a switch in the main
        // process sees the choice just made; move to a ContentProvider if the file is ever written from both.
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
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

    // MARK: - Where the posters come from

    static final String JELLYFIN = "jellyfin", TMDB = "tmdb";

    /** JELLYFIN (what was added to your server) or TMDB (what's popular on your streaming apps). */
    static String source(Context c) {
        String s = p(c).getString("source", null);
        if (s != null) return s;
        return tmdbKey(c).isEmpty() || jellyfinReady(c) ? JELLYFIN : TMDB;
    }

    static void saveSource(Context c, String source) {
        p(c).edit().putString("source", source).apply();
    }

    // MARK: - TMDB

    /** The person's own TMDB API key (v3) or read access token (v4); empty = not set. */
    static String tmdbKey(Context c) {
        return p(c).getString("tmdbKey", "");
    }

    /** "BR": the country whose streaming catalog is shown. Defaults to the TV's. */
    static String region(Context c) {
        String country = Locale.getDefault().getCountry();
        return p(c).getString("region", country.length() == 2 ? country : "BR");
    }

    /** "Brasil", for the settings screen. */
    static String regionName(Context c) {
        String code = region(c);
        String name = new Locale("", code).getDisplayCountry(Ui.local());
        return p(c).getString("regionName", name.isEmpty() ? code : name);
    }

    static boolean tmdbReady(Context c) {
        return !tmdbKey(c).isEmpty();
    }

    static void saveTmdb(Context c, String key) {
        p(c).edit().putString("tmdbKey", key).apply();
    }

    static void saveRegion(Context c, String code, String name) {
        p(c).edit().putString("region", code).putString("regionName", name).apply();
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

    // MARK: - Preferences (what only config.properties could set before; its values are the defaults)

    /** "auto" (follows the TV), "en", "pt" or "es". Read at every start of a process and when the person changes it. */
    static volatile String language = Config.LANGUAGE;
    /** "auto" (Fahrenheit when the TV is set to the US), "C" or "F". */
    static volatile String temperature = Config.TEMPERATURE;

    /** Reads the saved preferences into the statics the drawing code uses; every process of the app calls this at start. */
    static void load(Context c) {
        language = p(c).getString("language", Config.LANGUAGE);
        temperature = p(c).getString("temperature", Config.TEMPERATURE);
        Ui.refreshLanguage();
    }

    static void setLanguage(Context c, String value) {
        p(c).edit().putString("language", value).apply();
        load(c);
    }

    static void setTemperature(Context c, String value) {
        p(c).edit().putString("temperature", value).apply();
        load(c);
    }

    /** Whether Marquise mode also turns off Google Assistant (the remote's mic button stops). Read at each switch. */
    static boolean assistantOff(Context c) {
        return p(c).getBoolean("assistantOff", Config.DISABLE_ASSISTANT);
    }

    static void setAssistantOff(Context c, boolean off) {
        p(c).edit().putBoolean("assistantOff", off).apply();
    }

    /** A package name as Android writes one: the only thing allowed into a `pm` command. */
    private static final java.util.regex.Pattern PACKAGE = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");

    /** "a.b, c.d" → the valid package names in it, in order, without repeats; anything else is dropped. */
    static java.util.Set<String> parsePackages(String csv) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String s : csv == null ? new String[0] : csv.split(",")) {
            String pkg = s.trim();
            if (PACKAGE.matcher(pkg).matches()) out.add(pkg);
        }
        return out;
    }

    /**
     * The packages to turn off besides the built-in lists: what the person chose on the TV (Preferences → Factory
     * apps) or, until they choose, extra_disable from config.properties.
     */
    static java.util.Set<String> extraDisable(Context c) {
        String saved = p(c).getString("extraDisable", null);
        return parsePackages(saved != null ? saved : Config.EXTRA_DISABLE);
    }

    static void setExtraDisable(Context c, java.util.Collection<String> packages) {
        p(c).edit().putString("extraDisable", String.join(",", parsePackages(String.join(",", packages)))).apply();
    }

    // MARK: - Remote buttons

    /** What a remote button opens (RemoteKeys: a package or one of its targets); fallback until one is chosen. */
    static String remoteTarget(Context c, String button, String fallback) {
        return p(c).getString("remote." + button, fallback);
    }

    static void setRemoteTarget(Context c, String button, String target) {
        p(c).edit().putString("remote." + button, target).apply();
    }

    // MARK: - Updates

    /** Whether this TV also takes beta releases (Settings → Updates); off by default. */
    static boolean betas(Context c) {
        return p(c).getBoolean("betas", false);
    }

    static void setBetas(Context c, boolean on) {
        p(c).edit().putBoolean("betas", on).apply();
    }

    // MARK: - Setup

    /** The wizard ran (or the build already came configured): the app's icon opens the settings, not the wizard. */
    static boolean setupDone(Context c) {
        return p(c).getBoolean("setupDone", false) || jellyfinReady(c) || tmdbReady(c);
    }

    static void finishSetup(Context c) {
        p(c).edit().putBoolean("setupDone", true).apply();
    }
}
