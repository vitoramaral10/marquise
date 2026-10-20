package br.dev.vitormelo.marquise;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Original ⇄ Marquise. "free": our home screen and screensaver, and the apps in the lists below turned off
 * (pm disable-user: still installed, back with one button). "original": everything as it came from the factory.
 *
 * It all goes through the TV's own ADB (Adb, 127.0.0.1:5555), which you allow once on the TV. The switches run as
 * background jobs (ModeJob) in the main process; the settings screen (the :home process) asks for them and follows the
 * state file. Only the TV itself asks for switches: nothing here listens to the network.
 */
final class HomeMode {
    private HomeMode() {}

    static final String PKG = "br.dev.vitormelo.marquise";
    static final String HOME_ACTIVITY = PKG + "/" + PKG + ".HomeActivity";
    static final String DREAM = PKG + "/" + PKG + ".Screensaver";
    static final String FREE = "free", ORIGINAL = "original";

    /** Google TV's home screen: the fallback when the TV's own one wasn't recorded. */
    static final String GOOGLE_HOME = "com.google.android.apps.tv.launcherx/.home.HomeActivity";
    /** Google TV has a RecoveryActivity that would take over the home screen without its launcher. */
    static final String GOOGLE_SETUP = "com.google.android.tungsten.setupwraith";

    /**
     * Turned off in Marquise mode, when the TV has them. Google extras first, then TV-maker extras (TCL here; add
     * your brand's from a real TV, see the README: anything not listed here, other than the TV's home screen, is left
     * alone on a maker's package, see MAKER_PREFIXES). The TV's own home screen and screensaver are added at switch time.
     */
    static final String[] DEBLOAT = {
        "com.google.android.play.games", "com.google.android.youtube.tvmusic",        // Play Games, YouTube Music
        // TCL
        "com.tcl.magiconnectfree", "com.tcl.miracast",                                // screen mirroring apps
        "com.tcl.waterfall.overseas", "com.tcl.channelplus",                          // TCL Channel and its Live TV
        "com.tcl.messagebox", "com.tcl.usercenter",                                   // TCL messages and account
        "com.tcl.exhibit", "com.tcl.esticker", "com.tcl.hotelmenu",                   // store demo, hotel menu
        "com.tcl.repairguide", "com.tcl.ocean.instructions", "com.tcl.logkit",        // repair guide, manual, logs
        "com.tcl.partnercustomizer",                                                  // promo rows for the stock home
        // Google TV
        "android.autoinstalls.config.google.gtvpai",                                  // Play Auto Installs (sponsored apps)
        "com.google.android.feedback",                                                // feedback reports
    };

    /** The voice assistant, only with disable_assistant=true in config.properties (the mic button stops working). */
    static final String[] ASSISTANT = {"com.google.android.katniss", "com.android.hotwordenrollment.okgoogle"};

