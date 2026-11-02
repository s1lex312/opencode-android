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

# Collect the entry list once: piping `unzip` into `grep -q` would kill unzip with SIGPIPE and,
# under `pipefail`, report a false failure.
ENTRIES="$(unzip -Z1 "$APK")"
case "$ENTRIES" in
  *classes.dex*) ;;
  *) echo "::error title=broken APK::no classes.dex inside $APK"; exit 1 ;;
esac

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
if BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)" && [ -x "$BT/aapt" ]; then
  "$BT/aapt" dump badging "$APK" | head -n 6
fi

# The bundle must be stored, not recompressed, and must be inside the Full APK.
if [[ "$APK" == *lite* ]]; then
  case "$ENTRIES" in
    *assets/local-env.tar.gz*) echo "::error title=Lite APK contains a bundle::$APK must stay small"; exit 1 ;;
  esac
fi

if [[ "$APK" == *full* ]]; then
  BUNDLE_ENTRY="$(printf '%s\n' "$ENTRIES" | grep -i 'local-env' | head -n 1 || true)"
  if [ -z "$BUNDLE_ENTRY" ]; then
    echo "entries: $(printf '%s\n' "$ENTRIES" | wc -l)"
    echo "sample: $(printf '%s\n' "$ENTRIES" | head -n 10 | tr '\n' ' ')"
    echo "assets: $(printf '%s\n' "$ENTRIES" | grep -c '^assets/' || true) entries"
    echo "::error title=Full APK without bundle::$APK does not contain a local-env entry"
    exit 1
  fi
  echo "bundle entry: $BUNDLE_ENTRY"
  echo "bundle entry size: $(unzip -l "$APK" "$BUNDLE_ENTRY" | awk 'NR==4 {print $1}')"
fi
