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
- **No Jellyfin? TMDB.** What's popular in your country on the streaming apps you have (Netflix, Prime Video, Disney+,
  Max, Globoplay…), each opening in its app. Your own free TMDB key, typed once on the TV.
- **Your apps, in their colors, your way.** Hold OK on an app to move it or take it off the row; hold OK on any app in
  All Apps to add it. Over-the-air TV can be one of them.
- **The time and the weather** at the top, and the forecast for the next hours one press down.
- **Light by day, dark by night**, following your city's real sunset.
- **A gallery screensaver**: framed public-domain paintings from the Art Institute of Chicago, one at a time, with the
  time; it dims at night.
- **Updates from the TV.** When a new version is out, the settings say so, and OK installs it.
- **No ads, no sponsored rows, no tracking.** It talks to your Jellyfin server (or TMDB), Open-Meteo for the weather,
  the Art Institute of Chicago for the paintings and GitHub for updates, and nothing else.
- **Safe.** No root. Nothing is uninstalled. *Original* brings the factory home screen back, and a watchdog does it by
  itself if Marquise ever crashes.

<p align="center"><img src=".github/screensaver.jpg" width="680" alt="The Gallery screensaver: a Monet in a frame with a mat, under a spotlight on a dark wall, with its museum label and the time"></p>

## Requirements

- A **Google TV or Android TV with Android 12 or newer** (TCL, Sony, Hisense, Philips, Chromecast with Google TV,
  Google TV Streamer, onn 4K, NVIDIA Shield…). Samsung, LG or another TV: plug a Google TV streamer into it.
- A **Jellyfin server** the TV can reach, and the **Jellyfin app** on the TV.

No computer needed.

## Install

About five minutes, all with the remote. A step-by-step page with pictures (in Portuguese), to send to anyone:
**[vitoramaral10.github.io/marquise](https://vitoramaral10.github.io/marquise)**.

1. **Turn on USB debugging.** *Settings → System → About →* press *Android TV OS build* 7 times. Then *Settings →
   System → Developer options →* turn on **USB debugging**. (Marquise uses it to switch the Home screen and turn off
   the promo apps; Android has no other way.)
2. **Install Downloader** from the Play Store (by AFTVnews). Open it and allow it to install apps when asked.
3. **In Downloader, type** `vitoramaral10.github.io/marquise`, press *Baixar o Marquise* (download) and install.
4. **Open Marquise.** A short setup asks where the movies and shows come from: **TMDB** (popular on your streaming
   apps, with your own free key) or your **Jellyfin** server, which it finds on the network and signs in to with
   **Quick Connect** (a code you approve on your phone) or a user name and password. Then your city. Then **Switch Marquise on**, and accept the TV's debugging prompt (tick *Always allow*).

Updates: Marquise's settings show *Update — version X available*; press OK and confirm (the first time, Android asks
to allow Marquise to install apps). Installing the new APK with Downloader, on top of the old one, works too.

### Build it yourself

You'll need a JDK 17+, the Android SDK command-line tools (build-tools 34, platform android-34) and `adb`:

```bash
git clone https://github.com/vitoramaral10/marquise && cd marquise
./build.sh                     # the first run creates config.properties
adb connect 192.168.1.50:5555  # your TV's IP; accept the prompt on the TV
adb install -r marquise.apk
```

`config.properties` can pre-fill the setup (server, API key, city) and sets what only a build can: the default app
row, the language and the extra apps to turn off. **Keep `marquise.keystore`**: updates must be signed with the same
key.

> Prefer to have an AI do it? Open the folder in [Claude Code](https://claude.com/claude-code) and say *"Install
> Marquise on my TV at 192.168.1.50. My Jellyfin is at http://192.168.1.10:8096. I live in Lisbon."*
> [CLAUDE.md](CLAUDE.md) tells it how.

## Settings

**On the TV**, in Marquise's settings (its icon, or the gear at the top of Home): the Jellyfin server and account,
where the posters come from (*Titles*: your Jellyfin server and account, or TMDB with your key and country), your
city, the Home screen (Marquise or Original), a screensaver preview and, when there is one, a new version.

**The app row:** hold OK on an app of the row to *Move* it (← →, then OK), *Remove* it or *Restore the default row*,
and hold OK on any app in *All Apps* to *Add it to Home* (up to 8). Apps you installed also get *Uninstall*, which
opens Android's own confirmation. All of that is saved on the TV and survives updates.

**When building**, `config.properties` (created by the first build, kept out of git):

| Key | What it does | Default |
|---|---|---|
| `jellyfin_url`, `jellyfin_key`, `jellyfin_user` | Pre-fill the server, an API key and the user (skips the setup) | empty |
| `shelf` | The default app row: `jellyfin`, `live`, `youtube`, `netflix`, `prime`, `disney`, `max`, `appletv`, `hulu`, `paramount`, `peacock`, `globoplay`, `spotify`, `f1tv` | `jellyfin,live,youtube,netflix,prime,disney,max,spotify` |
| `latitude`, `longitude`, `city` | Pre-fill the city | New York |
| `language` | `auto` (follows the TV), `en` or `pt` | `auto` |
| `temperature` | `C`, `F` or `auto` | `auto` |
| `disable_assistant` | Also turn off Google Assistant (the mic button stops) | `false` |
| `extra_disable` | More packages to turn off while Marquise is on, comma-separated | empty |

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
| [`Recs.java`](app/src/br/dev/vitormelo/marquise/Recs.java) | The posters: recently added on Jellyfin, or popular on TMDB |
| [`Tmdb.java`](app/src/br/dev/vitormelo/marquise/Tmdb.java) | TMDB and the streaming apps it opens |
| [`HomeApps.java`](app/src/br/dev/vitormelo/marquise/HomeApps.java) | The app row, inputs and All Apps |
| [`HomeMode.java`](app/src/br/dev/vitormelo/marquise/HomeMode.java) | Switching between Marquise and Original |
| [`Adb.java`](app/src/br/dev/vitormelo/marquise/Adb.java) | The TV's own ADB, from inside the TV |
| [`HomeData.java`](app/src/br/dev/vitormelo/marquise/HomeData.java) | Weather |
| [`DreamView.java`](app/src/br/dev/vitormelo/marquise/DreamView.java) | The screensaver (Gallery); the works are in [`gallery.json`](app/assets/gallery.json) |
| [`Updater.java`](app/src/br/dev/vitormelo/marquise/Updater.java) | Updates from GitHub releases |
| [`Watchdog.java`](app/src/br/dev/vitormelo/marquise/Watchdog.java) | The safety net |

## Privacy

No accounts, no analytics, no tracking. Marquise talks to your Jellyfin server (with the key you set) or TMDB (with
your own key), and to Open-Meteo (with the coordinates you set); the screensaver fetches paintings from artic.edu, and
the settings check GitHub for a new version. Your settings stay on your computer and your TV.

## Credits

- Weather by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0).
- Movie and show data from [TMDB](https://www.themoviedb.org/), streaming availability by JustWatch. This product uses
  the TMDB API but is not endorsed or certified by TMDB.
- Screensaver paintings: [Art Institute of Chicago](https://www.artic.edu/open-access), public domain (CC0).
- Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Font: [Inter](https://rsms.me/inter/)
  (SIL OFL 1.1).
- Not affiliated with Jellyfin, Google, TCL or any streaming service. App names and logos belong to their owners.

## License

[GPL-3.0](LICENSE) (or any later version). Use it, change it and share it; if you distribute a modified version, its
source code must be published under the same license. Improvements are welcome back as pull requests. Third-party
notices are in [NOTICE](NOTICE).
