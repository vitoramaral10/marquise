package br.dev.vitormelo.marquise;

import static br.dev.vitormelo.marquise.Tests.eq;

/** The remote buttons: which key is which, and the accessibility list Marquise mode edits on the TV. */
final class RemoteKeysTest {
    private RemoteKeysTest() {}

    private static final String OURS = "br.dev.vitormelo.marquise/br.dev.vitormelo.marquise.RemoteKeys";
    private static final String TALKBACK = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService";

    /** Turning on adds Marquise's service after what is there; a screen reader stays on. */
    static void turningOnKeepsTheOthers() {
        eq("settings put secure enabled_accessibility_services '" + OURS + "' && settings put secure accessibility_enabled 1",
                RemoteKeys.servicesCommand("null", true));
        eq("settings put secure enabled_accessibility_services '" + TALKBACK + ":" + OURS + "' && settings put secure accessibility_enabled 1",
                RemoteKeys.servicesCommand(TALKBACK + ":" + OURS + "\n", true));
    }

    /** Turning off takes out only Marquise's (also as Settings writes it short); nothing left, the setting goes. */
    static void turningOffTakesOutOnlyOurs() {
        eq("settings put secure enabled_accessibility_services '" + TALKBACK + "'",
                RemoteKeys.servicesCommand(OURS + ":" + TALKBACK + ":br.dev.vitormelo.marquise/.RemoteKeys", false));
        eq("settings delete secure enabled_accessibility_services", RemoteKeys.servicesCommand(OURS, false));
        eq("settings delete secure enabled_accessibility_services", RemoteKeys.servicesCommand("", false));
    }

    /** Nothing that isn't a component name reaches the shell. */
    static void onlyComponentNamesGetIn() {
        eq("settings delete secure enabled_accessibility_services", RemoteKeys.servicesCommand("a/b'; reboot", false));
        eq("settings put secure enabled_accessibility_services 'x.y/x.y.Z$Inner'", RemoteKeys.servicesCommand("x.y/x.y.Z$Inner:rm -rf", false));
    }

    /** The codes TCL's remote sends, and a key that isn't one. */
    static void theButtonsAreFoundByTheirCode() {
        eq("netflix", RemoteKeys.BUTTONS[RemoteKeys.button(4062)][1]);
        eq("settings", RemoteKeys.BUTTONS[RemoteKeys.button(83)][1]);
        eq("tv", RemoteKeys.BUTTONS[RemoteKeys.button(4001)][1]);
        eq(-1, RemoteKeys.button(19));
    }
}
