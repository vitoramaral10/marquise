#!/usr/bin/env bash
# Cuts Inter down to what Marquise draws: Latin with accents, punctuation, arrows, ★ and currency signs, keeping the
# variable axes (optical size and weight) and tabular figures. Titles in other scripts fall back to the system font.
# Usage: tools/subset_font.sh path/to/InterVariable.ttf   (the full font from https://github.com/rsms/inter/releases)
set -euo pipefail
cd "$(dirname "$0")/.."
uvx --from fonttools pyftsubset "$1" --output-file=app/assets/fonts/InterVariable.ttf \
  --unicodes="U+0020-007E,U+00A0-024F,U+0300-036F,U+1E00-1EFF,U+2000-206F,U+20A0-20BF,U+2100-214F,U+2190-2199,U+2212,U+2605,U+2713" \
  --layout-features+=tnum
ls -la app/assets/fonts/InterVariable.ttf
