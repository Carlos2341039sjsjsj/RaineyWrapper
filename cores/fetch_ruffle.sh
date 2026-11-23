#!/bin/sh
# Baja Ruffle (reproductor de Flash en WebAssembly, MIT / Apache-2.0), version autoalojada, desde npm.
# Queda en player-template/app/src/main/assets/ruffle; solo los APK de juegos .swf lo conservan.
set -eu
DEST="$(cd "$(dirname "$0")/.." && pwd)/player-template/app/src/main/assets/ruffle"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
cd "$TMP"
npm pack @ruffle-rs/ruffle --silent
tar xzf ./*.tgz
rm -rf "$DEST"
mkdir -p "$DEST"
cp -r package/. "$DEST/"
test -f "$DEST/ruffle.js"
ls -la "$DEST"
