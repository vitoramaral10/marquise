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
)

render() {   # svg -> white, trimmed PNG (the glyph's 24-unit box drawn at 172 px, the em Symbols.java expects)
  rsvg-convert -w 172 -h 172 "$1" -o "$WORK/raw.png"
  magick "$WORK/raw.png" -fill white -colorize 100 -trim +repage -strip -define png:exclude-chunks=date,time "$2"
}

mkdir -p "$OUT"
for name in "${!MAP[@]}"; do
  curl -sfL -o "$WORK/$name.svg" "$BASE/${MAP[$name]}.svg"
  render "$WORK/$name.svg" "$OUT/$name.png"
done

# Sunrise and sunset have no Material symbol: tools/symbols/ holds "wb_twilight" with an arrow for its middle ray.
for name in sunrise sunset; do render "$(dirname "$0")/symbols/$name.svg" "$OUT/$name.png"; done

echo "$(ls "$OUT"/*.png | wc -l) icons in $OUT"
