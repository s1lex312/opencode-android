#!/usr/bin/env bash
#
# Verifies an environment bundle before it is used on a device:
#   * SHA-256 matches the expected value (if given);
#   * the archive contains proot, rootfs/bin/sh and rootfs/usr/local/bin/opencode;
#   * the proot binary looks like a static aarch64 executable (best effort).
#
# Usage: ./server/scripts/verify-artifact.sh <bundle.tar.gz> [expected-sha256]
set -euo pipefail

ARCHIVE="${1:-}"
EXPECTED="${2:-}"

[[ -f "$ARCHIVE" ]] || { echo "usage: $0 <bundle.tar.gz> [expected-sha256]" >&2; exit 2; }

ACTUAL="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
echo "file   : $ARCHIVE"
echo "sha256 : $ACTUAL"
if [[ -n "$EXPECTED" ]]; then
  if [[ "${ACTUAL,,}" != "${EXPECTED,,}" ]]; then
    echo "MISMATCH: expected ${EXPECTED,,}" >&2
    exit 1
  fi
  echo "checksum: OK"
fi

LISTING="$(tar -tzf "$ARCHIVE")"
for required in "./proot" "./rootfs/bin/sh" "./rootfs/usr/local/bin/opencode"; do
  if ! grep -qx -- "$required" <<< "$LISTING"; then
    echo "missing $required in the archive" >&2
    exit 1
  fi
done
echo "layout : OK (proot, rootfs/bin/sh, rootfs/usr/local/bin/opencode)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
tar -xzf "$ARCHIVE" -C "$TMP" ./proot
if command -v file >/dev/null 2>&1; then
  file "$TMP/proot" | sed 's/^/proot  : /'
fi
echo "done"
