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

# ---- Variant specific checks ------------------------------------------------------------------
VERSION_LINE=""
if BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -n 1)" && [ -x "$BT/aapt" ]; then
  VERSION_LINE="$("$BT/aapt" dump badging "$APK" | head -n 1)"
fi

has_entry() {
  printf '%s\n' "$ENTRIES" | grep -Fxq "$1"
}

entry_size() {
  unzip -l "$APK" "$1" | awk 'NR==4 {print $1}'
}

echo "::notice title=APK contents::$APK ($SIZE bytes) entries=$(printf '%s\n' "$ENTRIES" | wc -l) bundle=$(entry_size "$(printf '%s\n' "$ENTRIES" | grep -E '^assets/local-env\.(tar|tar\.gz)$' | head -n1 || echo assets/local-env.tar.gz)" | tr -d ' ') ${VERSION_LINE}"

if [[ "$APK" == *lite* ]]; then
  if printf '%s\n' "$ENTRIES" | grep -Eq '^assets/local-env\.(tar|tar\.gz)$'; then
    echo "::error title=Lite APK contains a bundle::$APK must stay small"
    exit 1
  fi
  case "$VERSION_LINE" in
    *-lite-debug*|*-lite*) ;;
    "") echo "::warning title=no aapt::cannot verify the variant of $APK" ;;
    *) echo "::error title=wrong variant::$APK does not report a -lite version"; exit 1 ;;
  esac
  echo "lite: no embedded bundle, variant ok"
fi

if [[ "$APK" == *full* ]]; then
  # Exact entry name: `grep local-env` would also match the 294 byte local-env.json manifest and
  # silently pass a Full APK that lost its 105 MB archive (that is exactly what happened in 1.1.2).
  # aapt2 may store `local-env.tar.gz` as `local-env.tar`; accept both, keep everything else strict.
  BUNDLE_ENTRY="$(printf '%s\n' "$ENTRIES" | grep -E '^assets/local-env\.(tar|tar\.gz)$' | head -n 1 || true)"
  if [ -z "$BUNDLE_ENTRY" ]; then
    echo "assets: $(printf '%s\n' "$ENTRIES" | grep -c '^assets/' || true) entries"
    printf 'asset entries: %s\n' "$(printf '%s\n' "$ENTRIES" | grep '^assets/' | tr '\n' ' ')"
    echo "::error title=Full APK without bundle::$APK has no assets/local-env.tar[.gz] entry"
    exit 1
  fi
  BUNDLE_BYTES="$(entry_size "$BUNDLE_ENTRY")"
  echo "bundle entry: $BUNDLE_ENTRY ($BUNDLE_BYTES bytes)"
  if [ "${BUNDLE_BYTES:-0}" -lt 100000000 ]; then
    echo "::error title=Full APK bundle too small::$BUNDLE_ENTRY is only ${BUNDLE_BYTES:-0} bytes, expected ~105 MB"
    exit 1
  fi
  case "$VERSION_LINE" in
    *-full-debug*|*-full*) ;;
    "") echo "::warning title=no aapt::cannot verify the variant of $APK" ;;
    *) echo "::error title=wrong variant::$APK does not report a -full version"; exit 1 ;;
  esac
  # Android 10+ only lets an app exec binaries that live in nativeLibraryDir, so proot must be
  # packaged as a native library. Without it Local mode cannot start at all.
  if ! printf '%s\n' "$ENTRIES" | grep -Fxq 'lib/arm64-v8a/libproot.so'; then
    printf 'lib entries: %s\n' "$(printf '%s\n' "$ENTRIES" | grep '^lib/' | tr '\n' ' ')"
    echo "::error title=Full APK without runtime::$APK has no lib/arm64-v8a/libproot.so"
    exit 1
  fi
  echo "proot runtime: lib/arm64-v8a/libproot.so"
  echo "full: bundle present ($BUNDLE_BYTES bytes), variant ok"
fi
