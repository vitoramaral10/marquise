package br.dev.vitormelo.marquise;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The remote's shortcut buttons in Marquise mode. On TCL TVs the system (TclShortCutKeyManager) takes them before any
 * app sees them, and most only work with the stock home screen; an accessibility service sees a key before that, so
 * here each one opens what was chosen in Preferences → Remote buttons. HomeMode turns the service on with Marquise
 * and off with Original; it asks for no window content, only the keys.
 */
public final class RemoteKeys extends AccessibilityService {
    static final String SERVICE = HomeMode.PKG + "/" + HomeMode.PKG + ".RemoteKeys";
    /** Targets that are not a package: leave the key to the TV, do nothing at all, the TV's settings, live TV. */
    static final String PASS = "pass", NOTHING = "nothing", TV_SETTINGS = "settings", LIVE_TV = "live";

    /** {key code, saved key, name, default target}. Codes read on a TCL Smart TV Pro (Android 12). */
    static final String[][] BUTTONS = {
        {"4062", "netflix", "Netflix", "com.netflix.ninja"},
        {"4099", "prime", "Prime Video", "com.amazon.amazonvideo.livingroom"},
        {"4065", "youtube", "YouTube", "com.google.android.youtube.tv"},
        {"4097", "globoplay", "Globoplay", "com.globo.globotv"},
        {"4020", "tclchannel", "TCL Channel", PASS},
        {"4141", "disney", "Disney+", "com.disney.disneyplus"},
        {String.valueOf(KeyEvent.KEYCODE_NOTIFICATION), "settings", "Settings", TV_SETTINGS},
        {"4001", "tv", "TV", LIVE_TV},
    };

    /** The button's row in BUTTONS, or -1. */
    static int button(int keyCode) {
        for (int i = 0; i < BUTTONS.length; i++) if (BUTTONS[i][0].equals(String.valueOf(keyCode))) return i;
        return -1;
    }

    /** The name shown for a button (the settings one has no brand: it's named in the language on screen). */
    static String name(int i) {
        return i == 6 ? Ui.tr("Configurações", "Settings") : BUTTONS[i][2];
    }

    @Override protected boolean onKeyEvent(KeyEvent e) {
        int i = button(e.getKeyCode());
        if (i < 0) return false;
        String target = Prefs.remoteTarget(this, BUTTONS[i][1], BUTTONS[i][3]);
        if (PASS.equals(target)) return false;
        // Both the press and the release are kept from the TV; only the first press opens.
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && !NOTHING.equals(target)) open(this, target);
        return true;
    }

    private static void open(Context c, String target) {
        Intent intent = TV_SETTINGS.equals(target) ? new Intent(Settings.ACTION_SETTINGS)
                : LIVE_TV.equals(target) ? HomeApps.liveTv(c) : HomeApps.launchIntent(c.getPackageManager(), target);
        // An app that isn't installed: its page in the store, where the stock button would also have taken you.
        if (intent == null && !LIVE_TV.equals(target)) intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + target));
        if (intent == null) return;
        try {
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException ex) {
            AppLog.w("remote button: couldn't open " + target + ": " + ex.getMessage());
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    /**
     * The shell command that puts this service in (or takes it out of) the TV's list of accessibility services,
     * keeping the others (a screen reader) as they are. Only component names go in, quoted: "$" in an inner class.
     */
    static String servicesCommand(String current, boolean on) {
        Set<String> list = new LinkedHashSet<>();
        for (String s : current == null ? new String[0] : current.trim().split(":")) {
            if (s.matches("[A-Za-z0-9._$]+/[A-Za-z0-9._$]+") && !s.equals(SERVICE) && !s.equals(HomeMode.PKG + "/.RemoteKeys")) list.add(s);
        }
        if (on) list.add(SERVICE);
        if (list.isEmpty()) return "settings delete secure enabled_accessibility_services";
        return "settings put secure enabled_accessibility_services '" + String.join(":", list) + "'"
                + (on ? " && settings put secure accessibility_enabled 1" : "");
    }
}
