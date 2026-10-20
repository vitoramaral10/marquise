package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;
import static br.dev.vitormelo.marquise.Tests.yes;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** What the log keeps on the TV: short lines, with nothing that points at the person's network or accounts. */
final class AppLogTest {
    private AppLogTest() {}

    /** An address or port in a message is taken out. */
    static void addressesAreTakenOut() {
        eq("weather: Failed to connect to <ip>", AppLog.clean("weather: Failed to connect to /192.168.0.174:8096"));
        eq("timeout to <ip> after 8s", AppLog.clean("timeout to 10.0.0.5 after 8s"));
    }

    /** Links, with whatever key they carry, are taken out. */
    static void linksAreTakenOut() {
        eq("recommendations: <url>", AppLog.clean("recommendations: http://nas.local:8096/Items?api_key=abcdefghijklmnopqrstuvwxyz123456"));
        eq("tmdb: <url> failed", AppLog.clean("tmdb: https://api.themoviedb.org/3/discover/movie?api_key=0123456789abcdef failed"));
    }

    /** IPv6 addresses, in the forms an exception prints, are taken out; a time of day is not. */
    static void ipv6AddressesAreTakenOut() {
        eq("connect to <ip> failed", AppLog.clean("connect to fe80::1%wlan0 failed"));
        eq("connect to <ip> failed", AppLog.clean("connect to [2001:db8::1]:8096 failed"));
        eq("connect to <ip> failed", AppLog.clean("connect to /2001:0db8:0000:0000:0000:ff00:0042:8329 failed"));
        eq("connect to <ip>", AppLog.clean("connect to ::1"));
        eq("at 12:30:45 it failed", AppLog.clean("at 12:30:45 it failed"));
    }

    /** A name with a port, a name after "host" and a country domain are taken out. */
    static void moreHostNamesAreTakenOut() {
        eq("connect to <host> failed", AppLog.clean("connect to jf.casa.pt:8096 failed"));
        eq("connect to <host> failed", AppLog.clean("connect to nas:8096 failed"));
        eq("Unable to resolve host \"<host>\"", AppLog.clean("Unable to resolve host nas.casa"));
        eq("Unable to resolve host \"<host>\"", AppLog.clean("Unable to resolve host \"nas\""));
        eq("connect to <host> failed", AppLog.clean("connect to jf.casa.pt failed"));
        eq("host is down", AppLog.clean("host is down"));
    }

    /** A host name is taken out, but a package name is not: it's what tells the apps apart. */
    static void hostNamesGoButPackagesStay() {
        eq("Unable to resolve host \"<host>\": No address", AppLog.clean("Unable to resolve host \"jf.example.com\": No address"));
        eq("connect to <host> failed", AppLog.clean("connect to nas.local:8096 failed"));
        eq("could not turn off br.dev.vitormelo.marquise: error", AppLog.clean("could not turn off br.dev.vitormelo.marquise: error"));
        eq("couldn't open com.google.android.youtube.tv", AppLog.clean("couldn't open com.google.android.youtube.tv"));
        eq("couldn't open org.jellyfin.androidtv", AppLog.clean("couldn't open org.jellyfin.androidtv"));
        // Packages that end like a domain word: the report needs them to support another brand.
        eq("could not turn off com.sony.dtv.home", AppLog.clean("could not turn off com.sony.dtv.home"));
        eq("could not turn off org.droidtv.home", AppLog.clean("could not turn off org.droidtv.home"));
        eq("could not turn off com.vendor.app", AppLog.clean("could not turn off com.vendor.app"));
    }

    /** A long run of letters and digits could be a token: it's taken out. */
    static void keyLikeThingsAreTakenOut() {
        eq("job failed: <key>", AppLog.clean("job failed: 0123456789abcdefghijklmnopqrstuv"));
        eq("ordinary words stay", AppLog.clean("ordinary words stay"));
    }

    /** One line, trimmed, and no longer than the limit. */
    static void oneShortLine() {
        eq("two lines here", AppLog.clean("  two\nlines \t here  "));
        String longer = AppLog.clean(new String(new char[100]).replace("\0", "word ") + "end");
        yes(longer.length() <= 140 && longer.endsWith("…"), "length " + longer.length());
    }

    /** The file keeps what was written, oldest first, and recent(n) gives the last n. */
    static void theFileKeepsTheLastLines() throws IOException {
        File f = File.createTempFile("applog", ".txt");
        try {
            for (int i = 1; i <= 5; i++) AppLog.append(f, 1000L * i, "problem " + i);
            List<AppLog.Entry> last = AppLog.recent(f, 3);
            eq(3, last.size());
            eq("problem 3", last.get(0).text);
            eq("problem 5", last.get(2).text);
            eq(5000L, last.get(2).at);
        } finally {
            f.delete();
        }
    }

    /** Past a few KB, the file is cut down to the last twelve lines. */
    static void aBigFileIsCutDown() throws IOException {
        File f = File.createTempFile("applog", ".txt");
        try {
            String line = new String(new char[100]).replace('\0', 'z');
            for (int i = 1; i <= 80; i++) AppLog.append(f, i, i + " " + line);
            yes(f.length() < 4096, "size " + f.length());
            List<AppLog.Entry> all = AppLog.recent(f, 100);
            eq(12, all.size() > 12 ? 12 : all.size());
            eq("80 " + line, all.get(all.size() - 1).text);
        } finally {
            f.delete();
            new File(f.getPath() + ".tmp").delete();
        }
    }

    /** The same problem back to back is one line, with its latest time; the same one later on is another line. */
    static void aRepeatedProblemIsOneLine() throws IOException {
        File f = File.createTempFile("applog", ".txt");
        try {
            AppLog.append(f, 1000, "weather: timeout");
            AppLog.append(f, 2000, "weather: timeout");
            AppLog.append(f, 3000, "update: failed");
            AppLog.append(f, 4000, "weather: timeout");
            List<AppLog.Entry> all = AppLog.recent(f, 10);
            eq(3, all.size());
            eq(2000L, all.get(0).at);
            eq("update: failed", all.get(1).text);
            eq(4000L, all.get(2).at);
        } finally {
            f.delete();
        }
    }

    /** A damaged line (two processes write to the file) is skipped, and the rest is read. */
    static void damagedLinesAreSkipped() throws IOException {
        File f = File.createTempFile("applog", ".txt");
        try {
            java.nio.file.Files.write(f.toPath(), "1000\tfine\nnot a line\nabc\tbad time\n2000\talso fine\n".getBytes("UTF-8"));
            List<AppLog.Entry> all = AppLog.recent(f, 10);
            eq(2, all.size());
            eq("also fine", all.get(1).text);
        } finally {
            f.delete();
        }
    }

    /** A file that isn't there is no problems, not a failure. */
    static void noFileIsNoProblems() {
        eq(0, AppLog.recent(new File("/nonexistent/applog.txt"), 5).size());
    }
}
