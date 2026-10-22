# Roadmap

Where Marquise goes next, in the order it should get there. Every merge to `main` is a release that reaches TVs by
itself, so safety comes first; then it reaches people who will never build it or open a terminal, and only then does
it grow.

This is a plan, not a promise. Items move when the reports from real TVs say they should. Want something here, or
something that isn't? Open an issue.

**Where it stands (1.17.0):** safe releases and "everything from the TV" are done in the code. What waits is what
needs people: TalkBack tried by someone who uses it, and reports from other brands. After that, the rest of "better
titles" and "reach".

| Version | What it brought |
|---|---|
| 1.10.0 | Beta channel: a PR labeled `beta` releases a pre-release, and a TV opts in in Settings → *Updates* |
| 1.11.0 | The last errors on *This TV* and in the report's QR code |
| 1.12.0 | *Preferences*: language, Celsius or Fahrenheit, Google Assistant |
| 1.13.0 | *Preferences → Factory apps*: pick from the TV which apps to turn off |
| 1.14.0 | Home works with TalkBack (and other screen readers) |
| 1.15.0 | The settings work with TalkBack |
| 1.16.0 | Jellyfin: *continue watching* and next episodes, first in each tab |
| 1.17.0 | Spanish; the remote's shortcut buttons (TCL); an HDMI input on the row; search on Home |

Not in that table because they don't ship in the app: the unit tests, the emulator check and the CI budgets on every
PR, the code of conduct and the security policy.

## What stays the same

These hold for every item below. A feature that breaks one of them doesn't ship.

- **No ads, no sponsored rows, no tracking, no backend.** Marquise talks only to the person's own server (or TMDB),
  Open-Meteo, the Art Institute of Chicago and GitHub.
- **No root, nothing uninstalled.** *Original* always brings the factory home screen back, and the watchdog does it
  by itself after a crash.
- **Light.** APK under 1 MB, Home's process around 80 MB on a real TCL, and no frame loop while nothing moves.
- **Everything with the remote.** Nothing should need a computer after the install.

## Now: waiting for people

- **TalkBack.** Done in the code: Home and the settings are nodes a screen reader can read and choose (`HomeAccess`),
  tried on the emulator with Google's TalkBack. *Done when* someone who uses it has used Home and the settings with
  the screen off. Not covered: *Move* in the app menu (it needs the arrow keys) and the audio side.
- **Compatibility, from the reports.** The *This TV* QR reports (issue template `tv_support`) feed a compatibility
  table on the install page and the brand lists in `HomeMode` (`MAKER_PREFIXES`, Live TV apps). *Done when* Sony,
  Hisense, Philips and Xiaomi each have a TV confirmed by someone who has one. The remote's shortcut-button key codes
  (`RemoteKeys`) were read on one TCL TV too; another brand's remote sends other codes.
- **Seen on a real TV.** The HDMI input on the row (the emulator has no ports) and the search on TMDB.

## Next: better titles

- **Deep links for every service TMDB lists.** Today only Netflix and Disney+ open on a search for the title; the
  others (Prime Video, Max, Globoplay…) open the app. Each link is checked on a real app before it ships, with the
  app's home as the fallback.
- **More servers.** Emby first, because its API is Jellyfin's ancestor and most of `Jellyfin.java` carries over. Then
  Plex, which needs its own sign-in (plex.tv/link with a code, like Quick Connect).
- **A row of its own for continue watching**, instead of sharing the first three posters of each tab.

## Later: reach

- **IzzyOnDroid and Obtainium.** IzzyOnDroid takes the signed APK from GitHub Releases, so no build recipe is needed;
  Obtainium follows the releases. Both are updates without Downloader.
- **F-Droid**, if the no-Gradle build can be reproduced there.
- **A recording on the install page.** A short video of the setup with the remote.
- **The install page (`docs/`) and the README in Spanish.**

## Not planned

- **The Play Store.** Switching the home screen and turning off the promo apps go through ADB; a store app can't do it.
- **Accounts, sync or a Marquise server.** Everything stays on the TV.
- **TVs without Android TV** (Samsung Tizen, LG webOS). A Google TV streamer plugged into them works.
- **Live channels guide (EPG).** That's the TV's own Live TV app; Marquise opens it.
- **A settings screen for the default app row (`shelf`).** The row is edited from Home (hold OK on an app), and
  *Restore the default row* goes back to the build's.

## Housekeeping

- Replace the Home screenshot on the install page and in the README: it still shows the old badges. This needs the
  TV with Marquise on screen.
- Upload the social preview (`.github/social-preview.png`) in Settings → Social preview on GitHub.
- Cover the TMDB and Jellyfin parsing, and the settings screens, in the emulator check (recorded responses served to
  the emulator).
