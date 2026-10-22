package br.dev.vitormelo.marquise;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks, from the source chosen on the TV (Prefs.source):
 * - Jellyfin: what was just added to your server (movies, and shows with new episodes), newest first; selecting one
 *   opens that title straight in the Jellyfin app.
 * - TMDB: what's popular in your country on the streaming apps this TV has (Tmdb); selecting one opens that app.
 *
 * Keys and tokens never go to the log.
 */
final class Recs {
    static final class Pick {
        String itemId;          // the Jellyfin item "Watch" opens
        String kind;            // "movie" or "tv"
        String title;
        String app, pkg;        // the service's name ("Jellyfin", "Netflix") and its installed app
        String uri;             // a link the app opens (a search for the title), or null: the app's home
        String poster;          // the card image (2:3)
        String blurhash;        // the poster's color sketch, shown until the image is ready
        String badge = "";      // the short line under the poster: "2025 · ★ 7,2 · 16", "Novo episódio · ★ 8,8"
        boolean resume;         // half-watched, or the next episode of a show being followed: kept in front of the tabs
    }

    interface Listener { void onRecs(); }

    static final String JELLYFIN_PKG = "org.jellyfin.androidtv";
    private static final long LIST_EVERY = 20 * 60_000L;
    private static final long ROTATE_EVERY = 30 * 60_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 13;
    private static final int MAX = 24;
    /** Most "continue watching" posters in front of a tab; the rest of its six are what was added lately. */
    private static final int RESUME_MAX = 3;
    /** Posters per tab on the home screen. */
    static final int PER_TAB = 6;

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // the shown ones, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile long failedAt, keyStamp;
    private volatile String userId;
    private volatile String serverName = "", serverVersion = "";   // for the settings screen

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            listAt = cache.getLong("listAt", 0);
            keyStamp = cache.getLong("keyStamp", 0);
            userId = cache.getString("userId", null);
        } catch (Exception ignored) {
        }
    }

    /** The last fetch's error, or empty. */
    String error() {
        return error;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return tmdb(c) ? Prefs.tmdbReady(c) : Prefs.jellyfinReady(c);
    }

    /**
     * The home screen's search on the person's source: the titles on their Jellyfin server, or those that play on their
     * streaming apps (TMDB). Throws with a readable message when there is none. Run it off the UI thread.
     */
    List<Pick> search(String query) throws Exception {
        if (tmdb(context)) return Tmdb.search(context, Prefs.tmdbKey(context), Prefs.region(context), query);
        if (HomeApps.launchIntent(context.getPackageManager(), JELLYFIN_PKG) == null) {
            throw new IOException(Ui.tr("o app do Jellyfin não está instalado", "the Jellyfin app isn't installed"));
        }
        JSONArray items = new JSONObject(get("/Users/" + user() + "/Items?searchTerm=" + Uri.encode(query)
                + "&IncludeItemTypes=Movie,Series&Recursive=true&Limit=20&Fields=ProductionYear")).getJSONArray("Items");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            Pick p = new Pick();
            p.itemId = o.optString("Id");
            p.kind = o.optString("Type").equals("Movie") ? "movie" : "tv";
            p.title = o.optString("Name");
            p.app = "Jellyfin";
            p.pkg = JELLYFIN_PKG;
            p.badge = "Jellyfin" + (o.optInt("ProductionYear") > 0 ? " · " + o.optInt("ProductionYear") : "");   // as TMDB's: where first
            if (!p.itemId.isEmpty()) out.add(p);
        }
        if (out.isEmpty()) throw new IOException(Ui.tr("nada achado no Jellyfin", "nothing found on Jellyfin"));
        return out;
    }

    static boolean tmdb(Context c) {
        return Prefs.TMDB.equals(Prefs.source(c));
    }

    private String server() {
        return Prefs.server(context);
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .put("server", serverName).put("version", serverVersion).put("source", Prefs.source(context))
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /**
     * 3 of the recently added, rotating every half hour through the list, movies only ("movie") or shows only ("tv");
     * null = both (the home screen tabs). Never two from the same franchise.
     */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;
        List<Pick> pinned = new ArrayList<>();
        for (Pick p : picks) {
            if ((kind != null && !kind.equals(p.kind)) || p.pkg == null) continue;   // no app: none
            if (p.resume) pinned.add(p);
            else list.add(p);
        }
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // What the person is in the middle of comes first, in the order Jellyfin gives (the last one watched first).
        for (int i = 0; i < pinned.size() && out.size() < RESUME_MAX; i++) {
            if (seen.add(franchise(pinned.get(i).title))) out.add(pinned.get(i));
        }
        if (list.isEmpty()) return out;
        long slot = System.currentTimeMillis() / ROTATE_EVERY;
        for (int i = 0; i < list.size() && out.size() < PER_TAB; i++) {
            Pick p = list.get((int) ((slot * PER_TAB + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick opens in ("Jellyfin", "Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : p.app;
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.poster == null) return null;
        final String key = p.poster;
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasPoster(Pick p) {
        return p.poster != null && imageFile(p.poster).exists();
    }

    private File imageFile(String path) {
        return new File(new File(context.getCacheDir(), "recs"), path.replaceAll("[^A-Za-z0-9]", ""));
    }

    /** With a key: fetches the list when stale (every 20 min, or a new server/key/user); then the images shown. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = (Prefs.source(context) + "\n" + server() + "\n" + Prefs.token(context) + "\n" + Prefs.userId(context) + "\n"
                + Prefs.userName(context) + "\n" + Prefs.tmdbKey(context) + "\n" + Prefs.region(context)).hashCode();
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 5 * 60_000L) return;
        boolean stale = newKey || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty();
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !hasPoster(pick);
        if (!stale && !missing) return;
        if (newKey) userId = null;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList();
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        download(pick.poster);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home is hidden: keeps only the given posters' ready images (the ones on screen); the rest is rebuilt on demand. */
    void trim(List<Pick> keep) {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Pick p : keep) if (p.poster != null) wanted.add(p.poster);
        synchronized (images) {
            images.keySet().retainAll(wanted);
        }
    }

    /** The picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    static String key(Pick p) {
        return p.kind + ":" + p.itemId;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - Jellyfin

    private void fetchList() throws Exception {
        if (tmdb(context)) {
            List<Pick> list = Tmdb.popular(context, Prefs.tmdbKey(context), Prefs.region(context));
            picks = list.size() > MAX ? new ArrayList<>(list.subList(0, MAX)) : list;
            listAt = System.currentTimeMillis();
            serverName = Prefs.regionName(context);
            serverVersion = "";
            return;
        }
        try {   // the server's name and version (public, no key): shown on the settings screen
            JSONObject info = new JSONObject(get("/System/Info/Public"));
            serverName = info.optString("ServerName");
            serverVersion = info.optString("Version");
        } catch (Exception ignored) {
        }
        String uid = user();
        JSONArray items = new JSONArray(get("/Users/" + uid + "/Items/Latest?IncludeItemTypes=Movie,Episode&GroupItems=true"
                + "&Limit=" + (MAX + 12) + "&Fields=ProductionYear,ChildCount&EnableImageTypes=Primary&ImageTypeLimit=1"
                + "&EnableUserData=true"));
        boolean app = HomeApps.launchIntent(context.getPackageManager(), JELLYFIN_PKG) != null;
        List<Pick> list = continueWatching(uid, app);
        // No filter by franchise here: now() already keeps one card per franchise in each tab, and which of the
        // in-progress ones show is decided there (more than RESUME_MAX can't all be up front).
        for (int i = 0; i < items.length() && list.size() < MAX; i++) {
            Pick p = pick(items.getJSONObject(i));
            if (p == null) continue;
            if (app) {
                p.app = "Jellyfin";
                p.pkg = JELLYFIN_PKG;
            }
            list.add(p);
        }
        if (list.isEmpty()) throw new IOException(Ui.tr("nada novo no Jellyfin", "nothing new on Jellyfin"));
        picks = list;
        listAt = System.currentTimeMillis();
    }

    /**
     * What the person is in the middle of (Jellyfin's Resume: movies and episodes with a position), then the next
     * episode of the shows they follow (NextUp), one poster per show or movie. Each is marked to stay in front of the
     * tabs. Failing to get them is no reason to fail the list: it is the recently added that matters most.
     */
    private List<Pick> continueWatching(String uid, boolean app) {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> shows = new java.util.HashSet<>();
        String fields = "&EnableImageTypes=Primary&ImageTypeLimit=1&Fields=ProductionYear";
        String[] paths = {"/Users/" + uid + "/Items/Resume?MediaTypes=Video&Limit=8" + fields,
                "/Shows/NextUp?UserId=" + uid + "&Limit=8" + fields};
        for (int pass = 0; pass < 2; pass++) {
            // Each on its own: if NextUp fails, what Resume brought is still kept, and the other way round.
            try {
                JSONArray items = new JSONObject(get(paths[pass])).optJSONArray("Items");
                for (int i = 0; items != null && i < items.length() && out.size() < MAX; i++) {
                    Pick p = resumePick(items.getJSONObject(i), pass == 1);
                    // An episode already being watched is also the show's "next up": one poster for the show.
                    if (p == null || !shows.add(franchise(p.title))) continue;
                    if (app) {
                        p.app = "Jellyfin";
                        p.pkg = JELLYFIN_PKG;
                    }
                    out.add(p);
                }
            } catch (Exception e) {
                AppLog.w("continue watching: " + e.getMessage());
            }
        }
        return out;
    }

    /** A half-watched movie or episode, or the next episode of a show: the poster as usual, a line that says which. */
    private static Pick resumePick(JSONObject o, boolean next) {
        Pick p = pick(o);
        if (p == null) return null;
        JSONObject data = o.optJSONObject("UserData");
        int left = Resume.minutesLeft(o.optLong("RunTimeTicks"), data == null ? 0 : data.optLong("PlaybackPositionTicks"));
        p.badge = Resume.badge(Ui.lang, next, o.optString("Type").equals("Episode"),
                o.optInt("ParentIndexNumber"), o.optInt("IndexNumber"), left);
        if (data != null && data.optBoolean("Played")) p.badge = "✓ " + p.badge;   // watched before, started again
        p.resume = true;
        return p;
    }

    /** The configured user ("jellyfin_user"), or the server's first one. */
    private String user() throws Exception {
        String signedIn = Prefs.userId(context);   // signed in on the TV: that user
        if (!signedIn.isEmpty()) return signedIn;
        if (userId != null) return userId;
        JSONArray users = new JSONArray(get("/Users"));
        String wanted = Prefs.userName(context);
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            if (wanted.isEmpty() || wanted.equalsIgnoreCase(u.optString("Name"))) {
                userId = u.getString("Id");
                cache.edit().putString("userId", userId).apply();
                return userId;
            }
        }
        throw new IOException(Ui.tr("usuário do Jellyfin não encontrado: ", "Jellyfin user not found: ") + wanted);
    }

    /** A movie, an episode, or a season/show with several new episodes (GroupItems). Null = no backdrop to show. */
    private static Pick pick(JSONObject o) {
        String type = o.optString("Type");
        Pick p = new Pick();
        p.itemId = o.optString("Id");
        p.kind = type.equals("Movie") ? "movie" : "tv";
        p.poster = posterPath(o);
        p.blurhash = posterHash(o);
        if (p.poster == null || p.itemId.isEmpty()) return null;
        List<String> badge = new ArrayList<>();
        switch (type) {
            case "Movie": {
                p.title = o.optString("Name");
                int year = o.optInt("ProductionYear");
                if (year > 0) badge.add(String.valueOf(year));
                break;
            }
            case "Episode": {
                p.title = o.optString("SeriesName", o.optString("Name"));
                badge.add(Ui.tr("Novo episódio", "New episode"));
                break;
            }
            default: {   // Season or Series: several new episodes grouped
                p.title = o.optString("SeriesName", o.optString("Name"));
                int n = o.optInt("ChildCount");
                badge.add(n > 1 ? n + Ui.tr(" episódios novos", " new episodes") : Ui.tr("Novo episódio", "New episode"));
                break;
            }
        }
        double rating = o.optDouble("CommunityRating", Double.NaN);
        if (o.optInt("ChildCount") <= 1) {   // "3 episódios novos" alone: it's all that fits under a poster
            if (!Double.isNaN(rating) && rating > 0) {
                badge.add("★ " + String.format(Ui.local(), "%.1f", rating));
            }
            String age = ageRating(o.optString("OfficialRating"));
            if (!age.isEmpty()) badge.add(age);
        }
        p.badge = String.join(" · ", badge);
        JSONObject data = o.optJSONObject("UserData");
        if (data != null && data.optBoolean("Played")) p.badge = "✓ " + p.badge;   // already watched
        return p;
    }

    /** "BR-16" → "16", "BR-L" → "L"; other countries' ratings ("R", "PG-13") as they come. */
    static String ageRating(String r) {
        if (r == null || r.isEmpty() || r.equals("null")) return "";
        return r.startsWith("BR-") ? r.substring(3) : r;
    }

    /** The BlurHash of the poster posterPath picks (Jellyfin sends them per image tag), or null. */
    private static String posterHash(JSONObject o) {
        JSONObject hashes = o.optJSONObject("ImageBlurHashes");
        JSONObject primary = hashes == null ? null : hashes.optJSONObject("Primary");
        if (primary == null) return null;
        JSONObject tags = o.optJSONObject("ImageTags");
        String own = tags == null ? "" : tags.optString("Primary");
        String tag = !own.isEmpty() && !o.optString("Type").equals("Episode") ? own : o.optString("SeriesPrimaryImageTag");
        String hash = tag.isEmpty() ? "" : primary.optString(tag);
        return hash.isEmpty() ? null : hash;
    }

    /** The poster: the movie's or the show's (an episode's own image is a 16:9 still). */
    private static String posterPath(JSONObject o) {
        String type = o.optString("Type");
        JSONObject tags = o.optJSONObject("ImageTags");
        String own = tags == null ? "" : tags.optString("Primary");
        if (!own.isEmpty() && !type.equals("Episode")) return "/Items/" + o.optString("Id") + "/Images/Primary?tag=" + own;
        String series = o.optString("SeriesId"), tag = o.optString("SeriesPrimaryImageTag");
        if (!series.isEmpty()) return "/Items/" + series + "/Images/Primary?tag=" + (tag.isEmpty() ? "series" : tag);
        return null;
    }

    private String get(String path) throws IOException {
        HttpURLConnection c = open(server() + path);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException(Ui.tr("o Jellyfin recusou a chave", "Jellyfin rejected the key"));
            if (code != 200) throw new IOException("Jellyfin HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setRequestProperty("Authorization", Jellyfin.authorization(Prefs.deviceId(context), Prefs.token(context)));
        return c;
    }

    // MARK: - Images (backdrops on disk, up to MAX so the whole list fits; the shown ones kept ready in memory)

    private void download(String path) throws IOException {
        if (path == null) return;
        File f = imageFile(path);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c;
        if (path.startsWith("https://")) {   // TMDB: a public image URL
            c = (HttpURLConnection) new URL(path).openConnection();
            c.setConnectTimeout(8000);
        } else {
            boolean poster = path.contains("/Images/Primary");
            c = open(server() + path + (poster ? "&maxHeight=840&quality=92" : "&maxWidth=1920&quality=90"));
        }
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the poster and builds the card plus the frosted background (Backdrop, poster mode). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.poster)) return;
        }
        File f = imageFile(p.poster);
        try {
            download(p.poster);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outHeight / (opts.inSampleSize * 2) >= Backdrop.POSTER_H) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            Backdrop backdrop = Backdrop.make(b);
            synchronized (images) {
                images.put(p.poster, backdrop);
                while (images.size() > 3 * PER_TAB + 2) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("itemId", p.itemId).put("kind", p.kind).put("title", p.title)
                        .put("poster", p.poster)
                        .put("badge", p.badge).put("resume", p.resume).put("blurhash", p.blurhash == null ? "" : p.blurhash)
                        .put("app", p.app == null ? "" : p.app)
                        .put("pkg", p.pkg == null ? "" : p.pkg).put("uri", p.uri == null ? "" : p.uri));
            }
            cache.edit().putString("picks", a.toString()).putLong("listAt", listAt)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.itemId = o.optString("itemId");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.badge = o.optString("badge");
            p.resume = o.optBoolean("resume");
            p.blurhash = o.optString("blurhash").isEmpty() ? null : o.optString("blurhash");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.uri = o.optString("uri").isEmpty() ? null : o.optString("uri");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
