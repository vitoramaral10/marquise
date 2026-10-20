#!/usr/bin/env bash
# Renders the icons (white PNGs with transparency) from the official Material Symbols Rounded SVGs, pinned to one
# version so the result never changes. Needs curl, rsvg-convert and ImageMagick 7.
# Usage: tools/make_symbols.sh [OUT_DIR]   (default: app/assets/symbols)
set -euo pipefail
OUT=${1:-app/assets/symbols}
BASE=https://cdn.jsdelivr.net/npm/@material-symbols/svg-400@0.47.6/rounded
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT

# name used in the app -> Material Symbols file (-fill = filled)
declare -A MAP=(
  [sunny]=sunny-fill [clear_night]=bedtime-fill [partly_cloudy_day]=partly_cloudy_day-fill
  [partly_cloudy_night]=partly_cloudy_night-fill [cloud]=cloud-fill [foggy]=foggy-fill [rainy]=rainy-fill
  [thunderstorm]=thunderstorm-fill [snowflake]=ac_unit
  [tv]=tv [live_tv]=live_tv-fill [hdmi]=settings_input_hdmi-fill [tv_settings]=display_settings-fill
  [apps]=apps-fill [settings]=settings-fill [bell]=notifications-fill [check]=check [chevron_right]=chevron_right
  [twilight]=wb_twilight-fill [up]=north [down]=south
)

render() {   # svg -> white, trimmed PNG (the glyph's 24-unit box drawn at 172 px, the em Symbols.java expects)
  rsvg-convert -w 172 -h 172 "$1" -o "$WORK/raw.png"
  magick "$WORK/raw.png" -fill white -colorize 100 -trim +repage "$2"
}

mkdir -p "$OUT"
for name in "${!MAP[@]}"; do
  curl -sfL -o "$WORK/$name.svg" "$BASE/${MAP[$name]}.svg"
  case $name in twilight|up|down) render "$WORK/$name.svg" "$WORK/$name.png" ;; *) render "$WORK/$name.svg" "$OUT/$name.png" ;; esac
done

# Sunrise and sunset: the sun on the horizon with an arrow above it, up or down.
H=$(magick identify -format %h "$WORK/twilight.png"); W=$(magick identify -format %w "$WORK/twilight.png")
for pair in sunrise:up sunset:down; do
  magick "$WORK/${pair#*:}.png" -resize "x$((H * 55 / 100))" "$WORK/arrow.png"
  magick -size "${W}x$((H * 150 / 100))" xc:none "$WORK/twilight.png" -gravity south -composite \
    "$WORK/arrow.png" -gravity north -composite "$OUT/${pair%%:*}.png"
done
echo "$(ls "$OUT"/*.png | wc -l) icons in $OUT"
