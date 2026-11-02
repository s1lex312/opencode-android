#!/usr/bin/env bash
# Sanity-checks a built APK: non-trivial size, valid zip, dex present, readable manifest.
set -euo pipefail

APK="${1:?usage: verify-apk.sh <apk> [min-bytes]}"
MIN="${2:-${EXPECT_MIN_BYTES:-5000000}}"

[ -f "$APK" ] || { echo "::error title=missing APK::$APK was not produced"; exit 1; }

SIZE="$(stat -c%s "$APK")"
echo "APK: $APK ($SIZE bytes, minimum $MIN)"

if [ "$SIZE" -le "$MIN" ]; then
  echo "::error title=APK too small::$APK is $SIZE bytes, expected more than $MIN"
  exit 1
fi

unzip -t "$APK" > /dev/null || { echo "::error title=broken APK::$APK is not a valid zip archive"; exit 1; }
unzip -l "$APK" | grep -q 'classes.dex' || { echo "::error title=broken APK::no classes.dex inside $APK"; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
if BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)" && [ -x "$BT/aapt" ]; then
  "$BT/aapt" dump badging "$APK" | head -n 6
fi

# The bundle must be stored, not recompressed, and must be inside the Full APK.
if [[ "$APK" == *full* ]]; then
  unzip -l "$APK" | grep -q 'assets/local-env.tar.gz' \
    || { echo "::error title=Full APK without bundle::$APK does not contain assets/local-env.tar.gz"; exit 1; }
  echo "bundle: present in $APK"
fi
