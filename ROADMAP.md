# Roadmap

Where Marquise goes next, in the order it should get there. Every merge to `main` is a release that reaches TVs by
itself, so the first stretch is about making that safe. Then it reaches people who will never build it or open a
terminal, and only then does it grow.

This is a plan, not a promise. Items move when the reports from real TVs say they should. Want something here, or
something that isn't? Open an issue.

## What stays the same

These hold for every item below. A feature that breaks one of them doesn't ship.

- **No ads, no sponsored rows, no tracking, no backend.** Marquise talks only to the person's own server (or TMDB),
  Open-Meteo, the Art Institute of Chicago and GitHub.
- **No root, nothing uninstalled.** *Original* always brings the factory home screen back, and the watchdog does it
  by itself after a crash.
- **Light.** APK under 1 MB, about 120 MB of memory with Home on screen, and no frame loop while nothing moves.
- **Everything with the remote.** Nothing should need a computer after the install.

## Now: safe releases (1.10–1.11)

Today a merge goes straight to every TV that accepts the update, and nothing but a human checks it first.

- ~~**Unit tests for the logic that can't fail quietly.**~~ Done: `app/test` runs on the JVM in `build.sh` (so in
  the `apk` check), and `.github/scripts/test_release.py` runs in CI. They cover:
  - `HomeMode.mayTurnOff` and the switch's targets, per brand;
  - the screensaver's restore command;
  - the TV-report link, which has to fit the QR code;
  - the TMDB services and their search links;
  - Jellyfin addresses and headers, update versions, age ratings;
  - `release.py` (`plan`, `apply`, `notes`).

  Android's classes are stubs on the JVM, so parsing (`org.json`) and anything drawn are covered by the emulator check
  below instead.
- **An emulator check on every PR.** A Google TV AVD on the CI runner (KVM). It installs the APK, switches Marquise
  on, and checks the home screen and the disabled packages. Then it switches back to Original and checks that the
  list and the screensaver are exactly as they were. A screenshot of Home goes on the PR. *Done when* this is a
  required check next to `apk` and `title`.
- **A beta channel.** A label on the PR (`beta`) releases a pre-release instead of a release. Settings → *Updates* lets
  a TV opt in to betas. Everyone else keeps getting only the latest stable release (`releases/latest`, as today).
  *Done when* a risky change can run on a couple of TVs for a few days before it reaches everyone.
- **Budgets in CI.** The build fails if the APK goes over 1 MB. The emulator check also records Home's memory and
  fails if it goes over 150 MB.
- **What went wrong, without telemetry.** Settings → *This TV* shows the last errors from `AppLog`. The QR report
  carries their summary, within the URL limit. Nothing leaves the TV unless the person scans it.

## Next: everything from the TV (1.12–1.14)

Whoever installs with Downloader has no `config.properties`. For them, anything that only a build can set doesn't
exist.

- **Every option in the settings.** Today these are build-time only: the language, Celsius or Fahrenheit, the extra
  apps to turn off (`extra_disable`), Google Assistant (`disable_assistant`) and the default row. Each one becomes a
  row in the settings. `config.properties` only pre-fills them, as it already does for Jellyfin and the city.
  *Done when* nothing in `config.example.properties` is missing from the TV.
- **TalkBack.** Home is drawn on a canvas, so a screen reader sees nothing: no posters, no apps, no tabs. An
  `AccessibilityNodeProvider` on `HomeView` and `SettingsActivity` would announce what has focus, with the title,
  year and app. *Done when* Home can be used with TalkBack on and the screen off.
- **Compatibility, from the reports.** The *This TV* QR reports (issue template `tv_support`) feed:
  - a compatibility table on the install page, with brand, model, Android version, what works and what was turned
    off;
  - the brand lists in `HomeMode` (`MAKER_PREFIXES`, Live TV apps).

  *Done when* Sony, Hisense, Philips and Xiaomi each have at least one TV confirmed by someone who has one.
- **Spanish.** Latin America has the same TVs and the same streaming apps. The strings are `Ui.en ? … : …` pairs
  inline, so a third language first needs them moved to one table per language.

## Then: better titles (1.15+)

- **Continue watching (Jellyfin).** A row of what's half-watched and the next episode (`/Users/{id}/Items/Resume`,
  `/Shows/NextUp`). OK opens the title in the Jellyfin app. What's already watched gets a mark.
- **Deep links for every service TMDB lists.** Today only Netflix and Disney+ open on a search for the title. The
  others (Prime Video, Max, Globoplay…) only open the app. Each link is checked on a real app before it ships, with
  the app's home as the fallback.
- **More servers.** Emby first, because its API is Jellyfin's ancestor and most of `Jellyfin.java` carries over.
  Then Plex, which needs its own sign-in (plex.tv/link with a code, like Quick Connect).
- **HDMI inputs on the row.** The TV's inputs (`TvInputManager`) as app tiles, with the names the person gave them.
  For a TV with a console or a cable box, the factory home is often the only way to get there.
- **Search.** One button on Home searches the person's server or TMDB and opens the title in its app.

## Later: reach

- **IzzyOnDroid and Obtainium.** IzzyOnDroid takes the signed APK from GitHub Releases, so no build recipe is needed.
  Obtainium follows the releases. Both are updates without Downloader.
- **F-Droid**, if the no-Gradle build can be reproduced there.
- **A recording on the install page.** A short video of the setup with the remote, for whoever is unsure before
  starting.

## Not planned

- **The Play Store.** Switching the home screen and turning off the promo apps go through ADB. That's how Marquise
  works, and a store app can't do it.
- **Accounts, sync or a Marquise server.** Everything stays on the TV.
- **TVs without Android TV** (Samsung Tizen, LG webOS). A Google TV streamer plugged into them works.
- **Live channels guide (EPG).** That's the TV's own Live TV app; Marquise opens it.

## Housekeeping

- Replace the Home screenshot on the install page and in the README: it still shows the old badges. This needs the
  TV.
- Upload the social preview (`.github/social-preview.png`) in Settings → Social preview on GitHub.
