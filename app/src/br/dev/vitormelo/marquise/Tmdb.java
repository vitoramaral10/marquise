package br.dev.vitormelo.marquise;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The other source of posters, for whoever has no Jellyfin: what's popular in the chosen country on the streaming
 * apps installed on this TV, from TMDB (themoviedb.org), with the person's own API key. TMDB says which service has
 * each title there (its watch providers, by JustWatch); selecting the poster opens that app, on a search for the title
 * where the app takes one (TMDB doesn't know the services' own ids, so nothing opens on the title itself).
 *
 * "This product uses the TMDB API but is not endorsed or certified by TMDB."
 */
final class Tmdb {
    private Tmdb() {}

    private static final String API = "https://api.themoviedb.org/3", IMAGES = "https://image.tmdb.org/t/p/w500";
    /** Titles per kind (movies, shows); each needs one more call for its providers. */
    private static final int PER_KIND = 12;

    /** A streaming service: its TMDB provider ids, its apps (TV first) and a search link it opens, if any. */
    static final class Service {
        final String name, search;
        final int[] ids;
        final String[] pkgs;

        Service(String name, int[] ids, String search, String... pkgs) {
            this.name = name;
            this.ids = ids;
            this.search = search;
            this.pkgs = pkgs;
        }
    }

    /**
     * The services Marquise knows how to open. The search links were checked on a TCL Google TV (Netflix, Disney+);
     * the others open on their home screen. Package names are each app's TV build first, then the phone one.
     */
    static final Service[] SERVICES = {
            new Service("Netflix", new int[] {8, 1796}, "nflx://www.netflix.com/search?q=%s", "com.netflix.ninja", "com.netflix.mediaclient"),
            new Service("Prime Video", new int[] {119, 9}, null, "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"),
            new Service("Disney+", new int[] {337}, "https://www.disneyplus.com/search?q=%s", "com.disney.disneyplus"),
            new Service("Max", new int[] {1899, 384}, null, "com.wbd.stream", "com.hbo.hbonow"),
            new Service("Globoplay", new int[] {307}, null, "com.globo.globotv"),
            new Service("Apple TV+", new int[] {350}, null, "com.apple.atve.androidtv.appletv"),
            new Service("Paramount+", new int[] {531}, null, "com.cbs.ott"),
            new Service("Crunchyroll", new int[] {283}, null, "com.crunchyroll.crunchyroid"),
            new Service("Mubi", new int[] {11}, null, "com.mubi"),
            new Service("Hulu", new int[] {15}, null, "com.hulu.livingroomplus"),
            new Service("Peacock", new int[] {386, 387}, null, "com.peacocktv.peacockandroid"),
            new Service("Pluto TV", new int[] {300}, null, "tv.pluto.android"),
    };

    /** TMDB's language for titles and country names: English, Brazilian Portuguese or Latin American Spanish. */
    private static String tmdbLanguage() {
        return Ui.en ? "en-US" : Ui.es ? "es-MX" : "pt-BR";
    }

    /** The installed app of a service, or null. */
    static String installed(PackageManager pm, Service s) {
        for (String pkg : s.pkgs) if (HomeApps.launchIntent(pm, pkg) != null) return pkg;
        return null;
    }

    /** The installed services, by TMDB provider id (several ids can point to one service). */
    static Map<Integer, Service> installedServices(Context c) {
        Map<Integer, Service> out = new LinkedHashMap<>();
        PackageManager pm = c.getPackageManager();
        for (Service s : SERVICES) {
            if (installed(pm, s) == null) continue;
            for (int id : s.ids) out.put(id, s);
        }
        return out;
    }

    /** "Netflix, Disney+", for the settings screen. */
    static String installedNames(Context c) {
        List<String> names = new ArrayList<>();
        for (Service s : installedServices(c).values()) if (!names.contains(s.name)) names.add(s.name);
        return String.join(", ", names);
    }

    // MARK: - Popular titles

