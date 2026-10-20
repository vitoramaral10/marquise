package br.dev.vitormelo.marquise;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver: a gallery. One painting at a time from the Art Institute of Chicago's public-domain collection
 * (CC0, the curated list in assets/gallery.json), framed with a mat under a spotlight on a dark wall, its label at
 * the bottom left and the time at the bottom right. A new work every 75 s (fade to the wall and back); the whole
 * scene drifts a few pixels so nothing stays on the same pixels. Images are fetched at screen size (IIIF) and kept
 * on disk, so it also works offline with what was already seen. Redrawn every 2 s, and every frame only while fading.
 */
final class DreamView extends View implements HomeData.Listener {
    private static final long SHOW_MS = 75_000, FADE_MS = 2400, TICK_MS = 2000;
    private static final String IIIF = "https://www.artic.edu/iiif/2/%s/full/!1400,840/0/default.jpg";
    private static final int CACHE_KEEP = 24;
    private static final int CREAM = 0xFFF3EDE2, MAT = 0xFFF1ECE3, MAT_SHADE = 0xFFE2DCD0, FRAME = 0xFF17120D;

    private static final class Art {
        String title, artist, date, image;
        float ratio;
        Bitmap bitmap;
        final RectF frame = new RectF(), mat = new RectF(), picture = new RectF();
        Paint matPaint;
        String label1, label2;
    }

