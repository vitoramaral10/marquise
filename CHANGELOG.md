# Changelog

All notable changes to Marquise are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- License: from MIT to GPL-3.0-or-later. Modified versions that are distributed must publish their source under the
  same license. Releases up to 1.1.0 remain available under MIT. Third-party notices moved to NOTICE.

### Added

- The settings screen shows who makes Marquise, a QR code to the project page, the Jellyfin server's name and
  version, this TV's model and Android version, and tips for the remote.

## [1.1.0] - 2026-10-19

### Added

- Edit the app row on the TV: hold OK on an app to move it, remove it or restore the default row; hold OK on any app
  in All Apps to add it to Home. Any installed app can go on the row. The order is saved on the TV.
- *Uninstall* in the same menu, for apps you installed (never the TV's system apps), through Android's own
  confirmation.

### Changed

- All Apps lists every app that isn't on the row (apps from the catalog left off the row were hidden), and no longer
  shows Marquise itself (its settings are the gear at the top of Home).
- Getting to the corner buttons: ↑ from a poster on the right half goes straight to the nearest one, ↑ from the tabs
  goes to them, and ↓ from them comes back to the tabs (before, you walked across the tabs, switching them on the way).
- App art on the row is fitted whole, with its own background color around it, instead of stretched.
- The corner Live TV button hides while Live TV is on the row.
- A full row of eight: bigger logos, and the Live TV tile's name shrinks to fit.
- A tab with fewer than six titles shows only those, without empty placeholders.

## [1.0.0] - 2026-10-19

### Added

- Home screen for Google TV and Android TV (Android 12+), with a greeting, the time and the weather at the top.
- Six posters of what was just added to your Jellyfin server, with year, rating and age rating, rotating every half
  hour, in Explore, Movies and Shows tabs. Selecting one opens it in the Jellyfin app.
- An app row in each brand's color, configurable with `shelf`, including over-the-air TV (`live`).
- A screensaver with your new titles' backdrops and a big clock.
- Light by day, dark after your city's sunset.
- A switch between Marquise and the TV's original home screen, a crash watchdog and a boot check.
- `extra_disable` to turn off more packages while Marquise is on; `disable_assistant` for Google Assistant.
- Plain-HTTP Jellyfin servers on the local network, allowed for that host only.
