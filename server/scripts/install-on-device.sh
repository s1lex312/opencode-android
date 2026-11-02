#!/usr/bin/env bash
#
# Provisions the experimental proot-based opencode server on a connected Android
# device (arm64, debuggable build).
#
# What it does:
#   1. pushes the rootfs tarball to /data/local/tmp and extracts it into the app's
#      private files directory via `run-as` (no root needed);
#   2. installs a static proot binary as files/proot/proot (chmod 700);
#   3. prints the paths to paste into Settings -> Local server.
#
# Usage:
#   PROOT_BIN=./out/proot-arm64-static \
#   ROOTFS_TARBALL=./out/rootfs-arm64.tar.gz \
#   APP_ID=ai.opencode.android.debug \
#   ./server/scripts/install-on-device.sh
#
set -euo pipefail

PROOT_BIN="${PROOT_BIN:-}"
ROOTFS_TARBALL="${ROOTFS_TARBALL:-}"
APP_ID="${APP_ID:-ai.opencode.android.debug}"
PORT="${PORT:-4096}"

if [[ -z "${ROOTFS_TARBALL}" || -z "${PROOT_BIN}" ]]; then
  echo "Usage: PROOT_BIN=<proot-arm64-static> ROOTFS_TARBALL=<rootfs.tar.gz> APP_ID=<app id> $0" >&2
  exit 2
fi
if [[ ! -f "${ROOTFS_TARBALL}" ]]; then echo "rootfs tarball not found: ${ROOTFS_TARBALL}" >&2; exit 1; fi
if [[ ! -f "${PROOT_BIN}" ]]; then echo "proot binary not found: ${PROOT_BIN}" >&2; exit 1; fi

command -v adb >/dev/null 2>&1 || { echo "adb is required" >&2; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device/emulator connected" >&2; exit 1; }

echo "==> Checking app is installed and debuggable ($APP_ID)"
adb shell run-as "${APP_ID}" true >/dev/null 2>&1 || {
  echo "run-as failed. Install the debug APK and make sure it is a debuggable build." >&2
  exit 1
}

REMOTE_TMP="/data/local/tmp/opencode-rootfs.tar.gz"

echo "==> Pushing rootfs tarball"
adb push "${ROOTFS_TARBALL}" "${REMOTE_TMP}" >/dev/null

echo "==> Extracting rootfs into the app's files dir (this can take a while)"
adb shell "run-as ${APP_ID} mkdir -p files/rootfs"
adb shell "cat ${REMOTE_TMP} | run-as ${APP_ID} tar -xzf - -C files/rootfs"

echo "==> Installing proot"
adb push "${PROOT_BIN}" /data/local/tmp/proot-arm64 >/dev/null
adb shell "run-as ${APP_ID} mkdir -p files/proot"
adb shell "cat /data/local/tmp/proot-arm64 | run-as ${APP_ID} sh -c 'cat > files/proot/proot'"
adb shell "run-as ${APP_ID} chmod 700 files/proot/proot"

echo "==> Cleaning temporary files on device"
adb shell "rm -f ${REMOTE_TMP} /data/local/tmp/proot-arm64"

echo "==> Verification"
adb shell "run-as ${APP_ID} ls -la files/rootfs/bin/sh" || true
adb shell "run-as ${APP_ID} ls -la files/proot/proot"

cat <<EOF

Provisioning finished.

Paste into the app (Settings -> Local server):
  Rootfs path : /data/data/${APP_ID}/files/rootfs
  proot path  : /data/data/${APP_ID}/files/proot/proot
  Port        : ${PORT}
  Command     : opencode serve --hostname 127.0.0.1 --port {port}

Then press "Save local settings" and "Start". Server address for the app itself:
  http://127.0.0.1:${PORT}

Note: the app's applicationId for debug builds has the ".debug" suffix.
EOF