    private final HomeData data;
    private final File cache;
    private final List<Art> works = new ArrayList<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            ticks++;
            if (ticks % 30 == 0) data.refreshIfStale();   // once a minute
            long now = System.currentTimeMillis();
            if (fadeStart < 0 && pending != null && (current == null || now - shownAt >= SHOW_MS)) {
                fadeStart = now;
                previous = current;
            }
            invalidate();
            handler.postDelayed(this, TICK_MS);
        }
    };
    private boolean running, loading;
    private long startedAt, shownAt, fadeStart = -1;
    private int ticks, position;
    private Art current, previous, pending;

    // Made once per size.
    private float k = 1;
    private int w, h;
    private Paint wall, spot, shadow, frame, frameEdge, bevel, picture, title, line, museum, time, date;
    private final Matrix move = new Matrix();
    private final RectF rect = new RectF();
    private final Date now = new Date();
    private java.text.DateFormat timeFormat;
    private SimpleDateFormat dateFormat;

    DreamView(Context c) {
        super(c);
        data = HomeData.get(c);
        cache = new File(c.getCacheDir(), "gallery");
        try (InputStream in = c.getAssets().open("gallery.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            JSONArray list = new JSONArray(out.toString(StandardCharsets.UTF_8.name()));
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.getJSONObject(i);
                Art a = new Art();
                a.title = o.getString("title");
                a.artist = o.optString("artist");
                a.date = o.optString("date");
                a.image = o.getString("image");
                a.ratio = (float) o.getDouble("ratio");
                works.add(a);
            }
        } catch (Exception e) {
            AppLog.w("gallery: " + e.getMessage());
        }
        Collections.shuffle(works);
        // Start with a work already on disk, so the first one shows at once (and offline).
        for (int i = 0; i < works.size(); i++) {
            if (file(works.get(i)).exists()) {
                Collections.swap(works, 0, i);
                break;
            }
        }
    }

    void start() {
        if (running) return;
        running = true;
        startedAt = System.currentTimeMillis();
        data.addListener(this);
        data.refreshIfStale();
        loadNext();
        handler.post(tick);
    }

    void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(tick);
        data.removeListener(this);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        loader.shutdownNow();
    }

    @Override public void onData() {
        invalidate();
    }

    // ---------------------------------------------------------------- loading

    private File file(Art a) {
        return new File(cache, a.image + ".jpg");
    }

    /** Fetches the next work that loads (skipping any that fail); offline, only the ones on disk are shown. */
    private void loadNext() {
        if (loading || pending != null || works.isEmpty() || loader.isShutdown()) return;
        loading = true;
        final int from = position;
        loader.execute(() -> {
            Art found = null;
            int tried = 0, at = from;
            while (found == null && tried < works.size()) {
                Art a = works.get(at % works.size());
                at++;
                tried++;
                if (a == current) continue;
                boolean cached = file(a).exists();
                if (!cached && !download(a)) continue;
                a.bitmap = decode(file(a), 1400, 840);   // the size IIIF sends
                if (a.bitmap != null) found = a;
            }
            final Art art = found;
            final int next = at;
            handler.post(() -> {
                loading = false;
                position = next;
                if (art == null) return;
                pending = art;
                layout(art);
                if (current == null) {   // the first one comes in right away
                    fadeStart = System.currentTimeMillis();
                    previous = null;
                    invalidate();
                }
            });
        });
    }

    private boolean download(Art a) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(String.format(IIIF, a.image)).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setRequestProperty("AIC-User-Agent", "Marquise/" + Config.VERSION + " (github.com/vitoramaral10/marquise)");
            if (c.getResponseCode() != 200) return false;
            if (!cache.isDirectory() && !cache.mkdirs()) return false;
            File part = new File(cache, a.image + ".part");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            if (!part.renameTo(file(a))) return false;
            trimCache();
            return true;
        } catch (Exception e) {
            AppLog.w("gallery " + a.image + ": " + e.getMessage());
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Keeps the most recently fetched works on disk (~300 KB each). */
    private void trimCache() {
        File[] files = cache.listFiles();
        if (files == null || files.length <= CACHE_KEEP) return;
        Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        for (int i = CACHE_KEEP; i < files.length; i++) //noinspection ResultOfMethodCallIgnored
            files[i].delete();
    }

    private static Bitmap decode(File f, int maxW, int maxH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();   // a broken download: fetch it again next time
            return null;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= maxW && o.outHeight / (o.inSampleSize * 2) >= maxH) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    // ---------------------------------------------------------------- layout

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        wall = new Paint();
        wall.setShader(new LinearGradient(0, 0, 0, h, 0xFF1C1915, 0xFF0A0908, Shader.TileMode.CLAMP));
        // The gallery light from above, made at the origin and moved onto the work.
        spot = new Paint();
        spot.setShader(new RadialGradient(0, 0, 1000 * k, new int[] {0x38FFE4BC, 0x14FFD9A6, 0x00000000},
                new float[] {0, 0.55f, 1}, Shader.TileMode.CLAMP));
        shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(FRAME);
        shadow.setShadowLayer(54 * k, 0, 30 * k, 0xB0000000);
        frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        frame.setColor(FRAME);
        frameEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        frameEdge.setStyle(Paint.Style.STROKE);
        frameEdge.setStrokeWidth(1.5f * k);
        frameEdge.setColor(0x2EFFE9C8);
        bevel = new Paint(Paint.ANTI_ALIAS_FLAG);
        bevel.setStyle(Paint.Style.STROKE);
        bevel.setStrokeWidth(4 * k);
        bevel.setColor(0xFFFCFAF5);
        picture = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        title = Ui.text(Ui.displaySemibold, 32 * k, CREAM);
        line = Ui.text(Ui.regular, 24 * k, 0x99F3EDE2);
        museum = Ui.text(Ui.semibold, 15 * k, 0x66F3EDE2);
        museum.setLetterSpacing(0.32f);
        time = Ui.numbers(Ui.light, 68 * k, CREAM);
        time.setTextAlign(Paint.Align.RIGHT);
        date = Ui.text(Ui.regular, 24 * k, 0x99F3EDE2);
        date.setTextAlign(Paint.Align.RIGHT);
        timeFormat = android.text.format.DateFormat.getTimeFormat(getContext());
        dateFormat = Ui.en ? new SimpleDateFormat("EEEE, MMMM d", java.util.Locale.US) : new SimpleDateFormat("EEEE, d 'de' MMMM", Ui.PT);
        if (current != null) layout(current);
        if (pending != null) layout(pending);
        loadNext();
    }

    /** The frame, mat and picture rectangles (centered a little above the middle, leaving room for the label). */
    private void layout(Art a) {
        if (w == 0) return;
        float maxW = 1040 * k, maxH = 560 * k;
        float pw = Math.min(maxW, maxH * a.ratio), ph = pw / a.ratio;
        float m = 62 * k, f = 20 * k, cx = w / 2f, cy = h * 0.42f;
        a.picture.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2);
        a.mat.set(a.picture.left - m, a.picture.top - m, a.picture.right + m, a.picture.bottom + m * 1.12f);   // museum mats sit a bit lower
        a.frame.set(a.mat.left - f, a.mat.top - f, a.mat.right + f, a.mat.bottom + f);
        a.matPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        a.matPaint.setShader(new LinearGradient(0, a.mat.top, 0, a.mat.bottom, MAT, MAT_SHADE, Shader.TileMode.CLAMP));
        a.label1 = TextUtils.ellipsize(a.title, new android.text.TextPaint(title), 980 * k, TextUtils.TruncateAt.END).toString();
        String by = a.artist == null || a.artist.isEmpty() || a.artist.equals("null") ? "" : a.artist;
        a.label2 = by.isEmpty() ? a.date : a.date.isEmpty() ? by : by + ", " + a.date;
    }

    // ---------------------------------------------------------------- drawing

    @Override protected void onDraw(Canvas c) {
        if (wall == null) return;
        long t = System.currentTimeMillis();
        c.drawRect(0, 0, w, h, wall);

        // Fade out to the wall, then in with the next one.
        float p = 1;
        if (fadeStart >= 0) {
            p = Math.min(1, (t - fadeStart) / (float) FADE_MS);
            if (p >= 1) {
                if (previous != null && previous != pending && previous.bitmap != null) previous.bitmap.recycle();
                current = pending;
                pending = null;
                previous = null;
                fadeStart = -1;
                shownAt = t;
                loadNext();
            } else {
                postInvalidateOnAnimation();
            }
        }
        Art art;
        float alpha;
        if (fadeStart < 0) {
            art = current;
            alpha = 1;
        } else if (previous != null && p < 0.5f) {
            art = previous;
            alpha = 1 - ease(p * 2);
        } else {
            art = pending;
            alpha = previous == null ? ease(p) : ease((p - 0.5f) * 2);
        }

        // The slow drift (minutes per cycle).
        float s = (t - startedAt) / 1000f;
        float dx = 16 * k * (float) Math.sin(2 * Math.PI * s / 600), dy = 10 * k * (float) Math.sin(2 * Math.PI * s / 840);
        c.save();
        c.translate(dx, dy);
        if (art != null && art.bitmap != null && alpha > 0) drawArt(c, art, alpha);
        drawClock(c);
        c.restore();
    }

    private static float ease(float x) {
        return x * x * (3 - 2 * x);
    }

    private void drawArt(Canvas c, Art a, float alpha) {
        int layer = alpha < 1 ? c.saveLayerAlpha(0, 0, w, h, Math.round(alpha * 255)) : -1;
        move.setTranslate(a.frame.centerX(), a.frame.top - 120 * k);
        spot.getShader().setLocalMatrix(move);
        c.drawRect(-40 * k, 0, w + 40 * k, h, spot);

        c.drawRect(a.frame, shadow);
        c.drawRect(a.frame, frame);
        rect.set(a.frame.left + 1.5f * k, a.frame.top + 1.5f * k, a.frame.right - 1.5f * k, a.frame.bottom - 1.5f * k);
        c.drawRect(rect, frameEdge);
        c.drawRect(a.mat, a.matPaint);
        rect.set(a.picture.left - 2 * k, a.picture.top - 2 * k, a.picture.right + 2 * k, a.picture.bottom + 2 * k);
        c.drawRect(rect, bevel);
        c.drawBitmap(a.bitmap, null, a.picture, picture);

        float x = 96 * k, base = h - 150 * k;
        c.drawText(a.label1, x, base, title);
        if (!a.label2.isEmpty()) c.drawText(a.label2, x, base + 40 * k, line);
        c.drawText(Ui.tr("ART INSTITUTE OF CHICAGO · DOMÍNIO PÚBLICO", "ART INSTITUTE OF CHICAGO · PUBLIC DOMAIN"), x, base + 78 * k, museum);
        if (layer >= 0) c.restoreToCount(layer);
    }

    private void drawClock(Canvas c) {
        now.setTime(System.currentTimeMillis());
        float x = w - 96 * k, base = h - 150 * k;
        c.drawText(timeFormat.format(now), x, base + 6 * k, time);
        c.drawText(dateLine(), x, base + 50 * k, date);
    }

    /** "Segunda-feira, 20 de outubro · 16°" (the temperature when known). */
    private String dateLine() {
        String day = dateFormat.format(now);
        day = day.substring(0, 1).toUpperCase(Ui.en ? java.util.Locale.US : Ui.PT) + day.substring(1);
        HomeData.Weather wx = data.weather;
        return Double.isNaN(wx.temp) ? day : day + "  ·  " + HomeData.degrees(wx.temp);
    }
}