    /**
     * Popular in the region on the installed services, movies and shows alternating (the Explore tab), each with the
     * service it opens in. Throws with a readable message when there's nothing to show.
     */
    static List<Recs.Pick> popular(Context c, String key, String region) throws IOException {
        Map<Integer, Service> services = installedServices(c);
        if (services.isEmpty()) {
            throw new IOException(Ui.tr("nenhum streaming conhecido instalado na TV", "no known streaming app installed on the TV"));
        }
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        List<Recs.Pick> movies = discover(c, key, region, "movie", ids.toString(), services);
        List<Recs.Pick> shows = discover(c, key, region, "tv", ids.toString(), services);
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; i < Math.max(movies.size(), shows.size()); i++) {
            if (i < movies.size()) out.add(movies.get(i));
            if (i < shows.size()) out.add(shows.get(i));
        }
        if (out.isEmpty()) throw new IOException(Ui.tr("nada em alta nos seus streamings", "nothing popular on your streaming apps"));
        return out;
    }

    private static List<Recs.Pick> discover(Context c, String key, String region, String kind, String providers,
                                            Map<Integer, Service> services) throws IOException {
        String lang = tmdbLanguage();
        JSONArray results = json(key, "/discover/" + kind + "?watch_region=" + region + "&with_watch_providers=" + Uri.encode(providers)
                + "&with_watch_monetization_types=" + Uri.encode("flatrate|free|ads") + "&sort_by=popularity.desc"
                + "&vote_count.gte=20&include_adult=false&language=" + lang + "&page=1").optJSONArray("results");
        List<Recs.Pick> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (int i = 0; results != null && i < results.length() && out.size() < PER_KIND; i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null || o.optString("poster_path").isEmpty() || o.optString("poster_path").equals("null")) continue;
            Service s = provider(key, kind, o.optLong("id"), region, services);
            String pkg = s == null ? null : installed(pm, s);
            if (pkg == null) continue;
            out.add(pick(o, kind, s, pkg));
        }
        return out;
    }

    private static Recs.Pick pick(JSONObject o, String kind, Service s, String pkg) {
        Recs.Pick p = new Recs.Pick();
        p.itemId = "tmdb-" + o.optLong("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.poster = IMAGES + o.optString("poster_path");
        p.app = s.name;
        p.pkg = pkg;
        p.uri = s.search == null ? null : String.format(Locale.ROOT, s.search, Uri.encode(p.title));
        List<String> badge = new ArrayList<>();
        badge.add(s.name);   // first: where it plays matters most, and the end of the line is what gets cut
        String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date");
        if (date.length() >= 4) badge.add(date.substring(0, 4));
        double rating = o.optDouble("vote_average", 0);
        if (rating > 0) badge.add("★ " + String.format(Ui.local(), "%.1f", rating));
        p.badge = String.join(" · ", badge);
        return p;
    }

    /**
     * The home screen's search: titles matching the text that play on an installed service in the region (the same
     * test as the popular list), best match first. Throws with a readable message when none does.
     */
    static List<Recs.Pick> search(Context c, String key, String region, String query) throws IOException {
        Map<Integer, Service> services = installedServices(c);
        if (services.isEmpty()) {
            throw new IOException(Ui.tr("nenhum streaming conhecido instalado na TV", "no known streaming app installed on the TV"));
        }
        JSONArray results = json(key, "/search/multi?query=" + Uri.encode(query) + "&include_adult=false&language=" + tmdbLanguage()
                + "&page=1").optJSONArray("results");
        List<Recs.Pick> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        // shortcut: one request per title to see where it plays, so only the first dozen are checked; a long list needs
        // TMDB's own "watch providers" filter on /discover, which can't search by name.
        for (int i = 0; results != null && i < Math.min(12, results.length()); i++) {
            JSONObject o = results.optJSONObject(i);
            String kind = o == null ? "" : o.optString("media_type");
            if (!kind.equals("movie") && !kind.equals("tv")) continue;
            Service s = provider(key, kind, o.optLong("id"), region, services);
            String pkg = s == null ? null : installed(pm, s);
            if (pkg != null) out.add(pick(o, kind, s, pkg));
        }
        if (out.isEmpty()) throw new IOException(Ui.tr("nada achado nos seus streamings", "nothing found on your streaming apps"));
        return out;
    }

    /** The first installed service that has the title in the region (TMDB lists them in its display order). */
    private static Service provider(String key, String kind, long id, String region, Map<Integer, Service> services) {
        try {
            JSONObject where = json(key, "/" + kind + "/" + id + "/watch/providers").optJSONObject("results");
            JSONObject here = where == null ? null : where.optJSONObject(region);
            if (here == null) return null;
            for (String type : new String[] {"flatrate", "free", "ads"}) {
                JSONArray list = here.optJSONArray(type);
                for (int i = 0; list != null && i < list.length(); i++) {
                    Service s = services.get(list.getJSONObject(i).optInt("provider_id"));
                    if (s != null) return s;
                }
            }
        } catch (Exception e) {
            AppLog.w("tmdb providers " + id + ": " + e.getMessage());
        }
        return null;
    }

    // MARK: - Setup

    /** Checks a key (v3 key or v4 token): returns normally if TMDB accepts it. */
    static void check(String key) throws IOException {
        json(key, "/configuration");
    }

    /** The countries TMDB has streaming catalogs for: {code, name}, sorted by name, in the TV's language. */
    static List<String[]> regions(String key) throws IOException {
        JSONArray results = json(key, "/watch/providers/regions?language=" + tmdbLanguage()).optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("native_name", o.optString("english_name"));
            out.add(new String[] {o.optString("iso_3166_1"), name});
        }
        final Collator collator = Collator.getInstance(Ui.local());
        Collections.sort(out, (a, b) -> collator.compare(a[1], b[1]));
        return out;
    }

    // MARK: - HTTP

    /** A v4 read access token is a long JWT (sent as a bearer); a v3 key is 32 hex characters (sent in the URL). */
    private static JSONObject json(String key, String path) throws IOException {
        boolean token = key.contains(".");
        String url = API + path + (token ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + Uri.encode(key));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        if (token) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException(Ui.tr("o TMDB recusou a chave", "TMDB rejected the key"));
            if (code != 200) throw new IOException("TMDB HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("TMDB: " + e.getMessage());
        } finally {
            c.disconnect();
        }
    }
}
