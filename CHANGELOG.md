# Changelog

All notable changes to Marquise are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.12.0] - 2026-10-21

### Added

- Settings → *Preferences*: the language (automatic, Português or English), Celsius or Fahrenheit, and whether Marquise
  mode also turns off Google Assistant, all from the TV. Until now these needed a build with `config.properties`;
  it now only sets where they start. The language and the unit change at once, the assistant at the next switch.

## [1.11.0] - 2026-10-21

### Added

- Settings → *This TV* shows the last errors Marquise saw (the weather that didn't load, a switch that failed, an
  update that didn't go). The report's QR code carries the latest ones, so whoever has no computer can still say what
  went wrong. Addresses, links and anything key-like are taken out of what is kept; nothing leaves the TV unless
  the person scans the code and sends the report.

## [1.10.0] - 2026-10-20

### Added

- Beta releases, for whoever wants what's new sooner: Settings → *Updates* → *Beta releases too*. Everyone else keeps
  getting only stable releases. Going back to stable only keeps the beta until the next stable release is out.

### Changed

- Settings: *Updates* is always there, with the new version when there is one (OK → *Install version X*) and the
  choice between stable releases only and betas too.

## [1.9.2] - 2026-10-20

### Fixed

- CodeRabbit auto-fixes for PR #15 (#17).

## [1.9.1] - 2026-10-20

### Fixed

- Original puts back the screensaver the person had chosen (#13).

## [1.9.0] - 2026-10-20

### Added

- Quiet poster placeholders instead of the marquee card (#10).

## [1.8.0] - 2026-10-20

### Added

- Settings → *This TV*: brand, model, Android and the factory apps Marquise keeps off, and a QR code that opens a
  pre-filled GitHub report of the TV on the phone (factory home screen, screensaver, antenna input and the apps that
  came with it; never the person's own apps, accounts, network or location), so other brands can be supported with
  what they really have.

## [1.7.0] - 2026-10-20

### Added

- Over-the-air TV on more brands: the Live TV apps of Philips, Xiaomi and AOSP's Live Channels (MediaTek's already
  covered Hisense, Sharp and others).

### Changed

- Safer on brands other than TCL: Marquise never turns off a TV maker's system app unless it was checked by hand,
  is the TV's own home screen, or was asked for in `extra_disable` (a maker's screensaver can live inside an app that
  does much more).

## [1.6.0] - 2026-10-20

### Changed

- On TCL TVs where TclAppBoot blocks background jobs, switching Marquise on now lets Marquise run in the background
  by itself; no computer needed.

## [1.5.0] - 2026-10-20

### Added

- TMDB as a second source of posters, for whoever has no Jellyfin: what's popular in your country on the streaming
  apps installed on the TV (Netflix, Prime Video, Disney+, Max, Globoplay and others), with your own TMDB key and the
  country chosen on the TV. Selecting a poster opens that app, on a search for the title where the app takes one
  (Netflix, Disney+).
- Settings → *Titles* picks where the Home posters come from: Jellyfin or TMDB.

## [1.4.2] - 2026-10-20

### Fixed

- Updates: dismissing Android's install dialog no longer leaves the settings row stuck on "Installing…"; OK starts
  again.

## [1.4.1] - 2026-10-20

### Fixed

- Sunrise and sunset icons: the arrow now replaces the sun's middle ray at the same weight, and they are drawn larger
  in the weather panel.

## [1.4.0] - 2026-10-20

### Added

- Updates from the TV: the settings show "Update — version X available" when a newer release is out, and OK downloads
  it straight into Android's own installer (the first time, Android asks to allow Marquise to install apps).

### Changed

- A new screensaver, Gallery: one painting at a time from the Art Institute of Chicago's public-domain collection
  (64 curated works, CC0), framed with a mat under a spotlight, with a museum label and the time; a new work every
  75 s, a slow drift against burn-in, and the works already seen kept on disk so it also works offline. At night
  (from 22:00 to 06:30) the scene dims gradually so it doesn't light up a dark room.
- Icons redrawn from the official Material Symbols Rounded SVGs (`tools/make_symbols.sh`, pinned version): the
  partly cloudy night icon was broken, sunrise and sunset were the same, and a few were outlined among filled ones.

### Removed

- The photo screensaver, and with it the full-size backdrop downloads (only posters are fetched now).

## [1.3.0] - 2026-10-20

### Added

- The background follows the focused poster (as on Apple TV), crossfading as you move.
- The focused poster shows its whole title, on two lines when it needs them.
- Switching tabs fades the new posters in.
- No posters yet: a marquee-style card says so; before Jellyfin is set up, it's focusable and OK opens the setup.
- Posters show their colors at once (Jellyfin's BlurHash) instead of a gray card while the image loads.
- The time and weather at the top can be focused; OK opens the drawer with the forecast for the next hours.

### Changed

- A clearer focus on posters (it grows and rises more, with a thicker rim) and more contrast on the tabs.
- Much lighter and smoother on TV chips: memory with Home on screen from ~264 MB to ~120 MB, and hidden from ~240 MB
  to ~110 MB; 1 frame in 150 late instead of 1 in 20; the frame loop sleeps while nothing moves.
  - Drawing makes no objects per frame (gradients, paints and cut titles are made once).
  - Soft shadows are blurred once per size instead of on every frame (the GPU kept ~45 MB of scratch surfaces).
  - Poster corners are rounded once, when the poster is prepared, instead of clipped on every frame.
  - Hidden Home gives back its layers' GPU memory and most of the poster cache.
- APK from 1.4 MB to under 800 KB: the font is cut to Latin and the symbols Marquise draws (other scripts use the
  system font), and the settings logo is smaller.

### Removed

- Leftovers of earlier designs (the large "hero" card, the glass bar behind the apps, the corner clock capsule), unused
  symbols, strings and the ACCESS_NETWORK_STATE permission.

## [1.2.0] - 2026-10-20

### Added

- Install without a computer: a signed APK on every release (GitHub Releases), installed on the TV with Downloader.
- A setup on the TV, with the remote: finds the Jellyfin server on the network (Jellyfin's discovery, plus a quick look
  for its port on the local network) or takes a typed address; signs in with Quick Connect (a code approved on the
  phone) or a user name and password; looks up the city by name; checks USB debugging and switches Marquise on.
- Jellyfin and City rows in the settings, to change the server, the account or the city later.
- The settings screen shows who makes Marquise, a QR code to the project page, the Jellyfin server's name and
  version, this TV's model and Android version, and tips for the remote.

### Changed

- License: from MIT to GPL-3.0-or-later. Modified versions that are distributed must publish their source under the
  same license. Releases up to 1.1.0 remain available under MIT. Third-party notices moved to NOTICE.
- Settings live on the TV; config.properties only pre-fills them. Plain-HTTP Jellyfin servers work at any address
  (the build no longer needs to know it).

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
