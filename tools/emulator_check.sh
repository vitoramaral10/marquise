#!/usr/bin/env bash
# The emulator check: Marquise switched on and back off on a Google TV emulator, as a person would with the remote.
#
#   tools/emulator_check.sh SERIAL APK OUT_DIR
#
# Needs a booted Google TV emulator (CI: .github/workflows/emulator.yml) that has never approved Marquise's debugging
# key. It installs the APK, picks Marquise in the settings, accepts Android's "Allow USB debugging?" with "Always
# allow" (this emulator only), and checks the home screen, the screensaver and the apps turned off. Then it picks
# Original and checks that all three are back exactly as they were. Screenshots, the memory of Home and the log go to
# OUT_DIR. Any difference or crash fails the check.
set -euo pipefail

SERIAL=$1 APK=$2 OUT=$3
PKG=br.dev.vitormelo.marquise
GOOGLE_HOME=com.google.android.apps.tv.launcherx/.home.HomeActivity
PERSONS_DREAM=com.android.dreams.basic/.Colors   # a screensaver "the person" chose, to see it come back
# Budget for Home's process with the empty posters, on this emulator (~31 MB measured). Real posters on a TV take
# about 120 MB, so this isn't that number: it catches a change that makes Home itself heavier (a leak, a big cache).
MAX_HOME_MB=${MAX_HOME_MB:-80}
mkdir -p "$OUT"

e() { adb -s "$SERIAL" "$@"; }
run() { e shell "$@" | tr -d '\r'; }
key() { e shell input keyevent "$1"; sleep "${2:-1}"; }
shot() { e exec-out screencap -p > "$OUT/$1.png"; }
top() { run dumpsys activity activities | grep -m1 topResumedActivity || true; }
home() { run cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; }
disabled() { run pm list packages -d | sed 's/^package://' | sort; }
fail() { echo "FAIL: $*"; shot failure || true; e logcat -d > "$OUT/logcat.txt" || true; exit 1; }   # diagnostics even if adb is gone
step() { echo "== $*"; }

# Settings → Home → Marquise (UP) or Original (DOWN), with the remote's keys. The settings are drawn on a canvas,
# so the steps go by time: the screen is ready well within these waits on CI.
choose() {
  e shell am force-stop "$PKG"
  e shell am start -W -n "$PKG/.SettingsActivity" > /dev/null
  sleep 6
  key DPAD_CENTER 2   # the Home row is focused first: open its choices
  key "$1"
  key DPAD_CENTER 1
}

# Waits for the home screen to become $1, accepting the debugging prompt the first time it shows.
until_home() {
  local want=$1 i
  for i in $(seq 1 45); do
    if top | grep -q UsbDebuggingActivity; then
      step "accepting \"Allow USB debugging?\" with \"Always allow\""
      shot prompt
      key DPAD_CENTER   # tick "Always allow from this computer" (focused first)
      key DPAD_DOWN     # down lands on Cancel…
      key DPAD_LEFT     # …so left, to Allow
      key DPAD_CENTER 2
    fi
    [ "$(home)" = "$want" ] && return 0
    sleep 2
  done
  fail "the home screen is $(home), not $want"
}

# Waits for the screensaver to become $1: the switch sets it a few seconds after the home screen.
until_dream() {
  local i
  for i in $(seq 1 30); do
    [ "$(run settings get secure screensaver_components)" = "$1" ] && return 0
    sleep 2
  done
  fail "the screensaver is $(run settings get secure screensaver_components), not $1"
}

# Waits for the switch's closing notice in the log, as a regular expression: it is in the language of the TV's
# Marquise settings ("Marquise is on…" / "Marquise ligado…", "Original mode…" / "Modo original…").
until_notice() {
  local i
  for i in $(seq 1 30); do
    run logcat -d -s Marquise | grep -Eq "notice: $1" && return 0
    sleep 2
  done
  fail "no \"$1\" notice from the switch"
}

crashes() { run logcat -d -b crash | grep -c "$PKG" || true; }

step "before"
e shell settings put secure screensaver_components "$PERSONS_DREAM"
e logcat -c
disabled > "$OUT/disabled-before.txt"
[ "$(home)" = "$GOOGLE_HOME" ] || fail "the emulator doesn't start on Google TV's home ($(home))"
# This emulator only: Play Protect would stop an install from adb to ask about scanning it.
e shell settings put global verifier_verify_adb_installs 0
e shell settings put global package_verifier_enable 0
e install -r "$APK" > /dev/null

step "switching Marquise on"
choose DPAD_UP
until_home "$PKG/.HomeActivity"
until_dream "$PKG/$PKG.Screensaver"
until_notice "(Marquise is on|Marquise ligado)"
disabled > "$OUT/disabled-marquise.txt"
for pkg in com.google.android.apps.tv.launcherx com.google.android.apps.tv.dreamx; do
  grep -qx "$pkg" "$OUT/disabled-marquise.txt" || fail "$pkg wasn't turned off"
done
# Nothing protected turned off: every package off is one that was off before or one Marquise may turn off here.
comm -13 "$OUT/disabled-before.txt" "$OUT/disabled-marquise.txt" > "$OUT/turned-off.txt"
echo "turned off: $(paste -sd' ' "$OUT/turned-off.txt")"
for pkg in com.android.vending com.google.android.gms com.android.tv.settings com.android.systemui; do
  grep -qx "$pkg" "$OUT/turned-off.txt" && fail "$pkg is protected and was turned off"
done

step "Home on screen"
key HOME 10
[[ "$(top)" == *"$PKG/.HomeActivity"* ]] || fail "Home isn't on screen: $(top)"
shot home
key DPAD_RIGHT 2
shot home-focus
home_kb=$(run dumpsys meminfo "$PKG:home" | grep -m1 "TOTAL PSS" | awk '{print $3}' || true)
[[ "$home_kb" =~ ^[0-9]+$ ]] || fail "couldn't read Home's memory"
# Compared in KiB: rounding to MB first would let a process a little over the budget through.
echo "Home memory (PSS): $(( (home_kb + 1023) / 1024 )) MB (budget $MAX_HOME_MB MB)" | tee "$OUT/memory.txt"
[ "$home_kb" -le $(( MAX_HOME_MB * 1024 )) ] || fail "Home uses $(( (home_kb + 1023) / 1024 )) MB, over the $MAX_HOME_MB MB budget"

step "back to Original"
choose DPAD_DOWN
until_home "$GOOGLE_HOME"
until_dream "$PERSONS_DREAM"
until_notice "(Original mode|Modo original)"
disabled > "$OUT/disabled-after.txt"
diff -u "$OUT/disabled-before.txt" "$OUT/disabled-after.txt" || fail "the apps turned off differ from before"

[ "$(crashes)" = 0 ] || fail "Marquise crashed: see logcat.txt"
e logcat -d > "$OUT/logcat.txt"
echo "OK: switched on and back, everything restored"
