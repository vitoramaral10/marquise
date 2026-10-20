#!/usr/bin/env bash
# tools/frame_tv.sh SCREEN.png OUT.webp: a screenshot inside a modern TV for the install page (hair-thin bezel, aluminium rim, glass sheen, soft shadow).
set -euo pipefail
in=$1; out=$2; W=1600; H=900
B=9; R=20; TW=$((W+2*B)); TH=$((H+2*B)); PAD=90; CW=$((TW+2*PAD)); CH=$((TH+2*PAD))
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
mask(){ magick -size $1x$2 xc:black -fill white -draw "roundrectangle 0,0 $(($1-1)),$(($2-1)) $3,$3" -colorspace gray "$4"; }
# Screen with its own small rounding and a faint diagonal glass sheen.
magick "$in" -resize ${W}x${H}^ -gravity center -extent ${W}x${H} -colorspace sRGB PNG24:"$t/s.png"
magick -size ${W}x${H} gradient:'rgba(255,255,255,0.07)'-'rgba(255,255,255,0)' -rotate 0 PNG32:"$t/sheen.png"
magick "$t/s.png" "$t/sheen.png" -compose screen -composite PNG24:"$t/s.png"
mask $W $H $((R-B)) "$t/sm.png"
magick "$t/s.png" "$t/sm.png" -alpha off -compose CopyOpacity -composite PNG32:"$t/s.png"
# Body: near-black glass bezel.
mask $TW $TH $R "$t/bm.png"
magick -size ${TW}x${TH} xc:'#08080a' "$t/bm.png" -alpha off -compose CopyOpacity -composite PNG32:"$t/b.png"
# Aluminium rim: a 2px ring, lighter at the top.
magick -size ${TW}x${TH} gradient:'#8a8a92'-'#2c2c31' -colorspace sRGB PNG24:"$t/al.png"
magick -size ${TW}x${TH} xc:black -fill none -stroke white -strokewidth 2.5 \
  -draw "roundrectangle 1,1 $((TW-2)),$((TH-2)) $R,$R" -colorspace gray "$t/rm.png"
magick "$t/al.png" "$t/rm.png" -alpha off -compose CopyOpacity -composite PNG32:"$t/rim.png"
magick "$t/b.png" "$t/s.png" -geometry +$B+$B -compose over -composite "$t/rim.png" -compose over -composite PNG32:"$t/tv.png"
# Soft shadow, a little lower than the set, as on a lit wall.
magick -size ${CW}x${CH} xc:none -fill 'rgba(0,0,0,0.6)' \
  -draw "roundrectangle $((PAD+30)),$((PAD+40)) $((PAD+TW-30)),$((PAD+TH+26)) $R,$R" -blur 0x34 PNG32:"$t/sh.png"
magick "$t/sh.png" "$t/tv.png" -geometry +$PAD+$PAD -compose over -composite -strip -quality 88 "$out"
