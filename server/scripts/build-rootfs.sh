#!/usr/bin/env bash
#
# Builds the "opencode environment" bundle consumed by the Android app:
#
#   opencode-env-<version>-arm64.tar.gz   proot + rootfs/ + rootfs/usr/local/bin/opencode
#   manifest.json                         version, ABI, SHA-256, size
#
# Two modes:
#   --mode full   real arm64 bundle. Needs a static arm64 proot, a Debian/Ubuntu arm64 rootfs
#                 (directory or .tar.gz) and the standalone opencode binary.
#   --mode stub   tiny fake bundle used by CI to exercise the packaging/manifest pipeline.
#                 It is NOT runnable on a device.
#
# Examples:
#   # real bundle from artifacts you already have
#   ./server/scripts/build-rootfs.sh --mode full --output dist --version 2026.11 \
#       --proot ./proot-aarch64 --rootfs ./ubuntu-base.tar.gz --opencode ./opencode
#
#   # download the upstream pieces first (needs network, runs on x86_64 hosts)
#   ./server/scripts/build-rootfs.sh --mode full --output dist --version 2026.11 \
#       --download-base --download-opencode --proot ./proot-aarch64
#
#   # CI smoke test of the packaging pipeline
#   ./server/scripts/build-rootfs.sh --mode stub --output dist
#
# Result: dist/opencode-env-<version>-arm64.tar.gz + dist/manifest.json (+ .sha256 file).
# Host the tarball anywhere (GitHub release) and paste its URL + SHA-256 on the Setup screen,
# or copy it to app/src/main/assets/local-env.tar.gz before building the APK.
set -euo pipefail

MODE="full"
OUTPUT="dist"
VERSION="$(date -u +%Y%m%d)"
ABI="arm64-v8a"
PROOT=""
ROOTFS=""
OPENCODE=""
BASE_URL=""
DOWNLOAD_BASE=0
DOWNLOAD_OPENCODE=0
UBUNTU_SERIES="${UBUNTU_SERIES:-22.04}"
UBUNTU_BASE_VERSION="${UBUNTU_BASE_VERSION:-22.04.5}"
OPENCODE_REPO="${OPENCODE_REPO:-anomalyco/opencode}"

usage() {
  sed -n '2,32p' "$0"
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode) MODE="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --abi) ABI="$2"; shift 2 ;;
    --proot) PROOT="$2"; shift 2 ;;
    --rootfs) ROOTFS="$2"; shift 2 ;;
    --opencode) OPENCODE="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --download-base) DOWNLOAD_BASE=1; shift ;;
    --download-opencode) DOWNLOAD_OPENCODE=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BUNDLE="$WORK/bundle"
ROOTFS_DIR="$BUNDLE/rootfs"
mkdir -p "$BUNDLE" "$ROOTFS_DIR" "$OUTPUT"

echo "==> mode=$MODE abi=$ABI version=$VERSION"

if [[ "$MODE" == "stub" ]]; then
  # Minimal, intentionally non-functional layout: the Android extractor and the provisioner tests
  # only require proot + rootfs/bin/sh to exist.
  printf '#!/bin/sh\necho "stub proot: not runnable on a device"\n' > "$BUNDLE/proot"
  chmod 755 "$BUNDLE/proot"
  mkdir -p "$ROOTFS_DIR/bin" "$ROOTFS_DIR/usr/local/bin"
  printf '#!/bin/sh\necho stub rootfs\n' > "$ROOTFS_DIR/bin/sh"
  printf '#!/bin/sh\necho stub opencode\n' > "$ROOTFS_DIR/usr/local/bin/opencode"
  chmod 755 "$ROOTFS_DIR/bin/sh" "$ROOTFS_DIR/usr/local/bin/opencode"
  VERSION="${VERSION}-stub"
