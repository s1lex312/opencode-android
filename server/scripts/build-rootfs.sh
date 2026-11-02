#!/usr/bin/env bash
#
# Builds an arm64 Ubuntu rootfs (optionally with opencode installed) for the
# experimental proot-based server of opencode-android.
#
# Requires root on a Debian/Ubuntu host. Review before running: the script uses
# debootstrap and, when --install-opencode is passed, qemu-user-static + proot
# to execute arm64 apt/npm inside the rootfs.
#
# Usage:
#   sudo ./server/scripts/build-rootfs.sh --output ./out/rootfs-arm64.tar.gz [--install-opencode] [--suite noble]
#
set -euo pipefail

OUTPUT="./out/rootfs-arm64.tar.gz"
SUITE="jammy"
ARCH="arm64"
INSTALL_OPENCODE=0
WORKDIR="$(mktemp -d)"

usage() {
  sed -n '2,14p' "$0"
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) OUTPUT="$2"; shift 2 ;;
    --suite) SUITE="$2"; shift 2 ;;
    --arch) ARCH="$2"; shift 2 ;;
    --install-opencode) INSTALL_OPENCODE=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ "${EUID}" -ne 0 ]]; then
  echo "This script installs packages and uses chroot-like tooling; run it as root." >&2
  exit 1
fi

ROOTFS="${WORKDIR}/rootfs"
mkdir -p "${ROOTFS}" "$(dirname "${OUTPUT}")"

echo "==> Creating ${ARCH} ${SUITE} rootfs in ${ROOTFS}"
if command -v debootstrap >/dev/null 2>&1; then
  debootstrap --arch="${ARCH}" --foreign --variant=minbase "${SUITE}" "${ROOTFS}" \
    "http://ports.ubuntu.com/ubuntu-ports"
else
  echo "debootstrap not found — falling back to an Ubuntu base tarball." >&2
  echo "Install it first: apt-get install debootstrap" >&2
  exit 1
fi

echo "==> Registering binfmt (arm64 binaries on $(uname -m) host)"
if [[ "$(uname -m)" != "aarch64" && "$(uname -m)" != "arm64" ]]; then
  if [[ ! -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]]; then
    apt-get update -y
    apt-get install -y qemu-user-static binfmt-support
  fi
  cp /usr/bin/qemu-aarch64-static "${ROOTFS}/usr/bin/" || true
fi

echo "==> Second stage debootstrap"
chroot "${ROOTFS}" /debootstrap/debootstrap --second-stage

echo "==> Base packages"
cat > "${ROOTFS}/etc/apt/sources.list" <<EOF
deb http://ports.ubuntu.com/ubuntu-ports ${SUITE} main universe
deb http://ports.ubuntu.com/ubuntu-ports ${SUITE}-updates main universe
deb http://ports.ubuntu.com/ubuntu-ports ${SUITE}-security main universe
EOF

if [[ "${INSTALL_OPENCODE}" -eq 1 ]]; then
  echo "==> Installing prerequisites for opencode inside the rootfs"
  chroot "${ROOTFS}" /bin/bash -c "
    set -e
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -y
    apt-get install -y --no-install-recommends ca-certificates curl unzip git nodejs npm
    npm install -g opencode-ai@latest || npm install -g opencode
    /usr/local/bin/opencode --version || opencode --version
  "
fi

echo "==> Cleaning"
rm -f "${ROOTFS}/usr/bin/qemu-aarch64-static"
rm -rf "${ROOTFS}/var/lib/apt/lists"/* "${ROOTFS}/tmp"/*

echo "==> Packing ${OUTPUT}"
tar -C "${ROOTFS}" -czf "${OUTPUT}" .
rm -rf "${WORKDIR}"

cat <<EOF

Done: ${OUTPUT}

Next steps:
  1. Build or fetch a static arm64 proot (see server/README.md).
  2. Push both to the device:
       PROOT_BIN=./proot-arm64-static ROOTFS_TARBALL=${OUTPUT} APP_ID=ai.opencode.android.debug \\
         ./server/scripts/install-on-device.sh
  3. In the app: Settings -> Local server -> set rootfs/proot paths -> Start.
EOF
