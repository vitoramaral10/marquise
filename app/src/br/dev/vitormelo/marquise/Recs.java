package br.dev.vitormelo.marquise;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
 * Home screen picks: what was just added to your Jellyfin server (movies, and shows with new episodes), newest
 * first. "Watch" opens that title straight in the Jellyfin app.
 *
 * The server and its API key come from config.properties ("jellyfin_url", "jellyfin_key", optional "jellyfin_user"),
 * built into the app. The key never goes to the log.
 */
final class Recs {
    static final class Pick {
        String itemId;          // the Jellyfin item "Watch" opens
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String poster;          // the card image (2:3); the backdrop is only for the screensaver
        String blurhash;        // the poster's color sketch, shown until the image is ready
        String badge = "";      // the short line under the poster: "2025 · ★ 7,2 · 16", "Novo episódio · ★ 8,8"
    }

    interface Listener { void onRecs(); }

    static final String JELLYFIN_PKG = "org.jellyfin.androidtv";
    private static final long LIST_EVERY = 20 * 60_000L;
    private static final long ROTATE_EVERY = 30 * 60_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 9;
    private static final int MAX = 24;
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

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return Prefs.jellyfinReady(c);
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
                    .put("server", serverName).put("version", serverVersion)
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static String periodLabel() {
        return Ui.tr("ADICIONADOS RECENTEMENTE", "RECENTLY ADDED");
    }

