#!/usr/bin/env bash
# Assemble the browser controller from the shared controller sources.
# The controller code is platform-adaptive (Tauri commands when run as the
# desktop app; browser APIs when served as a web page), so the same files serve
# both. Output: dist/web-controller/ — host it on any HTTPS origin.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
src="$here/apps/controller-app/src"
out="$here/dist/web-controller"
mkdir -p "$out"
cp "$src"/index.html "$src"/controller.js "$src"/signaling.js \
   "$src"/rtcConfig.js "$src"/fileTransfer.js "$src"/styles.css "$out"/
echo "web controller -> $out"
