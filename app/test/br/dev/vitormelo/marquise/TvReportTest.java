package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;
import static br.dev.vitormelo.marquise.Tests.yes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The report link stays short enough for a phone to read its QR code off the TV. */
final class TvReportTest {
    private TvReportTest() {}

    private static final String TV = "TCL 55C735 (TCL, x55) · Android 12 (API 31) · Marquise 1.9.1";
    private static final String HOME = "home: com.tcl.launcher/.Home\nscreensaver: ?\ntuner: none";

    static void aShortListGoesWhole() {
        String link = TvReport.fit("TCL 55C735", TV, HOME, Arrays.asList("com.tcl.a", "com.tcl.b"), "(cut)");
        yes(link.startsWith("https://github.com/vitoramaral10/marquise/issues/new?template=tv_support.yml&title="), link);
        yes(link.contains("&packages=com.tcl.a%0Acom.tcl.b"), "both apps: " + link);
        yes(!link.contains("&notes="), "no note when nothing was cut");
        yes(!link.contains("+"), "spaces as %20, not +");
    }

    static void aLongListIsCutToFit() {
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < 80; i++) apps.add("com.maker.app" + i);
        String link = TvReport.fit("TCL 55C735", TV, HOME, apps, "(cut)");
        yes(link.length() <= TvReport.MAX_URL, "length " + link.length());
        yes(link.contains("com.maker.app0%0A"), "keeps the first apps");
        yes(link.contains("%0A%E2%80%A6"), "marks the cut with …");
        yes(link.endsWith("&notes=%28cut%29"), "says it was cut: " + link);
        eq(80, apps.size());
    }

    static void neverUnderThreeApps() {
        List<String> apps = new ArrayList<>();
        for (int i = 0; i < 5; i++) apps.add("com.maker." + new String(new char[120]).replace('\0', 'x') + i);
        String link = TvReport.fit("TCL", TV, HOME, apps, "(cut)");
        yes(link.contains("x0%0A") && link.contains("x1%0A") && link.contains("x2%0A"), "three apps left");
        yes(!link.contains("x3"), "the fourth is cut");
    }
}
