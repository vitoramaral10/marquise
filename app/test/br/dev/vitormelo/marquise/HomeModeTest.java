package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;
import static br.dev.vitormelo.marquise.Tests.yes;

import java.util.List;

/** What Marquise may turn off, on each brand, and what it puts back. Config comes from build.sh's test values. */
final class HomeModeTest {
    private HomeModeTest() {}

    static void protectedPackagesStayOn() {
        // Not even when they look like the home screen or were asked for: picture, sound, store, Play services.
        yes(!HomeMode.mayTurnOff("com.android.vending", "com.android.vending", ""), "the Play Store");
        yes(!HomeMode.mayTurnOff("com.google.android.gms", "", ""), "Play services");
        yes(!HomeMode.mayTurnOff("com.tcl.tv", "", ""), "TCL's TV app");
        yes(!HomeMode.mayTurnOff(HomeMode.PKG, "", ""), "Marquise itself");
    }

    static void theTvsOwnHomeGoesOffOnAnyBrand() {
        yes(HomeMode.mayTurnOff("com.sony.dtv.home", "com.sony.dtv.home", ""), "Sony's home");
        yes(HomeMode.mayTurnOff("org.droidtv.home", "org.droidtv.home", ""), "Philips' home");
        yes(HomeMode.mayTurnOff(HomeMode.GOOGLE_SETUP, "", ""), "Google TV's recovery home");
    }

    static void makerAppsStayOnUnlessCheckedByHand() {
        yes(!HomeMode.mayTurnOff("com.sony.dtv.photo", "com.sony.dtv.home", ""), "an unknown Sony app");
        yes(!HomeMode.mayTurnOff("com.hisense.promo", "", ""), "an unknown Hisense app");
        yes(!HomeMode.mayTurnOff("com.android.tv", "", ""), "AOSP's Live TV");
        yes(HomeMode.mayTurnOff("com.tcl.miracast", "", ""), "a TCL app in DEBLOAT");
    }

    static void extraDisableWinsOverTheMakerRule() {
        yes(HomeMode.mayTurnOff("com.tcl.browser", "", ""), "a maker app named in extra_disable");
        yes(HomeMode.mayTurnOff("com.example.extra", "", ""), "extra_disable, trimmed");
    }

    static void assistantGoesOffOnlyWhenAsked() {
        // The test Config has disable_assistant=true.
        yes(HomeMode.mayTurnOff("com.google.android.katniss", "", ""), "Google Assistant");
    }

    static void onlyGooglesScreensaverGoesOff() {
        yes(!HomeMode.mayTurnOff("com.vendor.ambient", "", "com.vendor.ambient"), "an unknown maker's screensaver");
        yes(HomeMode.mayTurnOff("com.google.android.apps.tv.dreamx", "", "com.google.android.apps.tv.dreamx"),
                "Google's screensaver");
    }

    static void anotherAppMayGoOff() {
        yes(HomeMode.mayTurnOff("com.google.android.youtube.tvmusic", "", ""), "YouTube Music");
    }

    static void targetsHaveTheHomeTheScreensaverAndTheLists() {
        List<String> t = HomeMode.targets("com.tcl.launcher/.Home", "com.tcl.dream/.Saver");
        eq("com.tcl.launcher", t.get(0));
        yes(t.contains(HomeMode.GOOGLE_SETUP), "Google TV's recovery home");
        yes(t.contains("com.tcl.dream"), "the screensaver");
        yes(t.containsAll(java.util.Arrays.asList(HomeMode.DEBLOAT)), "DEBLOAT");
        yes(t.contains("com.example.extra"), "extra_disable, trimmed");
        yes(t.contains("com.google.android.katniss"), "the assistant");
    }

    static void targetsLeaveMarquiseAndDuplicatesOut() {
        List<String> t = HomeMode.targets(HomeMode.HOME_ACTIVITY, HomeMode.DREAM);
        yes(!t.contains(HomeMode.PKG), "Marquise itself");
        List<String> same = HomeMode.targets("com.tcl.launcher/.Home", "com.tcl.launcher/.Dream");
        eq(same.indexOf("com.tcl.launcher"), same.lastIndexOf("com.tcl.launcher"));
    }

    static void recoveryHomeCountsAsGoogleTvsHome() {
        eq(HomeMode.GOOGLE_HOME, HomeMode.realHome(HomeMode.GOOGLE_SETUP + "/.RecoveryActivity"));
        eq("com.tcl.launcher/.Home", HomeMode.realHome("com.tcl.launcher/.Home"));
    }

    static void packageOfAComponent() {
        eq("com.tcl.launcher", HomeMode.packageOf("com.tcl.launcher/.Home"));
        eq("com.tcl.launcher", HomeMode.packageOf("com.tcl.launcher"));
        eq(null, HomeMode.packageOf(""));
        eq(null, HomeMode.packageOf(null));
    }

    static void theChosenScreensaverComesBackQuoted() {
        eq("settings put secure screensaver_components 'com.vendor.tv/.Ambient$Dream'",
                HomeMode.restoreDreamCommand("com.vendor.tv/.Ambient$Dream"));
        eq("settings put secure screensaver_components 'a.b/.C,d.e/.F'", HomeMode.restoreDreamCommand("a.b/.C,d.e/.F"));
    }

    static void anythingElseRestoresTheDefaultScreensaver() {
        String reset = "settings delete secure screensaver_components";
        eq(reset, HomeMode.restoreDreamCommand(""));
        eq(reset, HomeMode.restoreDreamCommand("null"));
        eq(reset, HomeMode.restoreDreamCommand("com.x/.D; reboot"));
        eq(reset, HomeMode.restoreDreamCommand("com.x/.D' && reboot '"));
        eq(reset, HomeMode.restoreDreamCommand("com.x/.D\nreboot"));
    }
}
