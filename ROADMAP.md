# Roadmap

Where Marquise goes next, in the order it should get there. Every merge to `main` is a release that reaches TVs by
itself, so the first stretch is about making that safe. Then it reaches people who will never build it or open a
terminal, and only then does it grow.

This is a plan, not a promise. Items move when the reports from real TVs say they should. Want something here, or
something that isn't? Open an issue.

**Where it stands (1.17.0):** the safe-releases stretch and "everything from the TV" are done in the code, and from
"better titles" the continue-watching row, the HDMI inputs and the search are in. What waits is what needs people:
TalkBack tried by someone who uses it and the compatibility table (reports from other brands). Then the rest of
"better titles" (deep links, Emby, Plex) and "reach".

| Version | What it brought |
|---|---|
| 1.10.0 | Beta channel: a PR labeled `beta` releases a pre-release, and a TV opts in to betas in Settings → *Updates* |
| 1.11.0 | The last errors on *This TV* and in the report's QR code |
| 1.12.0 | Settings → *Preferences*: language, Celsius or Fahrenheit, Google Assistant |
| 1.13.0 | *Preferences* → *Factory apps*: pick from the TV which apps to turn off |
| 1.14.0 | Home works with TalkBack (and other screen readers) |
| 1.15.0 | The settings work with TalkBack |
| 1.16.0 | Jellyfin: *continue watching* and the next episodes, first in each tab |
| 1.17.0 | Spanish; the remote's shortcut buttons (TCL) open what you choose; an HDMI input on the row; search on Home |

Not in that table because they don't ship in the app: the unit tests, the emulator check on every PR, the CI budgets,
the code of conduct and the security policy.

## What stays the same

These hold for every item below. A feature that breaks one of them doesn't ship.

- **No ads, no sponsored rows, no tracking, no backend.** Marquise talks only to the person's own server (or TMDB),
  Open-Meteo, the Art Institute of Chicago and GitHub.
- **No root, nothing uninstalled.** *Original* always brings the factory home screen back, and the watchdog does it
  by itself after a crash.
- **Light.** APK under 1 MB (780 KB now), Home's process around 80 MB of memory on a real TCL (measured with other
  apps open; half of it already swapped out), and no frame loop while nothing moves.
- **Everything with the remote.** Nothing should need a computer after the install.

## Done: safe releases (1.10–1.11)

A merge goes straight to every TV that accepts the update, so what checks it first had to be more than a person
reading a diff. All four items below are in.

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
- ~~**An emulator check on every PR.**~~ Done: `tools/emulator_check.sh`, run by the `emulator` workflow on a Google
  TV emulator (Android 14, KVM), and a required check next to `apk` and `title`:
  - it installs the APK and switches Marquise on with the remote's keys, accepting the debugging prompt;
  - it checks the home screen, the screensaver and the packages turned off, with nothing protected among them;
  - it switches back to Original and checks that all three are exactly as they were;
  - it fails on any crash.

  The screenshots of Home, its memory and the log are the run's artifact.
  - Still to cover there: the TMDB and Jellyfin parsing, with recorded responses served to the emulator, and the
    settings screens (the factory apps list, for one, is checked by hand).
- ~~**A beta channel.**~~ Done: a PR labeled `beta` releases `X.Y.Z-beta.N` as a pre-release. Settings → *Updates*
  lets a TV opt in to betas, and everyone else keeps getting only the latest stable release (`releases/latest`).
- ~~**Budgets in CI.**~~ Done: the `apk` check fails if `marquise.apk` goes over 1 MB (now ~780 KB), and the
  emulator check fails if Home's process goes over 80 MB with its empty posters (~31 MB measured). Real posters on a
  TV take more: 82 MB measured on a TCL (49 MB of it already swapped out), which only a TV can measure, so that
  number stays a manual check.
- ~~**What went wrong, without telemetry.**~~ Done: `AppLog` keeps the last dozen problems in a file on the TV
  (addresses, links and key-like strings taken out), *This TV* lists the latest three, and the QR report carries the
  latest two, dropped before the app list when the link gets long. Nothing leaves the TV unless the person scans it.

## Now: everything from the TV (1.12–)

Whoever installs with Downloader has no `config.properties`. For them, anything that only a build can set doesn't
exist.

