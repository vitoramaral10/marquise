package br.dev.vitormelo.marquise;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * A recommendation's images, built once in the background from its poster:
 *  - the poster card (center-cropped to 2:3, already at the focused card size), with its rounded corners baked in;
 *  - the frosted version (192×108, heavily blurred) of the same poster: the background and the bottom drawer's
 *    frosted glass;
 *  - the Liquid Glass version (480×270, lightly blurred): what shows through the glass buttons;
 *  - both blended with white (by day) and with near-black (at night).
 * The card lives only in GPU memory (HARDWARE): drawing it costs one texture, and the process doesn't hold its pixels.
 */
final class Backdrop {
    static final int W = 1920, H = 1080, FW = 192, FH = 108, GW = 480, GH = 270;
    /** A poster card at its focused size (1.05× of 267×400, 2:3). */
    static final int POSTER_W = 280, POSTER_H = 420;
    final int[] frost;                  // heavily blurred (frosted glass, bottom drawer)
    final Bitmap tall;                  // the poster card
    final int[] lightGlass;             // by day: the glass version, lightened
    final Bitmap lightImage;            // by day: the background (the lightened frosted version, scaled up on screen)
    final int[] nightFrost, nightGlass; // at night: frosted (the bottom drawer's frosted glass) and glass, darkened
    final Bitmap nightImage;            // at night: the background (the darkened frosted version)

    private Backdrop(int[] frost, Bitmap tall, int[] light, int[] lightGlass, int[] night, int[] nightGlass) {
        this.frost = frost;
        this.tall = tall;
        this.lightGlass = lightGlass;
        this.nightFrost = night;
        this.nightGlass = nightGlass;
        lightImage = Bitmap.createBitmap(light, FW, FH, Bitmap.Config.ARGB_8888);
        nightImage = Bitmap.createBitmap(night, FW, FH, Bitmap.Config.ARGB_8888);
    }

    /** The card is the whole poster (never cropped to 16:9); the frosted versions come from the same poster. */
    static Backdrop make(Bitmap src) {
        Bitmap tall = rounded(card(src, POSTER_W, POSTER_H), POSTER_W * 18f / 267);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint photo = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        float sw = src.getWidth() * scale, sh = src.getHeight() * scale;
        c.drawBitmap(src, null, new RectF((W - sw) / 2, (H - sh) / 2, (W + sw) / 2, (H + sh) / 2), photo);
        src.recycle();
        int[] frost = reduce(out, FW, FH, 3, 3);
        int[] light = whiten(frost.clone(), 0.82f);
        int[] glass = reduce(out, GW, GH, 2, 2);
        int[] lightGlass = whiten(glass.clone(), 0.74f);
        int[] night = darken(frost.clone(), 0.76f);
        int[] nightGlass = darken(glass, 0.56f);
        out.recycle();
        return new Backdrop(frost, tall, light, lightGlass, night, nightGlass);
    }


    /** The center of the photo at the card's aspect ratio, filtered down (halving while possible) to the exact size. */
    private static Bitmap card(Bitmap full, int w, int h) {
        float ratio = (float) w / h;
        int cw = Math.min(full.getWidth(), Math.round(full.getHeight() * ratio));
        int ch = Math.min(full.getHeight(), Math.round(cw / ratio));
        Bitmap b = Bitmap.createBitmap(full, (full.getWidth() - cw) / 2, (full.getHeight() - ch) / 2, cw, ch);
        while (b.getWidth() / 2 >= w) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full && b != out) b.recycle();
        // Already the right size (a poster decoded at the card size): gpu() recycles its input, and the caller still
        // needs the original.
        if (out == full) out = full.copy(Bitmap.Config.ARGB_8888, false);
        return gpu(out);
    }

    /**
     * The card with its corners cut round (transparent), once: the home screen then draws it without clipping. `radius`
     * matches the card's on-screen corner (18 px on a 267 px wide poster).
     */
    private static Bitmap rounded(Bitmap card, float radius) {
        Bitmap soft = card.getConfig() == Bitmap.Config.HARDWARE ? card.copy(Bitmap.Config.ARGB_8888, false) : card;
        Bitmap out = Bitmap.createBitmap(soft.getWidth(), soft.getHeight(), Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new android.graphics.BitmapShader(soft, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(new RectF(0, 0, out.getWidth(), out.getHeight()), radius, radius, p);
        if (soft != card) soft.recycle();
        card.recycle();
        return gpu(out);
    }

    /** Copies it to GPU memory (cheap to draw; the process doesn't keep the pixels). */
    static Bitmap gpu(Bitmap b) {
        Bitmap g = b.copy(Bitmap.Config.HARDWARE, false);
        if (g == null) return b;
        b.recycle();
        return g;
    }

    /** Blends with a near-black (the darkest gray of Apple's dark-mode backgrounds), keeping the photo's tint. */
    private static int[] darken(int[] px, float amount) {
        float keep = 1 - amount, base = 11 * amount;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * keep + base);
            int g = Math.round(((p >> 8) & 255) * keep + base);
            int b = Math.round((p & 255) * keep + base + 2 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    private static int[] whiten(int[] px, float amount) {
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = Math.round(((p >> 16) & 255) * (1 - amount) + 255 * amount);
            int g = Math.round(((p >> 8) & 255) * (1 - amount) + 255 * amount);
            int b = Math.round((p & 255) * (1 - amount) + 255 * amount);
            px[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return px;
    }

    /** Halved at each step (a true average, no aliasing), then blurred (box passes of radius r). */
    private static int[] reduce(Bitmap full, int w, int h, int radius, int passes) {
        Bitmap b = full;
        while (b.getWidth() / 2 >= w * 1.5f) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != full) b.recycle();
            b = half;
        }
        Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != full) b.recycle();
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < passes; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        return px;
    }

    /** Moving average of radius r in one direction, repeating the edge pixels. */
    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        float k = 1f / (2 * r + 1);
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            float sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[base + Math.max(0, Math.min(n - 1, i)) * step];
                sr += (p >> 16) & 255;
                sg += (p >> 8) & 255;
                sb += p & 255;
            }
            for (int i = 0; i < n; i++) {
                out[base + i * step] = 0xFF000000 | (Math.round(sr * k) << 16) | (Math.round(sg * k) << 8) | Math.round(sb * k);
                int add = in[base + Math.min(n - 1, i + r + 1) * step], sub = in[base + Math.max(0, i - r) * step];
                sr += ((add >> 16) & 255) - ((sub >> 16) & 255);
                sg += ((add >> 8) & 255) - ((sub >> 8) & 255);
                sb += (add & 255) - (sub & 255);
            }
        }
    }

    /** Average luminance (0 to 1) of a rectangle given as fractions of the image, by sampling. */
    private static float luminance(Bitmap b, float x0, float y0, float x1, float y1) {
        float sum = 0;
        int count = 0;
        for (int j = 0; j < 24; j++) {
            for (int i = 0; i < 32; i++) {
                int x = Math.round((x0 + (x1 - x0) * (i + 0.5f) / 32) * (b.getWidth() - 1));
                int y = Math.round((y0 + (y1 - y0) * (j + 0.5f) / 24) * (b.getHeight() - 1));
                int p = b.getPixel(x, y);
                sum += (0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255)) / 255f;
                count++;
            }
        }
        return sum / count;
    }
}