else
  if [[ "$DOWNLOAD_BASE" -eq 1 && -z "$ROOTFS" ]]; then
    base_url="https://cdimage.ubuntu.com/ubuntu-base/releases/${UBUNTU_SERIES}/release/ubuntu-base-${UBUNTU_BASE_VERSION}-base-arm64.tar.gz"
    echo "==> downloading $base_url"
    curl -fL --retry 3 -o "$WORK/ubuntu-base.tar.gz" "$base_url"
    ROOTFS="$WORK/ubuntu-base.tar.gz"
  fi
  if [[ "$DOWNLOAD_OPENCODE" -eq 1 && -z "$OPENCODE" ]]; then
    oc_url="https://github.com/${OPENCODE_REPO}/releases/latest/download/opencode-linux-arm64.tar.gz"
    echo "==> downloading $oc_url"
    curl -fL --retry 3 -o "$WORK/opencode.tar.gz" "$oc_url"
    tar -xzf "$WORK/opencode.tar.gz" -C "$WORK"
    OPENCODE="$(find "$WORK" -maxdepth 2 -type f -name 'opencode*' ! -name '*.tar.gz' | head -n 1)"
  fi

  [[ -n "$PROOT" && -f "$PROOT" ]] || { echo "--proot is required in full mode (static arm64 binary)" >&2; exit 2; }
  [[ -n "$ROOTFS" ]] || { echo "--rootfs is required in full mode" >&2; exit 2; }
  [[ -n "$OPENCODE" && -f "$OPENCODE" ]] || { echo "--opencode is required in full mode" >&2; exit 2; }

  echo "==> staging rootfs"
  if [[ -d "$ROOTFS" ]]; then
    cp -a "$ROOTFS/." "$ROOTFS_DIR/"
  else
    tar -xf "$ROOTFS" -C "$ROOTFS_DIR"
  fi

  echo "==> installing proot and opencode"
  install -m 755 "$PROOT" "$BUNDLE/proot"
  mkdir -p "$ROOTFS_DIR/usr/local/bin"
  install -m 755 "$OPENCODE" "$ROOTFS_DIR/usr/local/bin/opencode"

  # proot has no Android DNS resolver inside the rootfs; the app bind-mounts its own file, but ship
  # a sane default so manual `proot` runs work too.
  mkdir -p "$ROOTFS_DIR/etc"
  [[ -f "$ROOTFS_DIR/etc/resolv.conf" ]] || printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS_DIR/etc/resolv.conf"

  # Keep the bundle small: docs/man pages are useless inside a mobile sandbox.
  rm -rf "$ROOTFS_DIR/usr/share/doc" "$ROOTFS_DIR/usr/share/man" "$ROOTFS_DIR/usr/share/info" 2>/dev/null || true
fi

ARCHIVE="$OUTPUT/opencode-env-${VERSION}-arm64.tar.gz"
echo "==> packing $ARCHIVE"
tar --numeric-owner --owner=0 --group=0 -czf "$ARCHIVE" -C "$BUNDLE" .

SHA="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
SIZE="$(stat -c %s "$ARCHIVE")"
HUMAN="$(du -h "$ARCHIVE" | awk '{print $1}')"

MANIFEST="$OUTPUT/manifest.json"
cat > "$MANIFEST" <<JSON
{
  "version": "${VERSION}",
  "abi": "${ABI}",
  "url": "${BASE_URL:+${BASE_URL}/$(basename "$ARCHIVE")}",
  "asset": "local-env.tar.gz",
  "sha256": "${SHA}",
  "sizeBytes": ${SIZE},
  "notes": "opencode environment for Android (proot + rootfs + opencode). Build mode: ${MODE}."
}
JSON

echo "$SHA  $(basename "$ARCHIVE")" > "$ARCHIVE.sha256"

cat <<SUMMARY

==> done
  archive : $ARCHIVE ($HUMAN)
  sha256  : $SHA
  manifest: $MANIFEST

Next steps:
  * Ship inside the APK:  cp "$ARCHIVE" app/src/main/assets/local-env.tar.gz
    (then rebuild the app; the manifest in assets/local-env.json must carry this sha256)
  * Or publish it and paste $SHA into the app's Setup screen together with the https:// URL.
  * Verify any copy with:  server/scripts/verify-artifact.sh "$ARCHIVE" $SHA
SUMMARY