- ~~**Every option in the settings.**~~ Nearly done: Settings → *Preferences* has the language, Celsius or
  Fahrenheit, Google Assistant and *Factory apps* (`config.properties` only sets where they start, as it does for
  Jellyfin and the city). The default app row (`shelf`) stays a build setting on purpose: the row itself is edited
  from Home (hold OK on an app), and *Restore the default row* goes back to the build's.
- ~~**The remote's shortcut buttons.**~~ Done on TCL (1.17.0): their system keeps Netflix, Prime Video, YouTube,
  Globoplay, TCL Channel, Disney+, Settings and TV for the stock home screen, so Marquise reads them first with an
  accessibility service (`RemoteKeys`) and each opens what *Preferences → Remote buttons* says. The key codes were
  read on one TCL TV; another brand's remote sends other codes, which a `This TV` report can bring in.
- **TalkBack.** *Done in the code, not yet by a person who uses it.* Home and the settings were drawn on a canvas, so
  a screen reader saw nothing. `HomeView` and `SettingsView` now have an `AccessibilityNodeProvider` (`HomeAccess`):
  on Home, the tabs, the corner buttons, the time and weather, the posters, the app row, the drawer, *All Apps* and
  the app menu are nodes with a name, a place and OK, and an app of the row or of *All Apps* takes a long click (its
  menu: Remove, Uninstall, Add to Home, Restore the default row); in the settings, every row of every screen
  (the main list, *Home Screen*, *Updates*, *Preferences*, *Factory apps*) says its name and its value, OK changes it,
  and *This TV* reads as one block of text. The setup wizard uses ordinary views and was already readable. Tried on
  the emulator with Google's own TalkBack, with the arrow keys and OK; the emulator check on every PR reads the
  accessibility tree of Home and the settings. Not covered: *Move* in the app menu (it needs the arrow keys), the
  Jellyfin and notices text under the settings list, and the audio side (the emulator has none). *Done when* someone
  who uses TalkBack has used Home and the settings with the screen off.
- **Compatibility, from the reports.** The *This TV* QR reports (issue template `tv_support`) feed:
  - a compatibility table on the install page, with brand, model, Android version, what works and what was turned
    off;
  - the brand lists in `HomeMode` (`MAKER_PREFIXES`, Live TV apps).

  *Done when* Sony, Hisense, Philips and Xiaomi each have at least one TV confirmed by someone who has one.
- ~~**Spanish.**~~ Done (Latin American): *Español* is a choice in *Preferences → Language* and the automatic one when
  the TV is set to Spanish. The 182 fixed texts are in `Es.java`, found by their English; the 12 built with a number or
  a name pass theirs to `Ui.tr` as a third argument; a test in CI fails a new text without Spanish. The weather words, the
  tabs, the date and the clock follow it, and TMDB answers in `es-MX`. Not done: the install page (`docs/`) and the
  README are not translated, and the museum labels of the screensaver are the Art Institute's own English.

## Then: better titles (1.15+)

- ~~**Continue watching (Jellyfin).**~~ Done: what is half-watched (`/Users/{id}/Items/Resume`) and the next episode of
  the shows being followed (`/Shows/NextUp`) come first in each tab, up to three, one poster per show, before what
  was added lately; OK opens the title in the Jellyfin app. What's already watched gets a ✓. Tried against a real
  server on the emulator. Not done: a row of its own (with six posters per tab, it shares the first three).
- **Deep links for every service TMDB lists.** Today only Netflix and Disney+ open on a search for the title. The
  others (Prime Video, Max, Globoplay…) only open the app. Each link is checked on a real app before it ships, with
  the app's home as the fallback.
- **More servers.** Emby first, because its API is Jellyfin's ancestor and most of `Jellyfin.java` carries over.
  Then Plex, which needs its own sign-in (plex.tv/link with a code, like Quick Connect).
- ~~**HDMI inputs on the row.**~~ Done: *All Apps* lists the TV's inputs (with the names the person gave them) and one
  can be added to the row like an app. Not yet seen on a TV with a device plugged in (the emulator has no HDMI ports).
- ~~**Search.**~~ Done: a magnifier in the corner opens a search (the TV's keyboard) over the person's Jellyfin or TMDB
  and opens the title in its app. It lists titles as text, no posters; on TMDB it checks where the first dozen
  matches play.

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
  TV with Marquise on screen.
- Upload the social preview (`.github/social-preview.png`) in Settings → Social preview on GitHub.
- The CHANGELOG's 1.3.0 entry still says "~120 MB"; that was Home with real posters before the later work. It is a
  released entry, so it stays as it was.
