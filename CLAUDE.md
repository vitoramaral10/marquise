# Marquise: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one: posters of what was just added to the person's
Jellyfin server, their apps in brand colors, the time and the weather. A settings app switches between Marquise and
the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

The quick way needs no computer: USB debugging on, Downloader from the Play Store, the release APK
(`github.com/vitoramaral10/marquise/releases/latest/download/marquise.apk`), then Marquise's own setup on the TV
(server found on the network, Quick Connect, city). The steps below are for building it yourself.

1. **Ask** for the TV's IP address, their Jellyfin address (as the TV reaches it) and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34), adb and zip. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools, adb and zip from the package manager, then the same `sdkmanager`
     line.
   - Windows: WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `jellyfin_url`, `latitude` and
   `longitude` (decimal degrees), `city`, `temperature`, and `shelf` with the apps they use (in their order).
4. **The Jellyfin API key is theirs and secret.** Ask them to create one (Jellyfin Dashboard → API Keys → +) and paste
   it after `jellyfin_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never
   commit it. If they paste it in the chat anyway, put it only in `config.properties`. Without a key everything works
   except the posters. `jellyfin_user` picks whose libraries show (empty = the first user).
5. **Build:** `./build.sh` → `marquise.apk`. Keep `marquise.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r marquise.apk`. On TCL TVs that have `TclAppBoot`, also
   `adb shell appops set br.dev.vitormelo.marquise AUTO_START allow` (it blocks background jobs by default, so the
   switch and the watchdog never run; the log shows `forbid bind service ... ModeJob`). TVs without it answer
   "Unknown operation string": that's fine.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On some TVs the remote's shortcut
   buttons belong to the stock home screen (TCL: Netflix, YouTube, Prime), so they rest while Marquise is on.
8. **They switch on the TV:** *Marquise* → *Home Screen* → *Marquise*. The app uses the TV's own ADB on 127.0.0.1 with
   its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
9. **Check:** `adb logcat -s Marquise` shows what the switch did.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME     # "Unknown command" on some Android 12 TVs: fine
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
  Personal choices (an app they just don't use) go in their `extra_disable`, not here.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it.
- `HomeApps.LIVE_TV_APPS` and `HIDDEN`: the brand's live TV app, apps to hide from All Apps.
- The row's order lives on the TV (`HomeApps.shelfOrder`, edited by holding OK on the home screen); `shelf` in
  `config.properties` is only the default, and *Restore the default row* goes back to it.
- `HomeApps.SHELF` is the catalog of apps the row knows (key, name, package, color...). A new streaming app goes there,
  plus a color in `HomeView.TILE_STYLE` and, if the app's TV art makes a poor logo, a PNG in `app/assets/logos/<key>.png`.
- Build, install, have them switch on the TV, read `adb logcat -s Marquise`, then have them switch back to *Original*
  once and confirm everything returned.

TVs that don't run Google TV or Android TV (Samsung, LG and others): the way is a Google TV streamer on HDMI.

**Uninstalled while in Marquise mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list, `extra_disable` and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 31 (Android 12), targetSdk 33. No Gradle, no libraries: only the Android SDK.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky use AGSL shaders (`Shaders`,
  Android 13+, with a flat fallback for 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- `Recs` fetches Jellyfin's `/Users/{id}/Items/Latest` and keeps the posters on disk.
  "Watch" launches the Jellyfin app with the `ItemId` extra.
- The screensaver (`Screensaver` → `DreamView`) is a gallery: the curated works in `assets/gallery.json` (Art
  Institute of Chicago, CC0), fetched through IIIF at screen size and kept in `cache/gallery`. It redraws every 10 s
  and only animates during the fade; keep it that way.
- `Updater` asks GitHub for the latest release and installs `marquise.apk` through `PackageInstaller`;
  `UpdateReceiver` gets the installer's answer. Bump `versionCode` and `versionName` in the manifest for every release.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home` process.
  `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text is bilingual, `Ui.tr("pt", "en")`: add both. Comments in English, short, only where the why isn't
  obvious.
- What's chosen on the TV (server, token, user, city) lives in `Prefs`; `config.properties` only gives defaults.
  `SetupActivity` is the first-run wizard (standard views, so the TV keyboard works); `Jellyfin` finds servers and
  signs in (Quick Connect or password).
- Releases: push a tag `vX.Y.Z`; `.github/workflows/release.yml` signs with the repository secrets
  (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`) and publishes the APK with that version's CHANGELOG section.
- Settings come from `config.properties` through the generated `out/gen/br/dev/vitormelo/marquise/Config.java` (never
  edit that).
- Art: `python3 tools/make_art.py` draws the icon, the TV banner and the repository images.
  `tools/make_symbols.sh` renders `assets/symbols` from the pinned Material Symbols SVGs (sunrise and sunset come
  from `tools/symbols/`); add an icon to its map, never hand-edit the PNGs.
- After any change, `./build.sh` must end with `Built marquise.apk`.

## Always

- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so *Original*
  can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
