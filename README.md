<div align="center">

<img src=".github/icon.png" width="112" alt="">

# Marquise

**A poster-first, ad-free home screen for Google TV, built around your Jellyfin library.**

What just arrived on your server, up front. Your apps, in their own colors. One button to go back.

[Install](#install) · [Settings](#settings) · [Going back](#going-back) · [Other TVs](#other-tvs) · [Português](README.pt-BR.md)

<img src=".github/screenshot.png" width="880" alt="Marquise: a greeting with the time and weather, six posters recently added to Jellyfin, and a row of app tiles in each brand's color">

</div>

## What you get

- **Your library, not an ad feed.** Six posters of what was just added to your Jellyfin server, with year, rating and
  age rating, rotating every half hour. Movies and Shows have their own tabs.
- **One press to watch.** Select a poster and it opens on that movie or show in the Jellyfin app.
- **Your apps, in their colors.** You pick which apps sit on the row, and in what order. Over-the-air TV can be one of
  them.
- **The time and the weather** at the top, and the forecast for the next hours one press down.
- **Light by day, dark by night**, following your city's real sunset.
- **A screensaver to match**: full-screen backdrops of your new titles with a big clock.
- **No ads, no sponsored rows, no tracking.** It only talks to your Jellyfin server and to Open-Meteo for the weather.
- **Safe.** No root. Nothing is uninstalled. *Original* brings the factory home screen back, and a watchdog does it by
  itself if Marquise ever crashes.

## Requirements

- A **Google TV or Android TV with Android 12 or newer** (TCL, Sony, Hisense, Philips, Chromecast with Google TV,
  Google TV Streamer, onn 4K, NVIDIA Shield…). Samsung, LG or another TV: plug a Google TV streamer into it.
- A **Jellyfin server** the TV can reach, and the **Jellyfin app** on the TV.
- A computer on the same network, with a JDK 17+, the Android SDK command-line tools and `adb`.

## Install

About ten minutes.

1. **On the TV**, turn on developer mode: *Settings → System → About →* press *Android TV OS build* 7 times. Then
   *Settings → System → Developer options →* turn on **USB debugging**. Note the TV's IP address (*Settings → Network*).
2. **Get the tools.** On a Mac:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   On Linux, get OpenJDK 17, Android's command-line tools and `adb` from your package manager, then run the same
   `sdkmanager` line. On Windows, use WSL.
3. **Get the code and build once** (this creates `config.properties`):
   ```bash
   git clone https://github.com/vitoramaral10/marquise && cd marquise
   ./build.sh
   ```
4. **Fill in `config.properties`**: your Jellyfin address and an API key (*Jellyfin Dashboard → API Keys → +*), your
   city's coordinates, and the apps you want on the row. See [Settings](#settings). Then build again: `./build.sh`.
5. **Install:**
   ```bash
   adb connect 192.168.1.50:5555      # your TV's IP; accept the prompt on the TV and tick "Always allow"
   adb install -r marquise.apk
   ```
   On some TCL TVs, also let it run in the background:
   `adb shell appops set br.dev.vitormelo.marquise AUTO_START allow`.
6. **On the TV**, open *Marquise → Home Screen → Marquise*, and accept the debugging prompt once more (Marquise uses the
   TV's own debugging connection to switch the home screen).

> Prefer to have an AI do it? Open the folder in [Claude Code](https://claude.com/claude-code) and say *"Install
> Marquise on my TV at 192.168.1.50. My Jellyfin is at http://192.168.1.10:8096. I live in Lisbon."*
> [CLAUDE.md](CLAUDE.md) tells it how.

## Settings

Everything lives in `config.properties`, created by the first build and kept out of git.

| Key | What it does | Default |
|---|---|---|
| `jellyfin_url` | Your server as the TV reaches it, e.g. `http://192.168.1.10:8096` | empty (no posters) |
| `jellyfin_key` | A Jellyfin API key | empty |
| `jellyfin_user` | Whose "recently added" (libraries and parental controls) | the server's first user |
| `shelf` | The app row, in order: `jellyfin`, `live`, `youtube`, `netflix`, `prime`, `disney`, `max`, `appletv`, `hulu`, `paramount`, `peacock`, `globoplay`, `spotify`, `f1tv` | `jellyfin,live,youtube,netflix,prime,disney,max,spotify` |
| `latitude`, `longitude` | Your city, for the weather and the sunset | New York |
| `city` | The name on the weather card | `Weather` |
| `language` | `auto` (follows the TV), `en` or `pt` | `auto` |
| `temperature` | `C`, `F` or `auto` | `auto` |
| `disable_assistant` | Also turn off Google Assistant (the mic button stops) | `false` |
| `extra_disable` | More packages to turn off while Marquise is on, comma-separated | empty |

Changed something? Run `./build.sh` and install again. **Keep `marquise.keystore`**: updates must be signed with the
same key.

A plain-HTTP Jellyfin address is fine on your network: the build allows unencrypted traffic **only** to that host.

## Going back

On the TV: *Marquise → Home Screen → Original*. The factory home screen, screensaver and every app come back exactly as
they were. If Marquise's home screen ever crashes three times in five minutes, it switches back by itself.

To remove it for good, switch to *Original* first, then uninstall Marquise (*Settings → Apps*).

## What it changes

- It becomes the home screen and the screensaver.
- It turns off, without uninstalling:
  - the stock home screen and screensaver;
  - Google TV's recovery home, Play Games, YouTube Music, Play Auto Installs (sponsored app installs) and feedback
    reports;
  - on TCL TVs, the TCL Channel, messages, store demo, hotel menu, manuals, mirroring, logging and promo apps;
  - Google Assistant, only with `disable_assistant=true`, and whatever you list in `extra_disable`.
- Picture, sound, inputs, remote pairing, Google services and the Play Store are on a protected list, checked before
  every change.

On some TVs the remote's shortcut buttons belong to the stock home screen (on TCL: Netflix, YouTube, Prime), so they
rest while Marquise is on.

## Other TVs

Marquise ships with the lists for Google TV and TCL. For another brand, add its promo apps to `DEBLOAT` and its system
pieces to `PROTECTED` in [`HomeMode.java`](app/src/br/dev/vitormelo/marquise/HomeMode.java), and its live TV app to
`LIVE_TV_APPS` in [`HomeApps.java`](app/src/br/dev/vitormelo/marquise/HomeApps.java). [CLAUDE.md](CLAUDE.md) lists the
read-only `adb` commands that tell you what your TV has. Pull requests for more brands are very welcome.

## How it works

- **A plain Android app in Java, no Gradle, no libraries.** [`build.sh`](build.sh) runs aapt2 → javac → d8 → zipalign
  → apksigner and makes a ~1 MB APK.
- **It uses the TV's own ADB, from inside the TV.** The app connects to `127.0.0.1:5555` with its own key and runs the
  same commands you would: `cmd package set-home-activity`, `settings put secure screensaver_components`,
  `pm disable-user`. Nothing listens on the network.
- **Drawn by hand on one Canvas**, so it stays at 60 fps on a TV's modest chip.
- **A safety net**: a watchdog in a separate process restores the stock home screen after three crashes in five minutes,
  and a boot check keeps your choice after TV updates.

| File | What it is |
|---|---|
| [`HomeView.java`](app/src/br/dev/vitormelo/marquise/HomeView.java) | The home screen |
| [`Recs.java`](app/src/br/dev/vitormelo/marquise/Recs.java) | Recently added, from Jellyfin |
| [`HomeApps.java`](app/src/br/dev/vitormelo/marquise/HomeApps.java) | The app row, inputs and All Apps |
| [`HomeMode.java`](app/src/br/dev/vitormelo/marquise/HomeMode.java) | Switching between Marquise and Original |
| [`Adb.java`](app/src/br/dev/vitormelo/marquise/Adb.java) | The TV's own ADB, from inside the TV |
| [`HomeData.java`](app/src/br/dev/vitormelo/marquise/HomeData.java) | Weather |
| [`DreamView.java`](app/src/br/dev/vitormelo/marquise/DreamView.java) | The screensaver |
| [`Watchdog.java`](app/src/br/dev/vitormelo/marquise/Watchdog.java) | The safety net |

## Privacy

No accounts, no analytics, no tracking. Marquise talks to your Jellyfin server (with the key you set) and to
Open-Meteo (with the coordinates you set). Your settings stay on your computer and your TV.

## Credits

- Weather by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0).
- Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Font: [Inter](https://rsms.me/inter/)
  (SIL OFL 1.1).
- Not affiliated with Jellyfin, Google, TCL or any streaming service. App names and logos belong to their owners.

## License

[MIT](LICENSE).
