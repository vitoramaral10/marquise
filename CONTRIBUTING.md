# Contributing to Marquise

Thanks for helping. Marquise is licensed under the [GPL-3.0](LICENSE): by sending a pull request you agree that your
contribution is released under it too. If you ship a modified Marquise, publish its source; better yet, send the
improvement here so everyone gets it.

Marquise is small on purpose: one Java app, no Gradle, no libraries, drawn by hand. Contributions
that keep it that way are the easiest to merge.

## Good first contributions

- **Support for another TV brand.** Add its promo apps to `DEBLOAT` and its system pieces to `PROTECTED` in
  `HomeMode.java`, and its live TV app to `LIVE_TV_APPS` in `HomeApps.java`. [CLAUDE.md](CLAUDE.md) lists the read-only
  `adb` commands that show what a TV has. Please say which model and Android version you tested on.
- **Another app on the row.** Add it to `HomeApps.SHELF` (key, name, package, color), its colors to
  `HomeView.TILE_STYLE`, and, if its TV art makes a poor logo, a PNG in `app/assets/logos/<key>.png`.
- **Translations.** On-screen text uses `Ui.tr("pt", "en")`; Spanish is looked up by the English text in
  [`Es.java`](app/src/br/dev/vitormelo/marquise/Es.java), so a new text comes with its line there (CI fails without
  it). A text made with a number or a name in it passes its Spanish as a third argument. A fourth language starts in
  `Ui.language()`.

## Building

```bash
./build.sh          # the first run creates config.properties
adb install -r marquise.apk
adb logcat -s Marquise
```

`./build.sh` must end with `Built marquise.apk`. It runs the unit tests in `app/test` first and stops if one fails;
a change to logic that runs without a TV (what gets turned off, links, versions) comes with its test there. Marquise
stays light on purpose: CI fails if the APK goes over 1 MB, or Home's memory over its budget on the emulator
(`tools/emulator_check.sh`). If a change needs more, say why in the pull request. CI builds
every push and pull request that touches the app or the build the same way, and also tests the release script
(`python3 -m unittest discover -s .github/scripts`). A change that is only docs, the CHANGELOG, the install page or
another workflow skips the build and the emulator (`.github/scripts/affects.sh` decides, from what the commit
touched): the `apk` and `emulator` checks still report, as they are required, and pass in seconds.

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

## Pull requests and releases

- Every change goes through a pull request to `main`; it's squash-merged and **its title becomes the commit**, so
  write the title as a Conventional Commit in English (`feat(home): …`, `fix(update): …`).
- That title decides the release: `feat` makes a new minor version, `fix` and `perf` a patch, a `!` (`feat!: …`) a
  major one; `docs`, `chore`, `ci`, `refactor`, `test` and `style` make none.
- If people will notice the change, add a line under `## [Unreleased]` in [CHANGELOG.md](CHANGELOG.md).
- After the merge everything else is automatic: the version in the manifest, the CHANGELOG, the tag and the signed
  APK on the releases page, which the TVs then offer as an update.
- A risky change can go out as a **beta** first: label the pull request `beta` before merging. It becomes
  `X.Y.Z-beta.N`, a pre-release that only TVs set to *Beta releases too* get; CHANGELOG's `[Unreleased]` stays for the
  stable release, which the next unlabeled merge makes.

## Reporting a problem

Open an issue with your TV's brand, model and Android version, what you expected and what happened, and the output of
`adb logcat -s Marquise` around the moment it went wrong. Please remove your Jellyfin address and any key from logs.

A security problem goes privately, not in an issue: see the [security policy](.github/SECURITY.md). Everyone here
follows the [code of conduct](.github/CODE_OF_CONDUCT.md).
