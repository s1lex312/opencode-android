#!/usr/bin/env bash
# Download the arm64 Termux proot and every package it depends on, then lay them out the way
# build-rootfs.sh expects:
#
#   <out>/proot-aarch64   the proot executable (bionic, dynamically linked)
#   <out>/proot-libs/     its shared libraries (regular files *and* their SONAME symlinks, e.g.
#                         libtalloc.so.2 -> libtalloc.so.2.5.0) plus proot's `loader`/`loader32`
#
# Cross-building proot from source needs a static libtalloc for aarch64, which Ubuntu does not ship
# and talloc's waf does not build here. The Termux package is an arm64 Android (bionic) build with
# the same user-space semantics, so it is used as-is; extract-proot-jnilibs.sh later turns it into
# jniLibs. Dependencies come from the `Depends:` fields of the Termux index, recursively, so a new
# proot dependency upstream is picked up without editing this script.
#
# Usage: fetch-termux-proot.sh [out-dir]
set -euo pipefail

OUT="${1:-.}"
BASE="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"

mkdir -p "$OUT"
cd "$OUT"
rm -rf debs termux proot-libs proot-aarch64
mkdir -p debs termux proot-libs

curl -fsSL --retry 3 "$BASE/dists/stable/main/binary-aarch64/Packages" -o Packages

# `<package> <Filename>` for proot and the transitive closure of its Depends/Pre-Depends.
python3 - Packages proot > closure.txt <<'PY'
import re, sys

stanzas = {}
for block in open(sys.argv[1], encoding="utf-8").read().split("\n\n"):
    fields = dict(re.findall(r"^([A-Za-z-]+): (.*)$", block, re.M))
    if "Package" in fields:
        stanzas.setdefault(fields["Package"], fields)

queue, seen = [sys.argv[2]], []
while queue:
    name = queue.pop(0)
    if name in seen:
        continue
    fields = stanzas.get(name)
    if fields is None:
        print(f"::warning title=termux package missing::{name} is not in the index", file=sys.stderr)
        continue
    seen.append(name)
    for field in ("Pre-Depends", "Depends"):
        for alternative in filter(None, (d.strip() for d in fields.get(field, "").split(","))):
            # `a (>= 1) | b` -> `a`: the first alternative is what Termux installs by default.
            queue.append(re.split(r"[\s(|]", alternative.split("|")[0].strip())[0])
    print(name, fields["Filename"])
PY

grep -q '^proot ' closure.txt || { echo "::error title=termux package missing::proot is not in the Termux index"; exit 1; }
echo "termux packages: $(cut -d' ' -f1 closure.txt | tr '\n' ' ')"

while read -r pkg file; do
  curl -fsSL --retry 3 "$BASE/$file" -o "debs/$pkg.deb"
  mkdir -p "termux/$pkg"
  ( cd "termux/$pkg" && ar x "../../debs/$pkg.deb" && tar xf data.tar.* )
done < <(tr -d '\r' < closure.txt)

PROOT_BIN="$(find termux -type f -path '*bin/proot' | head -n1)"
[ -n "$PROOT_BIN" ] || { echo "::error title=termux package missing::the proot package has no bin/proot"; exit 1; }
file "$PROOT_BIN"
file "$PROOT_BIN" | grep -qi 'aarch64' || { echo "::error title=wrong ABI::packaged proot is not arm64"; exit 1; }
cp "$PROOT_BIN" proot-aarch64
chmod 755 proot-aarch64

# Libraries with their symlinks: the SONAME link (libtalloc.so.2) is what an unpatched proot asks
# for when it runs from app storage on Android 8/9, and what extract-proot-jnilibs.sh resolves.
find termux \( -type f -o -type l \) -path '*usr/lib/*' -name '*.so*' -exec cp -a {} proot-libs/ \;
# proot execs its loader, so it has to be executable too: ship it with the libs and let
# extract-proot-jnilibs.sh package it as libproot_loader.so / libproot_loader32.so.
find termux -type f \( -name 'loader*' -o -path '*libexec/proot/*' \) -exec cp -a {} proot-libs/ \;

[ -n "$(ls -A proot-libs 2>/dev/null)" ] \
  || { echo "::error title=no proot libs::the termux packages produced no usr/lib/*.so*"; exit 1; }
ls -la proot-libs
