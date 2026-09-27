#!/usr/bin/env bash
# Render an SVG with headless Edge and diff it against the reference PNG.
set -e
SVG="${1:-icon_traced.svg}"
OUT="${2:-render.png}"
SIZE="${3:-2048}"
EDGE="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
"$EDGE" --headless=new --disable-gpu --hide-scrollbars \
  --screenshot="$(cygpath -w "$PWD/$OUT")" --window-size=$SIZE,$SIZE \
  --default-background-color=00000000 "file:///$(cygpath -m "$PWD/$SVG")" >/dev/null 2>&1
python compare.py "$OUT"
