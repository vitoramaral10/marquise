package br.dev.vitormelo.marquise;

import android.graphics.Bitmap;

/**
 * Decodes a BlurHash (https://blurha.sh): the ~30-character color sketch Jellyfin sends with each image. Shown in a
 * poster's place, already in its colors, while the real image loads.
 */
final class BlurHash {
    private BlurHash() {}

    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    /** The sketch at w × h pixels (small: it is drawn stretched and smoothed), or null if the hash is not valid. */
    static Bitmap decode(String hash, int w, int h) {
        if (hash == null || hash.length() < 6) return null;
        int size = decode83(hash, 0, 1);
        if (size < 0) return null;
        int nx = size % 9 + 1, ny = size / 9 + 1;
        if (hash.length() != 4 + 2 * nx * ny) return null;
        int q = decode83(hash, 1, 2);
        float maxAc = (q + 1) / 166f;
        float[][] colors = new float[nx * ny][];
        int dc = decode83(hash, 2, 6);
        if (dc < 0 || q < 0) return null;
        colors[0] = new float[] {linear(dc >> 16), linear((dc >> 8) & 255), linear(dc & 255)};
        for (int i = 1; i < nx * ny; i++) {
            int ac = decode83(hash, 4 + i * 2, 6 + i * 2);
            if (ac < 0) return null;
            colors[i] = new float[] {signPow((ac / (19 * 19) - 9) / 9f) * maxAc, signPow(((ac / 19) % 19 - 9) / 9f) * maxAc,
                    signPow((ac % 19 - 9) / 9f) * maxAc};
        }
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0;
                for (int j = 0; j < ny; j++) {
                    float by = (float) Math.cos(Math.PI * y * j / h);
                    for (int i = 0; i < nx; i++) {
                        float basis = (float) Math.cos(Math.PI * x * i / w) * by;
                        float[] c = colors[i + j * nx];
                        r += c[0] * basis;
                        g += c[1] * basis;
                        b += c[2] * basis;
                    }
                }
                px[x + y * w] = 0xFF000000 | (srgb(r) << 16) | (srgb(g) << 8) | srgb(b);
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    private static int decode83(String s, int from, int to) {
        int v = 0;
        for (int i = from; i < to; i++) {
            int d = CHARS.indexOf(s.charAt(i));
            if (d < 0) return -1;
            v = v * 83 + d;
        }
        return v;
    }

    private static float linear(int v) {
        float x = v / 255f;
        return x <= 0.04045f ? x / 12.92f : (float) Math.pow((x + 0.055f) / 1.055f, 2.4f);
    }

    private static int srgb(float v) {
        float x = Math.max(0f, Math.min(1f, v));
        return x <= 0.0031308f ? Math.round(x * 12.92f * 255) : Math.round(((float) (1.055 * Math.pow(x, 1 / 2.4)) - 0.055f) * 255);
    }

    private static float signPow(float v) {
        return Math.copySign(v * v, v);
    }
}
