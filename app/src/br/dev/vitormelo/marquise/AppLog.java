package br.dev.vitormelo.marquise;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The app's log (adb logcat -s Marquise). Never logs keys. Besides logcat, the last problems are kept on the TV, a
 * few short lines (Settings → This TV, and the report's QR code), so whoever has no computer can still say what went
 * wrong. What is kept has addresses, links and anything key-like taken out ({@link #clean}); logcat keeps the whole
 * message. "notice:" lines are the switch's own messages to the person, not problems.
 */
final class AppLog {
    private AppLog() {}

    private static final int KEEP = 12, MAX_LEN = 140;
    private static final long REPEAT_MS = 60_000;
    private static volatile File file;
    private static String lastMessage = "";
    private static long lastAt;

    /** One kept line: when it happened, and what was said. */
    static final class Entry {
        final long at;
        final String text;

        Entry(long at, String text) {
            this.at = at;
            this.text = text;
        }
    }

    static void init(Context c) {
        file = new File(c.getFilesDir(), "applog.txt");
    }

    static void w(String message) {
        Log.i("Marquise", message);
        File f = file;
        if (f == null || message == null || message.startsWith("notice:")) return;
        synchronized (AppLog.class) {
            long now = System.currentTimeMillis();
            String text = clean(message);
            if (text.equals(lastMessage) && now - lastAt < REPEAT_MS) return;   // the same thing again: once a minute
            lastMessage = text;
            lastAt = now;
            append(f, now, text);
        }
    }

    /** The last problems kept, oldest first (at most n), with a problem that repeated back to back counted once. */
    static List<Entry> recent(int n) {
        File f = file;
        return f == null ? new ArrayList<>() : recent(f, n);
    }

    // --- The file: "millis<TAB>text" lines, appended; cut down to the last KEEP once it grows.

    static void append(File f, long at, String text) {
        try {
            try (OutputStream out = new FileOutputStream(f, true)) {
                out.write((at + "\t" + text + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (f.length() > 4096) trim(f);
        } catch (IOException ignored) {
            // A log that can't be written is no reason to fail what was being logged.
        }
    }

    static List<Entry> recent(File f, int n) {
        List<Entry> all = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                try {
                    Entry e = new Entry(Long.parseLong(line.substring(0, tab)), line.substring(tab + 1));
                    // The same thing again right after itself (a weather that fails every few minutes): one line, the latest time.
                    if (!all.isEmpty() && all.get(all.size() - 1).text.equals(e.text)) all.remove(all.size() - 1);
                    all.add(e);
                } catch (NumberFormatException ignored) {
                    // A half-written line (two processes append to the same file): skipped.
                }
            }
        } catch (IOException e) {
            return new ArrayList<>();
        }
        return all.size() <= n ? all : new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    private static void trim(File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            for (Entry e : recent(f, KEEP)) out.write((e.at + "\t" + e.text + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    // --- Taking out what shouldn't be kept

    private static final Pattern URL = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+");
    private static final Pattern IP = Pattern.compile("/?\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    // A name ending in a well-known domain ("jf.example.com", "nas.local:8096"); never a package like br.dev.vitormelo.
    private static final Pattern HOST = Pattern.compile(
            "\\b[\\w-]+(?:\\.[\\w-]+)*\\.(?:com|net|org|io|dev|br|local|lan|home|app|me|xyz|info|cloud)(?::\\d+)?(?!\\.?[\\w-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{24,}");

    /** One short line without links, addresses or anything that looks like a key or token. */
    static String clean(String message) {
        String t = URL.matcher(message).replaceAll("<url>");
        t = IP.matcher(t).replaceAll("<ip>");
        t = HOST.matcher(t).replaceAll("<host>");
        t = KEY.matcher(t).replaceAll("<key>");
        t = t.replaceAll("\\s+", " ").trim();
        return t.length() > MAX_LEN ? t.substring(0, MAX_LEN - 1) + "…" : t;
    }
}