    /**
     * 3 of the recently added, rotating every half hour through the list, movies only ("movie") or shows only ("tv");
     * null = both (the home screen tabs). Never two from the same franchise.
     */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;
        for (Pick p : picks) if ((kind == null || kind.equals(p.kind)) && p.pkg != null) list.add(p);   // no app: none
        if (list.isEmpty()) return list;
        long slot = System.currentTimeMillis() / ROTATE_EVERY;
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < PER_TAB; i++) {
            Pick p = list.get((int) ((slot * PER_TAB + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the app the pick opens in ("Jellyfin"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : "Jellyfin";
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

    /** The screensaver's photo (the backdrop) is on disk. */
    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p.backdrop).exists();
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
        long stamp = (server() + "\n" + Prefs.token(context) + "\n" + Prefs.userId(context) + "\n" + Prefs.userName(context)).hashCode();
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
                        if (pick.backdrop != null) download(pick.backdrop);
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

    /** Screensaver: the shown picks first, then the rest of the list, those with an image already on disk. */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(key(p))) out.add(p);
        for (Pick p : picks) if (p.pkg != null && hasImage(p) && seen.add(key(p))) out.add(p);
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p.backdrop) : null;
    }

    /** While the screensaver is showing: the images of the whole list, one at a time, so it has more photos. */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 5 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (p.pkg != null && !hasImage(p)) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                for (Pick p : todo) {
                    try {
                        download(p.backdrop);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
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
        try {   // the server's name and version (public, no key): shown on the settings screen
            JSONObject info = new JSONObject(get("/System/Info/Public"));
            serverName = info.optString("ServerName");
            serverVersion = info.optString("Version");
        } catch (Exception ignored) {
        }
        String uid = user();
        JSONArray items = new JSONArray(get("/Users/" + uid + "/Items/Latest?IncludeItemTypes=Movie,Episode&GroupItems=true"
                + "&Limit=" + (MAX + 12) + "&Fields=Genres,ProductionYear,ChildCount&EnableImageTypes=Primary,Backdrop,Thumb&ImageTypeLimit=1"));
        boolean app = HomeApps.launchIntent(context.getPackageManager(), JELLYFIN_PKG) != null;
        List<Pick> list = new ArrayList<>();
        for (int i = 0; i < items.length() && list.size() < MAX; i++) {
            Pick p = pick(items.getJSONObject(i));
            if (p == null) continue;
            if (app) {
                p.app = "jellyfin";
                p.pkg = JELLYFIN_PKG;
            }
            list.add(p);
        }
        if (list.isEmpty()) throw new IOException(Ui.tr("nada novo no Jellyfin", "nothing new on Jellyfin"));
        picks = list;
        listAt = System.currentTimeMillis();
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
        p.backdrop = backdropPath(o);
        p.poster = posterPath(o);
        p.blurhash = posterHash(o);
        if (p.poster == null || p.itemId.isEmpty()) return null;
        List<String> badge = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        switch (type) {
            case "Movie": {
                p.title = o.optString("Name");
                parts.add(Ui.tr("Filme", "Movie"));
                int year = o.optInt("ProductionYear");
                if (year > 0) parts.add(String.valueOf(year));
                long minutes = o.optLong("RunTimeTicks") / 600_000_000L;
                if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + (Ui.en ? "h " : " h ") + (minutes % 60) + (Ui.en ? "m" : " min")
                        : minutes + (Ui.en ? "m" : " min"));
                if (year > 0) badge.add(String.valueOf(year));
                break;
            }
            case "Episode": {
                p.title = o.optString("SeriesName", o.optString("Name"));
                parts.add(Ui.tr("Série", "Series"));
                int s = o.optInt("ParentIndexNumber"), e = o.optInt("IndexNumber");
                if (s > 0 && e > 0) parts.add((Ui.en ? "S" : "T") + s + " E" + e);
                String name = o.optString("Name");
                if (!name.isEmpty() && !name.equals(p.title)) parts.add(name);
                badge.add(Ui.tr("Novo episódio", "New episode"));
                break;
            }
            default: {   // Season or Series: several new episodes grouped
                p.title = o.optString("SeriesName", o.optString("Name"));
                parts.add(Ui.tr("Série", "Series"));
                int n = o.optInt("ChildCount");
                if (n > 1) parts.add(n + Ui.tr(" episódios novos", " new episodes"));
                else if (type.equals("Season")) parts.add(o.optString("Name"));
                badge.add(n > 1 ? n + Ui.tr(" episódios novos", " new episodes") : Ui.tr("Novo episódio", "New episode"));
                break;
            }
        }
        JSONArray genres = o.optJSONArray("Genres");
        if (genres != null && genres.length() > 0 && !type.equals("Episode")) parts.add(genres.optString(0));
        p.meta = String.join(" · ", parts);
        double rating = o.optDouble("CommunityRating", Double.NaN);
        if (o.optInt("ChildCount") <= 1) {   // "3 episódios novos" alone: it's all that fits under a poster
            if (!Double.isNaN(rating) && rating > 0) {
                badge.add("★ " + String.format(Ui.en ? Locale.US : new Locale("pt", "BR"), "%.1f", rating));
            }
            String age = ageRating(o.optString("OfficialRating"));
            if (!age.isEmpty()) badge.add(age);
        }
        p.badge = String.join(" · ", badge);
        return p;
    }

    /** "BR-16" → "16", "BR-L" → "L"; other countries' ratings ("R", "PG-13") as they come. */
    private static String ageRating(String r) {
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

    /** The item's own backdrop, or its show's (episodes and seasons). */
    private static String backdropPath(JSONObject o) {
        JSONArray own = o.optJSONArray("BackdropImageTags");
        if (own != null && own.length() > 0) return "/Items/" + o.optString("Id") + "/Images/Backdrop/0?tag=" + own.optString(0);
        JSONArray parent = o.optJSONArray("ParentBackdropImageTags");
        String parentId = o.optString("ParentBackdropItemId");
        if (parent != null && parent.length() > 0 && !parentId.isEmpty()) {
            return "/Items/" + parentId + "/Images/Backdrop/0?tag=" + parent.optString(0);
        }
        // Latest sometimes leaves the show's tags out: the show's backdrop by its id (the server 404s if none).
        String series = o.optString("SeriesId");
        return series.isEmpty() ? null : "/Items/" + series + "/Images/Backdrop/0?tag=series";
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
        boolean poster = path.contains("/Images/Primary");
        HttpURLConnection c = open(server() + path + (poster ? "&maxHeight=840&quality=92" : "&maxWidth=1920&quality=90"));
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
                        .put("backdrop", p.backdrop == null ? "" : p.backdrop).put("poster", p.poster)
                        .put("badge", p.badge).put("blurhash", p.blurhash == null ? "" : p.blurhash).put("meta", p.meta).put("app", p.app == null ? "" : p.app)
                        .put("pkg", p.pkg == null ? "" : p.pkg));
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
            p.backdrop = o.optString("backdrop").isEmpty() ? null : o.optString("backdrop");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.badge = o.optString("badge");
            p.blurhash = o.optString("blurhash").isEmpty() ? null : o.optString("blurhash");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
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
