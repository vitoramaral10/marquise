package br.dev.vitormelo.marquise;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fonts and text for the home screen, the screensaver and the settings. Font: Inter (variable, assets/fonts, SIL OFL),
 * with the "display" optical size for large text and the "text" size for small text; without it, the system font at a
 * similar weight.
 */
final class Ui {
    private Ui() {}

    static final Locale PT = new Locale("pt", "BR");
    private static final Locale ES = new Locale("es", "MX");
    /** "en", "pt" or "es": from Settings → Preferences or config.properties ("language"), or, with "auto", the TV's own language. */
    static volatile String lang = language();
    /** English (its own words and formats: 12 h clock, US dates). Portuguese and Spanish share the other formats. */
    static volatile boolean en = lang.equals("en");
    static volatile boolean es = lang.equals("es");
    static Typeface thin, light, displayMedium, displaySemibold, regular, medium, semibold;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = font(a, 32, 200);
        light = font(a, 32, 300);
        displayMedium = font(a, 32, 500);
        displaySemibold = font(a, 32, 600);
        regular = font(a, 14, 400);
        medium = font(a, 14, 500);
        semibold = font(a, 14, 600);
    }

    /** Inter at an optical size (14 = text, 32 = display) and weight; the system font if the file is missing. */
    private static Typeface font(AssetManager a, int opticalSize, int weight) {
        if (has(a, FONT)) {
            try {
                Typeface t = new Typeface.Builder(a, FONT)
                        .setFontVariationSettings("'opsz' " + opticalSize + ", 'wght' " + weight).build();
                if (t != null) return t;
            } catch (RuntimeException ignored) {
            }
        }
        return Typeface.create(Typeface.DEFAULT, weight, false);
    }

    private static final String FONT = "fonts/InterVariable.ttf";

    /**
     * "en"/"pt"/"es" (Settings → Preferences, or config.properties), or "auto": Portuguese or Spanish when the TV is set
     * to one of them, English otherwise.
     */
    static String language() {
        String chosen = Prefs.language.trim().toLowerCase(Locale.ROOT);
        if (chosen.startsWith("pt")) return "pt";
        if (chosen.startsWith("es")) return "es";
        if (chosen.startsWith("en")) return "en";
        String tv = Locale.getDefault().getLanguage();
        return "pt".equals(tv) ? "pt" : "es".equals(tv) ? "es" : "en";
    }

    /** Reads the language again (the person changed it, or a process started). */
    static void refreshLanguage() {
        lang = language();
        en = lang.equals("en");
        es = lang.equals("es");
    }

    /** The locale of the words and numbers of the current language (month names, decimal comma, country names). */
    static Locale local() {
        return en ? Locale.US : es ? ES : PT;
    }

    private static boolean has(AssetManager a, String path) {
        try (InputStream in = a.open(path)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the temperatures don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /** The Portuguese or the English text; in Spanish, the English text's line in {@link Es} (English if it has none). */
    static String tr(String pt, String english) {
        return en ? english : es ? Es.of(english) : pt;
    }

    /** For a text made with a number or a name in it, which can't be looked up: the Spanish is given at the call. */
    static String tr(String pt, String english, String spanish) {
        return en ? english : es ? spanish : pt;
    }

    /** "21:47" (in English, "9:47", like the iPhone in the US). */
    static String clock(long now) {
        return en ? new SimpleDateFormat("h:mm", Locale.US).format(new Date(now))
                : new SimpleDateFormat("HH:mm", local()).format(new Date(now));
    }

    /** "5:21" (sunrise and sunset; in English, "5:21 AM"). */
    static String time(long at) {
        return en ? new SimpleDateFormat("h:mm a", Locale.US).format(new Date(at))
                : new SimpleDateFormat("H:mm", local()).format(new Date(at));
    }

    /** "domingo, 18 de outubro" (lowercase, like the iPhone in Portuguese; "domingo, 18 de octubre" in Spanish); "Sunday, October 18" in English. */
    static String date(long now) {
        return en ? new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date(now))
                : new SimpleDateFormat("EEEE, d 'de' MMMM", local()).format(new Date(now));
    }

    /** Spinning arc (work in progress). */
    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
