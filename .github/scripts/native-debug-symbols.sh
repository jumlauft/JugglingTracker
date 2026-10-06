#!/usr/bin/env bash
# Zips an app's release native libraries as the "native debug symbols" file
# Play Console asks for. The only native code is Compose's prebuilt
# libandroidx.graphics.path.so, which ships already stripped, so AGP's
# ndk.debugSymbolLevel has nothing to extract and Play keeps warning that no
# symbols were uploaded. Uploading the libraries themselves (same build IDs)
# gives Play their exported symbols and clears the warning.
#
# Usage: native-debug-symbols.sh <project dir, e.g. android or wearos>
# Run after bundleRelease. Writes <project>/app/build/native-debug-symbols.zip.
set -euo pipefail

app="$1/app/build"
lib_dir=$(find "$app/intermediates/merged_native_libs/release" -type d -name lib | head -1)
if [ -z "$lib_dir" ] || [ -z "$(find "$lib_dir" -name '*.so')" ]; then
  echo "No merged native libraries under $app/intermediates/merged_native_libs/release" >&2
  exit 1
fi

out="$(cd "$app" && pwd)/native-debug-symbols.zip"
rm -f "$out"
(cd "$lib_dir" && zip -qr "$out" .)
unzip -l "$out"
