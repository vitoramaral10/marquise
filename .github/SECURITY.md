# Security policy

Marquise runs on the TV with more power than an ordinary app. It talks to the TV's own ADB to switch the home screen
and turn apps off, and it keeps the person's Jellyfin token or TMDB key. A hole here is a hole in someone's TV, so it
is treated before anything else.

## Reporting a vulnerability

**Please don't open a public issue.** Use [Report a vulnerability](https://github.com/vitoramaral10/marquise/security/advisories/new)
on GitHub (the *Security* tab). Only the maintainer sees the report.

Say what an attacker can do, what they need first (on the same network, an app on the TV, physical access), and how to
reproduce it: the TV's model and Android version, the Marquise version, and the steps or a proof of concept.

Marquise is kept by one person in their spare time. You'll get an answer within a week. A confirmed problem is fixed
in a release, which reaches TVs through the update in the settings. The advisory is then published, with credit to you
if you want it.

## Supported versions

Only the latest release. The settings offer every new version, so a fix lands where people already update.

## What matters most

- **The TV's ADB** (`Adb.java`, `HomeMode.java`):
  - anything that makes Marquise run a command it didn't write;
  - anything that lets another app or device use Marquise's approved key;
  - anything that turns off a package on the protected list.
- **Updates** (`Updater.java`): anything that gets an APK other than the project's release installed. Android only
  installs an update signed with the same key, and that check must stay in the way.
- **Secrets on the TV:** the Jellyfin token, the TMDB key and the ADB key leaving the TV, or showing up in logs, in the
  QR report or in the update request.
- **The QR report** (`TvReport.java`): carrying anything beyond the TV and its factory apps.

## Not in scope

- **USB debugging has to be on** for Marquise to work. On many TVs that also opens ADB to the local network. Android
  still asks on the screen before any new computer gets in, so accept that prompt only when Marquise asks for it.
- Problems in Jellyfin, TMDB, Open-Meteo, the Art Institute of Chicago's API, or in Android itself. Report those to
  them, though a note here is welcome if Marquise should work around it.
- A modified build signed with someone else's key.
