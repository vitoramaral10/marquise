# Contributing to Marquise

Thanks for helping. Marquise is small on purpose: one Java app, no Gradle, no libraries, drawn by hand. Contributions
that keep it that way are the easiest to merge.

## Good first contributions

- **Support for another TV brand.** Add its promo apps to `DEBLOAT` and its system pieces to `PROTECTED` in
  `HomeMode.java`, and its live TV app to `LIVE_TV_APPS` in `HomeApps.java`. [CLAUDE.md](CLAUDE.md) lists the read-only
  `adb` commands that show what a TV has. Please say which model and Android version you tested on.
- **Another app on the row.** Add it to `HomeApps.SHELF` (key, name, package, color), its colors to
  `HomeView.TILE_STYLE`, and, if its TV art makes a poor logo, a PNG in `app/assets/logos/<key>.png`.
- **Translations.** On-screen text uses `Ui.tr("pt", "en")`.

## Building

```bash
./build.sh          # the first run creates config.properties
adb install -r marquise.apk
adb logcat -s Marquise
```

`./build.sh` must end with `Built marquise.apk`. CI builds every push and pull request the same way.

## Rules of the road

- **Keep the way back working.** *Original* must always restore the TV, and the watchdog must keep its job.
- **Only `pm disable-user`, never uninstall or clear data** of system apps. When unsure whether a package is safe to
  turn off, put it on the protected list instead.
- **Don't touch picture, sound, network or account settings.**
- **No keys or personal data in commits.** `config.properties` and `*.keystore` are ignored; keep it that way.
- **Performance:** the home screen runs at 60 fps on weak TV chips. No allocations in `onDraw`, no network or disk on
  the UI thread.
- Java 8 language level, minSdk 31. Comments in English, short, only where the why isn't obvious.
- Commits follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`…).

## Reporting a problem

Open an issue with your TV's brand, model and Android version, what you expected and what happened, and the output of
`adb logcat -s Marquise` around the moment it went wrong. Please remove your Jellyfin address and any key from logs.
