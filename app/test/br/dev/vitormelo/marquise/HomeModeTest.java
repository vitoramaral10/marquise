package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;
import static br.dev.vitormelo.marquise.Tests.yes;

import java.util.List;

/** What Marquise may turn off, on each brand, and what it puts back. Config comes from build.sh's test values. */
final class HomeModeTest {
    private HomeModeTest() {}

    /** What the build or the person asked to turn off, besides the lists: one maker app and one other. */
    private static final List<String> EXTRA = java.util.Arrays.asList("com.tcl.browser", "com.example.extra");

    /** Picture, sound, store and Play services never go off, even if asked. */
    static void protectedPackagesStayOn() {
        // Not even when they look like the home screen or were asked for: picture, sound, store, Play services.
        yes(!HomeMode.mayTurnOff("com.android.vending", "com.android.vending", "", false, EXTRA), "the Play Store");
        yes(!HomeMode.mayTurnOff("com.google.android.gms", "", "", false, EXTRA), "Play services");
        yes(!HomeMode.mayTurnOff("com.tcl.tv", "", "", false, EXTRA), "TCL's TV app");
        yes(!HomeMode.mayTurnOff(HomeMode.PKG, "", "", false, EXTRA), "Marquise itself");
    }

    /** The TV's own home screen may always go off, on any brand. */
    static void theTvsOwnHomeGoesOffOnAnyBrand() {
        yes(HomeMode.mayTurnOff("com.sony.dtv.home", "com.sony.dtv.home", "", false, EXTRA), "Sony's home");
        yes(HomeMode.mayTurnOff("org.droidtv.home", "org.droidtv.home", "", false, EXTRA), "Philips' home");
        yes(HomeMode.mayTurnOff(HomeMode.GOOGLE_SETUP, "", "", false, EXTRA), "Google TV's recovery home");
    }

    /** Unknown maker apps stay on unless they're in DEBLOAT or extra_disable. */
    static void makerAppsStayOnUnlessCheckedByHand() {
        yes(!HomeMode.mayTurnOff("com.sony.dtv.photo", "com.sony.dtv.home", "", false, EXTRA), "an unknown Sony app");
        yes(!HomeMode.mayTurnOff("com.hisense.promo", "", "", false, EXTRA), "an unknown Hisense app");
        yes(!HomeMode.mayTurnOff("com.android.tv", "", "", false, EXTRA), "AOSP's Live TV");
        yes(HomeMode.mayTurnOff("com.tcl.miracast", "", "", false, EXTRA), "a TCL app in DEBLOAT");
    }

    /** An app named in extra_disable may go off even if it isn't otherwise recognized. */
    static void extraDisableWinsOverTheMakerRule() {
        yes(HomeMode.mayTurnOff("com.tcl.browser", "", "", false, EXTRA), "a maker app named in extra_disable");
        yes(HomeMode.mayTurnOff("com.example.extra", "", "", false, EXTRA), "extra_disable, trimmed");
    }

    /** The assistant is a target only when the person asked for it (Settings → Preferences, or disable_assistant). */
    static void assistantGoesOffOnlyWhenAsked() {
        yes(HomeMode.targets("com.tcl.launcher/.Home", "", true, EXTRA).contains("com.google.android.katniss"), "asked: Google Assistant");
        yes(HomeMode.targets("com.tcl.launcher/.Home", "", true, EXTRA).contains("com.android.hotwordenrollment.okgoogle"), "asked: Ok Google");
        yes(!HomeMode.targets("com.tcl.launcher/.Home", "", false, EXTRA).contains("com.google.android.katniss"), "not asked: stays on");
        yes(!HomeMode.targets("com.tcl.launcher/.Home", "", false, EXTRA).contains("com.android.hotwordenrollment.okgoogle"), "not asked: stays on");
        yes(HomeMode.mayTurnOff("com.google.android.katniss", "", "", true, EXTRA), "it may go off when asked");
    }

    /** Only the current screensaver, and only if it's Google's, may go off. */
    static void onlyGooglesScreensaverGoesOff() {
        yes(!HomeMode.mayTurnOff("com.vendor.ambient", "", "com.vendor.ambient", false, EXTRA), "an unknown maker's screensaver");
        yes(HomeMode.mayTurnOff("com.google.android.apps.tv.dreamx", "", "com.google.android.apps.tv.dreamx", false, EXTRA),
                "Google's screensaver");
    }

    /** An unrelated app (not home, not screensaver) may still go off. */
    static void anotherAppMayGoOff() {
        yes(HomeMode.mayTurnOff("com.google.android.youtube.tvmusic", "", "", false, EXTRA), "YouTube Music");
    }

