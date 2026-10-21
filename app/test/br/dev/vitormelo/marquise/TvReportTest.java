package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;
import static br.dev.vitormelo.marquise.Tests.yes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The report link stays short enough for a phone to read its QR code off the TV. */
final class TvReportTest {
    private TvReportTest() {}

    private static final String TV = "TCL 55C735 (TCL, x55) · Android 12 (API 31) · Marquise 1.9.1";
    private static final List<String> NONE = Collections.emptyList();
    private static final String HOME = "home: com.tcl.launcher/.Home\nscreensaver: ?\ntuner: none";

    /** A short app list fits whole, with no cut note and spaces properly percent-encoded. */
    static void aShortListGoesWhole() {
        String link = TvReport.fit("TCL 55C735", TV, HOME, Arrays.asList("com.tcl.a", "com.tcl.b"), NONE, "(cut)");
        yes(link.startsWith("https://github.com/vitoramaral10/marquise/issues/new?template=tv_support.yml&title="), link);
        yes(link.contains("&packages=com.tcl.a%0Acom.tcl.b"), "both apps: " + link);
        yes(!link.contains("&notes="), "no note when nothing was cut");
        yes(!link.contains("+"), "spaces as %20, not +");
    }

    /** A long app list is cut from the end until the link fits MAX_URL, keeping the first apps and a cut note. */
    static void aLongListIsCutToFit() {
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < 80; i++) apps.add("com.maker.app" + i);
        String link = TvReport.fit("TCL 55C735", TV, HOME, apps, NONE, "(cut)");
        yes(link.length() <= TvReport.MAX_URL, "length " + link.length());
        yes(link.contains("com.maker.app0%0A"), "keeps the first apps");
        yes(link.contains("%0A%E2%80%A6"), "marks the cut with …");
        yes(link.endsWith("&notes=%28cut%29"), "says it was cut: " + link);
        eq(80, apps.size());
    }

    /** Even when the link is still too long, the cut never drops the list below three apps. */
    static void neverUnderThreeApps() {
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < 5; i++) apps.add("com.maker." + new String(new char[120]).replace('\0', 'x') + i);
        String link = TvReport.fit("TCL", TV, HOME, apps, NONE, "(cut)");
        yes(link.contains("x0%0A") && link.contains("x1%0A") && link.contains("x2%0A"), "three apps left");
        yes(!link.contains("x3"), "the fourth is cut");
    }

    /** The recent problems go into the form's "errors" field, oldest first, when the link has room. */
    static void errorsGoInWhenThereIsRoom() {
        String link = TvReport.fit("TCL", TV, HOME, Arrays.asList("com.tcl.a"), Arrays.asList("weather: timeout", "update: failed"), "(cut)");
        yes(link.contains("&errors=weather%3A%20timeout%0Aupdate%3A%20failed"), link);
        yes(!link.contains("&notes="), "nothing was cut");
    }

    /** Over MAX_URL, the oldest problems go first; the latest stay and the apps are whole. */
    static void theOldestProblemsGoFirst() {
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < 6; i++) apps.add("com.maker.app" + i);
        List<String> errors = new ArrayList<>();
        for (int i = 1; i <= 5; i++) errors.add("e" + i + new String(new char[120]).replace('\0', 'x'));
        String link = TvReport.fit("TCL", TV, HOME, apps, errors, "(cut)");
        yes(link.length() <= TvReport.MAX_URL, "length " + link.length());
        yes(!link.contains("e1xx") && !link.contains("e2xx"), "the oldest are gone: " + link);
        yes(link.contains("e5xx") && link.contains("e3xx"), "the latest stay: " + link);
        yes(link.contains("com.maker.app5%0A") == false && link.contains("com.maker.app5"), "the apps are whole: " + link);
        yes(!link.contains("&notes="), "no app was cut");
        eq(5, errors.size());
    }

    /** With no room even for three apps, the last problem is dropped too. */
    static void theLastProblemGoesAfterTheApps() {
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < 5; i++) apps.add("com.maker." + new String(new char[200]).replace('\0', 'x') + i);
        String link = TvReport.fit("TCL", TV, HOME, apps, Arrays.asList("weather: timeout"), "(cut)");
        yes(!link.contains("&errors="), "no errors left: " + link);
    }
}
