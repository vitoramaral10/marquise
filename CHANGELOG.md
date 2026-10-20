# Changelog

All notable changes to Marquise are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [Semantic Versioning](https://semver.org/).

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