    /** targets() lists the home, the screensaver, DEBLOAT, extra_disable and the assistant. */
    static void targetsHaveTheHomeTheScreensaverAndTheLists() {
        List<String> t = HomeMode.targets("com.tcl.launcher/.Home", "com.tcl.dream/.Saver", true, EXTRA);
        eq("com.tcl.launcher", t.get(0));
        yes(t.contains(HomeMode.GOOGLE_SETUP), "Google TV's recovery home");
        yes(t.contains("com.tcl.dream"), "the screensaver");
        yes(t.containsAll(java.util.Arrays.asList(HomeMode.DEBLOAT)), "DEBLOAT");
        yes(t.contains("com.example.extra"), "extra_disable, trimmed");
        yes(t.contains("com.google.android.katniss"), "the assistant");
    }

    /** targets() never names Marquise itself, and never the same package twice. */
    static void targetsLeaveMarquiseAndDuplicatesOut() {
        List<String> t = HomeMode.targets(HomeMode.HOME_ACTIVITY, HomeMode.DREAM, true, EXTRA);
        yes(!t.contains(HomeMode.PKG), "Marquise itself");
        List<String> same = HomeMode.targets("com.tcl.launcher/.Home", "com.tcl.launcher/.Dream", true, EXTRA);
        eq(same.indexOf("com.tcl.launcher"), same.lastIndexOf("com.tcl.launcher"));
    }

    /** Google TV's recovery home counts as Google TV's own home. */
    static void recoveryHomeCountsAsGoogleTvsHome() {
        eq(HomeMode.GOOGLE_HOME, HomeMode.realHome(HomeMode.GOOGLE_SETUP + "/.RecoveryActivity"));
        eq("com.tcl.launcher/.Home", HomeMode.realHome("com.tcl.launcher/.Home"));
    }

    /** packageOf() takes the package out of a "package/activity" component. */
    static void packageOfAComponent() {
        eq("com.tcl.launcher", HomeMode.packageOf("com.tcl.launcher/.Home"));
        eq("com.tcl.launcher", HomeMode.packageOf("com.tcl.launcher"));
        eq(null, HomeMode.packageOf(""));
        eq(null, HomeMode.packageOf(null));
    }

    /** A valid component list comes back quoted, as the person had chosen. */
    static void theChosenScreensaverComesBackQuoted() {
        eq("settings put secure screensaver_components 'com.vendor.tv/.Ambient$Dream'",
                HomeMode.restoreDreamCommand("com.vendor.tv/.Ambient$Dream"));
        eq("settings put secure screensaver_components 'a.b/.C,d.e/.F'", HomeMode.restoreDreamCommand("a.b/.C,d.e/.F"));
    }

    /** Anything empty, "null" or not a plain component list resets to the system default instead. */
    static void anythingElseRestoresTheDefaultScreensaver() {
        String reset = "settings delete secure screensaver_components";
        eq(reset, HomeMode.restoreDreamCommand(""));
        eq(reset, HomeMode.restoreDreamCommand("null"));
        eq(reset, HomeMode.restoreDreamCommand("com.x/.D; reboot"));
        eq(reset, HomeMode.restoreDreamCommand("com.x/.D' && reboot '"));
        eq(reset, HomeMode.restoreDreamCommand("com.x/.D\nreboot"));
    }

    /** What the person picks (or the build lists) goes off, but a protected package never does, even if listed. */
    static void aPickedAppGoesOffButNeverAProtectedOne() {
        List<String> picked = java.util.Arrays.asList("com.sony.dtv.photo", "com.android.vending", "com.google.android.gms");
        yes(HomeMode.mayTurnOff("com.sony.dtv.photo", "", "", false, picked), "a maker app the person picked");
        yes(!HomeMode.mayTurnOff("com.android.vending", "", "", false, picked), "the Play Store, even if picked");
        yes(!HomeMode.mayTurnOff("com.google.android.gms", "", "", false, picked), "Play services, even if picked");
        yes(!HomeMode.mayTurnOff("com.sony.dtv.photo", "", "", false, java.util.Collections.<String>emptyList()), "not picked: stays on");
        yes(HomeMode.targets("com.tcl.launcher/.Home", "", false, picked).contains("com.sony.dtv.photo"), "picked is a target");
    }

    /** The picker offers neither protected packages, nor what Marquise turns off by itself, nor nonsense. */
    static void thePickerOffersOnlyWhatIsSafeToChoose() {
        yes(HomeMode.canPick("com.sony.dtv.photo"), "a maker app");
        yes(HomeMode.canPick("com.vendor.demo"), "a vendor app");
        yes(!HomeMode.canPick("com.android.vending"), "protected");
        yes(!HomeMode.canPick("com.tcl.tv"), "protected");
        yes(!HomeMode.canPick(HomeMode.PKG), "Marquise itself");
        yes(!HomeMode.canPick("com.tcl.miracast"), "already in DEBLOAT");
        yes(!HomeMode.canPick("com.google.android.katniss"), "the assistant has its own switch");
        yes(!HomeMode.canPick("com.x.y; reboot"), "not a package name");
    }
}