    /** Never turned off, not even by mistake (picture, sound, inputs, remote, store). Checked before every disable. */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
        "com.tcl.ttvs", "com.tvos", "com.tcl.systemserver", "com.tcl.systemui.plugin", "com.tcl.tv", "com.tcl.tvinput",
        "com.tcl.hearaid", "com.dolby.android.audio.service", "com.tcl.globalkeyoverlay", "com.tcl.autopair",
        "com.tcl.UpdatePeripheral", "com.tcl.dashboard", "com.tcl.suspension", "com.tcl.providers.config",
        "com.tcl.gamebar", "com.tcl.airplay2", "com.mirakulo.astrotv.service", "com.tcl.initsetup",
        "com.tcl.useragreement", "com.tcl.factory.view", "com.tcl.guard", "com.tcl.t_solo",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending", "com.google.android.apps.mediashell",
        "com.google.android.tv.remote.service", "com.google.android.inputmethod.latin", "com.google.android.webview",
        "com.android.tv.settings", "com.android.systemui", "android", PKG));

    /**
     * TV makers' system packages, by prefix (TCL, Sony, Philips/TP Vision, Xiaomi, Hisense, Skyworth, Changhong, the
     * chip makers' TV apps, AOSP). They can hold picture, sound, inputs or the remote, so Marquise never turns one off
     * unless it is named in DEBLOAT (checked by hand), is the TV's own home screen (which Marquise replaces), or was
     * asked for in extra_disable. This is what keeps another brand safe: there, a maker's screensaver can live inside
     * a package that does much more.
     */
    private static final String[] MAKER_PREFIXES = {
        "com.tcl.", "com.tvos", "com.sony.", "org.droidtv.", "com.tpvision.", "com.philips.", "com.mitv.", "com.xiaomi.",
        "com.hisense.", "com.skyworth.", "com.changhong.", "com.mediatek.", "com.realtek.", "com.amlogic.",
        "com.nvidia.", "com.android.",
    };

    /** Google's screensaver-only apps: the one factory screensaver Marquise turns off (it replaces it). */
    private static final Set<String> GOOGLE_DREAMS = new HashSet<>(Arrays.asList(
        "com.google.android.apps.tv.dreamx", "com.google.android.backdrop"));

    /**
     * Whether Marquise may turn this package off (see PROTECTED and MAKER_PREFIXES). The TV's own screensaver is left
     * on unless it's Google's: on an unknown brand it can live inside an app that does much more, and Marquise only
     * needs to be the chosen screensaver, not to remove the old one.
     */
    static boolean mayTurnOff(String pkg, String homePkg, String dreamPkg) {
        if (PROTECTED.contains(pkg)) return false;
        if (pkg.equals(homePkg) || pkg.equals(GOOGLE_SETUP) || Arrays.asList(DEBLOAT).contains(pkg)) return true;
        for (String extra : Config.EXTRA_DISABLE.split(",")) if (pkg.equals(extra.trim())) return true;
        if (Config.DISABLE_ASSISTANT && Arrays.asList(ASSISTANT).contains(pkg)) return true;
        if (pkg.equals(dreamPkg) && !GOOGLE_DREAMS.contains(pkg)) return false;
        for (String prefix : MAKER_PREFIXES) if (pkg.startsWith(prefix)) return false;
        return true;
    }

    /** One switch at a time (jobs can overlap: a boot check and a button press). */
    private static final Object RUN = new Object();
    private static final Object FILE_LOCK = new Object();

    // MARK: - Requests (from any process)

    /**
     * Asks the main process to switch (the settings screen and the setup call this, from the screen on view). Switching
     * on first lets Marquise run in the background where the TV blocks it: TCL's TclAppBoot stops background jobs of
     * apps not on its list, which would stop the switch itself (and the watchdog). That's done from here, the app on
     * screen, which TclAppBoot doesn't block; other TVs answer "Unknown operation string", which is fine.
     */
    static void request(Context c, String mode) {
        if (!FREE.equals(mode)) {
            ModeJob.schedule(c, ModeJob.APPLY, mode, 0);
            return;
        }
        final Context app = c.getApplicationContext();
        busy(app, Ui.tr("Conectando…", "Connecting…"));
        new Thread(() -> {
            try (Adb adb = Adb.connect(app, 120_000, () -> busy(app, Ui.tr("Aceite o aviso “Permitir depuração” na TV (marque “Sempre permitir”).",
                    "Accept “Allow USB debugging?” on the TV (tick “Always allow”).")))) {
                String out = adb.shell("appops set " + PKG + " AUTO_START allow").trim();
                if (!out.isEmpty()) AppLog.w("AUTO_START: " + out);
            } catch (Exception e) {
                AppLog.w("AUTO_START: " + e.getMessage());   // the switch itself reports what went wrong
            }
            ModeJob.schedule(app, ModeJob.APPLY, mode, 0);
        }, "marquise-autostart").start();
    }

    /** Our home screen crashed 3 times in 5 minutes: give the home screen back to the TV's own (asked by :home). */
    static void requestRevertHome(Context c) {
        ModeJob.schedule(c, ModeJob.REVERT, null, 0);
    }

    /** After the TV boots (and after a TV update): wait for the system to settle and check Marquise mode. */
    static void bootCheck(Context c) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 45_000);
    }

    /** After an update of this app: complete Marquise mode if anything is missing. */
    static void verify(Context c) {
        ModeJob.schedule(c, ModeJob.VERIFY, null, 0);
    }

    // MARK: - Switches (main process, from ModeJob)

    static void applyNow(Context c, String mode) {
        synchronized (RUN) {
            if (FREE.equals(mode)) applyFree(c); else applyOriginal(c);
        }
    }

    private static void applyFree(Context c) {
        JSONObject s = state(c);
        try (Adb adb = connect(c, s)) {
            // The TV's own home screen and screensaver, recorded once (before ours replace them) so "original" restores
            // exactly what was there, on any brand.
            String home = originalHome(s);
            if (home.isEmpty() || home.startsWith(PKG + "/")) {
                String found = currentHome(adb);
                home = found != null ? realHome(found) : "";
            }
            String dream = s.optString("originalDream", "");
            if (dream.isEmpty()) dream = defaultDream(adb);
            // The screensaver the person had chosen ("null" = none, the TV's default), so "original" puts it back.
            String chosen = s.has("originalDreamSetting") ? s.optString("originalDreamSetting") : null;
            if (chosen == null) {
                String now = adb.shell("settings get secure screensaver_components").trim();
                chosen = now.contains(PKG + "/") ? "" : now;
            }
            JSONObject latest = state(c);
            latest.put("originalHome", home).put("originalDream", dream).put("originalDreamSetting", chosen);
            save(c, latest);
            busy(c, Ui.tr("Trocando o Início e a tela de descanso…", "Switching the home screen and screensaver…"));
            check(adb.shell("cmd package set-home-activity " + HOME_ACTIVITY), "Success",
                    Ui.tr("o Android não aceitou o novo Início", "Android didn't accept the new home screen"));
            adb.shell("settings put secure screensaver_components " + DREAM);
            List<String> targets = targets(home, dream);
            Set<String> disabled = new HashSet<>(stringList(state(c), "disabled"));
            Set<String> present = packages(adb, "");
            int done = 0;
            for (String pkg : targets) {
                done++;
                busy(c, Ui.tr("Desligando apps (", "Turning off apps (") + done + "/" + targets.size() + ")…");
                if (!mayTurnOff(pkg, packageOf(home), packageOf(dream)) || !present.contains(pkg)) continue;
                String out = adb.shell("pm disable-user --user 0 " + pkg);
                if (out.contains("disabled-user")) disabled.add(pkg);
                else AppLog.w("could not turn off " + pkg + ": " + out.trim());
            }
            Watchdog.clear(c);
            s = state(c);
            s.put("mode", FREE).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(sorted(disabled)));
            save(c, s);
            notice(c, Ui.tr("Marquise ligado: Início e tela de descanso novos, " + disabled.size() + " apps desligados.",
                    "Marquise is on: new home screen and screensaver, " + disabled.size() + " apps turned off."));
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        } catch (Exception e) {
            done(c, Ui.tr("Não deu para ligar o Marquise: ", "Couldn't turn on Marquise: ") + e.getMessage());
        }
    }

    private static void applyOriginal(Context c) {
        JSONObject s = state(c);
        try (Adb adb = connect(c, s)) {
            busy(c, Ui.tr("Religando os apps…", "Turning the apps back on…"));
            String home = originalHome(s);
            Set<String> toEnable = new HashSet<>(stringList(s, "disabled"));
            for (String pkg : targets(home, s.optString("originalDream", ""))) {
                if (!Arrays.asList(DEBLOAT).contains(pkg) && !Arrays.asList(ASSISTANT).contains(pkg)) toEnable.add(pkg);
            }
            Set<String> present = packages(adb, "");
            int done = 0;
            List<String> failed = new ArrayList<>();
            for (String pkg : sorted(toEnable)) {
                done++;
                busy(c, Ui.tr("Religando apps (", "Turning apps back on (") + done + "/" + toEnable.size() + ")…");
                if (!present.contains(pkg)) continue;
                // Checked: whatever didn't come back stays on the list (and in the notice); nothing disappears silently.
                String out = adb.shell("pm enable " + pkg);
                if (!out.contains("new state: enabled")) failed.add(pkg);
            }
            busy(c, Ui.tr("Devolvendo o Início original…", "Restoring the original home screen…"));
            boolean homeOk = restoreHome(adb, home);
            String chosen = s.optString("originalDreamSetting", "");
            if (chosen.matches("[A-Za-z0-9._$/,]+") && chosen.contains("/")) {
                // Quoted: a component can be an inner class ("…$Dream"), and the shell would expand the "$".
                adb.shell("settings put secure screensaver_components '" + chosen + "'");
            } else {
                adb.shell("settings delete secure screensaver_components");
            }
            s = state(c);
            s.remove("originalDreamSetting");   // back to the person's own: the next switch records it again
            s.put("mode", ORIGINAL).put("since", System.currentTimeMillis()).put("homeReverted", false)
                    .put("disabled", new JSONArray(failed));
            save(c, s);
            if (failed.isEmpty() && homeOk) {
                notice(c, Ui.tr("Modo original: Início, tela de descanso e apps de fábrica de volta.",
                        "Original mode: the factory home screen, screensaver and apps are back."));
            } else {
                List<String> problems = new ArrayList<>(failed);
                if (!homeOk) problems.add(Ui.tr("o Início original", "the original home screen"));
                notice(c, Ui.tr("Modo original, mas não consegui religar: ", "Original mode, but I couldn't turn back on: ")
                        + String.join(", ", problems) + ".");
            }
            done(c, null);
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        } catch (Exception e) {
            done(c, Ui.tr("Não deu para voltar ao original: ", "Couldn't go back to the original: ") + e.getMessage());
        }
    }

    /** The recorded home screen; without one, Google TV's; without that, the system picks (we leave the role). */
    private static boolean restoreHome(Adb adb, String home) throws IOException {
        // The recorded home was another launcher that has since been uninstalled: Google TV's comes back instead.
        if (!home.isEmpty() && !adb.shell("pm path " + packageOf(home)).contains("package:")) home = "";
        if (home.isEmpty() && adb.shell("pm path " + packageOf(GOOGLE_HOME)).contains("package:")) home = GOOGLE_HOME;
        if (!home.isEmpty()) {
            adb.shell("pm enable " + packageOf(home));
            if (adb.shell("cmd package set-home-activity " + home).contains("Success")) return true;
        }
        adb.shell("cmd role remove-role-holder --user 0 android.app.role.HOME " + PKG);
        return !homeHolder(adb).contains(PKG);
    }

    /** Safety net: our home screen crashed 3 times in a row. Only the home screen goes back; the rest stays. */
    static void revertHomeNow(Context app) {
        synchronized (RUN) {
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                JSONObject s = state(app);
                String home = originalHome(s);
                adb.shell("pm enable " + GOOGLE_SETUP);
                restoreHome(adb, home);
                String homePkg = packageOf(home);
                JSONArray kept = new JSONArray();
                for (String pkg : stringList(s, "disabled")) {
                    if (!pkg.equals(GOOGLE_SETUP) && !pkg.equals(homePkg)) kept.put(pkg);
                }
                s.put("homeReverted", true).put("disabled", kept);
                save(app, s);
                notice(app, Ui.tr("O Início do Marquise caiu 3 vezes seguidas; voltei para o Início original.",
                        "Marquise's home screen crashed 3 times in a row; I switched back to the original one."));
                adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME");
            } catch (Exception e) {
                AppLog.w("could not give the home screen back: " + e.getMessage());
            }
        }
    }

    /**
     * In Marquise mode, checks that everything is still as we left it (the listed apps off, our home screen and
     * screensaver) and completes whatever is missing, with a notice. Runs after the TV boots (TV updates can turn
     * things back on) and after an update of this app.
     */
    static void verifyNow(Context app) {
        synchronized (RUN) {
            JSONObject s = state(app);
            String fingerprint = Build.FINGERPRINT;
            boolean updated = !fingerprint.equals(s.optString("fingerprint", fingerprint));
            try {
                s.put("fingerprint", fingerprint);
                save(app, s);
            } catch (JSONException ignored) {}
            if (!FREE.equals(s.optString("mode"))) return;
            List<String> fixed = new ArrayList<>();
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                Set<String> present = packages(adb, "");
                Set<String> disabledNow = packages(adb, "-d");
                boolean reverted = s.optBoolean("homeReverted");
                String home = originalHome(s);
                Set<String> wanted = new HashSet<>(stringList(s, "disabled"));
                for (String pkg : targets(home, s.optString("originalDream", ""))) {
                    if (!reverted || !(pkg.equals(packageOf(home)) || pkg.equals(GOOGLE_SETUP))) wanted.add(pkg);
                }
                Set<String> disabled = new HashSet<>(stringList(s, "disabled"));
                for (String pkg : sorted(wanted)) {
                    if (!mayTurnOff(pkg, packageOf(home), packageOf(s.optString("originalDream", ""))) || !present.contains(pkg)) continue;
                    if (disabledNow.contains(pkg)) {
                        disabled.add(pkg);
                        continue;
                    }
                    if (adb.shell("pm disable-user --user 0 " + pkg).contains("disabled-user")) {
                        disabled.add(pkg);
                        fixed.add(pkg);
                    }
                }
                if (!reverted && !homeHolder(adb).contains(PKG)) {
                    adb.shell("cmd package set-home-activity " + HOME_ACTIVITY);
                    fixed.add(Ui.tr("o Início", "the home screen"));
                }
                if (!adb.shell("settings get secure screensaver_components").contains(DREAM)) {
                    adb.shell("settings put secure screensaver_components " + DREAM);
                    fixed.add(Ui.tr("a tela de descanso", "the screensaver"));
                }
                JSONObject latest = state(app);
                latest.put("disabled", new JSONArray(sorted(disabled)));
                save(app, latest);
            } catch (Exception e) {
                notice(app, Ui.tr("Não consegui conferir o Marquise: ", "Couldn't check Marquise: ") + e.getMessage());
                return;
            }
            if (!fixed.isEmpty()) {
                notice(app, (updated ? Ui.tr("A atualização da TV religou ", "The TV update turned back on ")
                        : Ui.tr("Desliguei o que faltava: ", "Turned off what was missing: ")) + String.join(", ", fixed) + ".");
            } else if (updated) {
                notice(app, Ui.tr("A TV foi atualizada; o Marquise continua igual.", "The TV was updated; Marquise is still on."));
            }
        }
    }

    /** What to turn off: the TV's own home screen and screensaver, Google TV's recovery home, and the lists above. */
    private static List<String> targets(String home, String dream) {
        List<String> out = new ArrayList<>();
        String homePkg = packageOf(home);
        if (homePkg != null && !homePkg.equals(PKG)) out.add(homePkg);
        out.add(GOOGLE_SETUP);
        String dreamPkg = packageOf(dream);
        if (dreamPkg != null && !dreamPkg.equals(PKG) && !dreamPkg.equals(homePkg)) out.add(dreamPkg);
        out.addAll(Arrays.asList(DEBLOAT));
        for (String pkg : Config.EXTRA_DISABLE.split(",")) {   // config.properties "extra_disable"
            if (!pkg.trim().isEmpty()) out.add(pkg.trim());
        }
        if (Config.DISABLE_ASSISTANT) out.addAll(Arrays.asList(ASSISTANT));
        return out;
    }

    /** The home screen in use now ("package/activity"): the HOME role holder, among the TV's home screens. */
    private static String currentHome(Adb adb) throws IOException {
        String holder = homeHolder(adb);
        if (holder.isEmpty() || holder.contains(PKG)) return null;
        String list = adb.shell("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME");
        for (String line : list.split("\n")) {
            line = line.trim();
            if (line.startsWith(holder + "/")) return line;
        }
        return null;
    }

    /**
     * The package that is the home screen now, or "". Some Android 12 TVs have no `cmd role get-role-holders` (it
     * answers "Unknown command"), so then we ask which activity opens HOME.
     */
    private static String homeHolder(Adb adb) throws IOException {
        String holder = adb.shell("cmd role get-role-holders --user 0 android.app.role.HOME").trim();
        if (holder.matches("[\\w.]+(;[\\w.]+)*")) return holder;
        String[] lines = adb.shell("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").trim().split("\n");
        String component = lines[lines.length - 1].trim();
        // With no default home yet, the system's chooser answers ("android/...ResolverActivity"): unknown.
        return component.contains("/") && !component.startsWith("android/") ? packageOf(component) : "";
    }

    /** The recorded home screen of the TV, as {@link #realHome}. */
    private static String originalHome(JSONObject s) {
        return realHome(s.optString("originalHome", ""));
    }

    /**
     * Some TVs (TCL, Android 14) leave the HOME role with Google TV's recovery home, while launcherx is the one that
     * answers the Home button. Treat that as launcherx, or it stays on and "original" restores the recovery screen.
     */
    private static String realHome(String home) {
        return home.startsWith(GOOGLE_SETUP + "/") ? GOOGLE_HOME : home;
    }

    /** The system's default screensaver ("package/service"), or "". */
    private static String defaultDream(Adb adb) throws IOException {
        String d = adb.shell("settings get secure screensaver_default_component").trim();
        return d.contains("/") && !d.startsWith(PKG + "/") ? d : "";
    }

    private static String packageOf(String component) {
        if (component == null || component.isEmpty()) return null;
        int slash = component.indexOf('/');
        return slash > 0 ? component.substring(0, slash) : component;
    }

    /** Packages the shell sees (all; "-d" = only the disabled ones). The app alone can't see the ones it doesn't declare. */
    private static Set<String> packages(Adb adb, String flags) throws IOException {
        Set<String> out = new HashSet<>();
        for (String line : adb.shell("pm list packages " + flags).split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) out.add(line.substring(8));
        }
        return out;
    }

    private static Adb connect(Context c, JSONObject s) throws IOException {
        busy(c, Ui.tr("Conectando…", "Connecting…"));
        Adb adb = Adb.connect(c, 120_000, () -> busy(c, Ui.tr("Aceite o aviso “Permitir depuração” na TV (marque “Sempre permitir”).",
                "Accept “Allow USB debugging?” on the TV (tick “Always allow”).")));
        if (!s.optBoolean("adbAuthorized")) {
            JSONObject latest = state(c);
            try { latest.put("adbAuthorized", true); } catch (JSONException ignored) {}
            save(c, latest);
        }
        return adb;
    }

    private static void check(String output, String expected, String error) throws IOException {
        if (!output.contains(expected)) throw new IOException(error + " (" + output.trim() + ")");
    }

    // MARK: - State (a file shared by the two processes; atomic writes)

    static JSONObject state(Context c) {
        synchronized (FILE_LOCK) {
            File f = new File(c.getFilesDir(), "home-state.json");
            if (!f.exists()) return defaults();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                return defaults();
            }
        }
    }

    private static JSONObject defaults() {
        try {
            return new JSONObject().put("mode", ORIGINAL);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void save(Context c, JSONObject s) {
        synchronized (FILE_LOCK) {
            File dir = c.getFilesDir();
            File tmp = new File(dir, "home-state.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (IOException e) {
                AppLog.w("could not save the state: " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(new File(dir, "home-state.json"))) AppLog.w("could not replace the state file");
        }
    }

    private static void busy(Context c, String text) {
        JSONObject s = state(c);
        try { s.put("busy", text).put("error", ""); } catch (JSONException ignored) {}
        save(c, s);
    }

    private static void done(Context c, String error) {
        JSONObject s = state(c);
        s.remove("busy");
        try { s.put("error", error == null ? "" : error); } catch (JSONException ignored) {}
        save(c, s);
        if (error != null) {
            AppLog.w(error);
            notice(c, error);
        }
    }

    /** A notice for the home screen (a bubble) and the settings screen. Keeps the last 20. */
    static void notice(Context c, String text) {
        AppLog.w("notice: " + text);
        JSONObject s = state(c);
        JSONArray old = s.optJSONArray("notices");
        JSONArray list = new JSONArray();
        try {
            list.put(new JSONObject().put("at", System.currentTimeMillis()).put("text", text));
            if (old != null) for (int i = 0; i < old.length() && list.length() < 20; i++) list.put(old.get(i));
            s.put("notices", list);
        } catch (JSONException ignored) {}
        save(c, s);
    }

    // MARK: - Helpers

    static List<String> stringList(JSONObject s, String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = s.optJSONArray(key);
        if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        Collections.sort(list);
        return list;
    }
}
