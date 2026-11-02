#!/usr/bin/env bash
# Move the proot runtime out of the app's private data directory and into `jniLibs`.
#
# Why: since Android 10 (targetSdk >= 29) SELinux forbids `execve()` on files in the app's data
# dir, so running `opencode-env/proot/proot` fails with "error=13, Permission denied". Files that
# ship as `lib*.so` inside the APK are extracted by the package installer into
# `applicationInfo.nativeLibraryDir`, a mount that *is* executable — the standard workaround.
#
# The script copies `proot` as `libproot.so`, a separate loader (if the bundle ships one) as
# `libproot_loader.so`, and the shared libraries proot needs as `lib<name>.so`. Versioned bionic
# names (`libtalloc.so.2`) cannot live in jniLibs, so their DT_SONAME/DT_NEEDED entries are
# rewritten with `patchelf`; without patchelf the libraries are still copied (best effort).
#
# Usage: extract-proot-jnilibs.sh <bundle.tar.gz> <out jniLibs dir> [--allow-missing]
#
# Only the Full flavour ships a bundle. `--allow-missing` turns a missing bundle into a notice
# instead of an error, so the script can be wired into a Lite-only build without breaking it.
set -euo pipefail

BUNDLE="${1:?usage: extract-proot-jnilibs.sh <bundle.tar.gz> <out-dir> [--allow-missing]}"
OUT="${2:?usage: extract-proot-jnilibs.sh <bundle.tar.gz> <out-dir> [--allow-missing]}"
ALLOW_MISSING="${3:-}"

if [[ ! -f "$BUNDLE" ]]; then
  if [[ "$ALLOW_MISSING" == "--allow-missing" ]]; then
    echo "::notice title=no bundle::$(basename "$BUNDLE") is absent (Lite flavour), skipping the proot runtime"
    exit 0
  fi
  echo "::error title=proot missing::no bundle at $BUNDLE"
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Only the runtime pieces; the rootfs itself stays in the APK assets.
tar -xzf "$BUNDLE" -C "$WORK" proot proot-libs 2>/dev/null || tar -xzf "$BUNDLE" -C "$WORK"

PROOT_BIN="$WORK/proot"
[[ -f "$PROOT_BIN" ]] || { echo "::error title=proot missing::bundle has no proot binary"; exit 1; }

LOADER=""
for candidate in "$WORK"/proot-libs/loader* "$WORK"/loader* "$WORK"/proot-libs/*loader*; do
  if [[ -f "$candidate" ]]; then LOADER="$candidate"; break; fi
done

mkdir -p "$OUT"

python3 - "$PROOT_BIN" "$WORK/proot-libs" "$OUT" "$LOADER" <<'PY'
import os, pathlib, re, shutil, subprocess, sys

proot = pathlib.Path(sys.argv[1])
libdir = pathlib.Path(sys.argv[2]) if sys.argv[2] else None
out = pathlib.Path(sys.argv[3])
loader = pathlib.Path(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[4] else None

# Libraries that always come from the system: never ship them, never rewrite their names.
SYSTEM = {
    "libc.so", "libdl.so", "libm.so", "liblog.so", "libz.so", "libandroid.so",
    "libstdc++.so", "libc++_shared.so", "libpthread.so", "librt.so", "libm.so.6", "libc.so.6",
}

def canonical(name: str) -> str:
    base = re.sub(r"\.so(\..*)?$", ".so", name)
    return base if base.startswith("lib") else "lib" + base

patchelf = shutil.which("patchelf")
def patch(*args: str) -> None:
    subprocess.run([patchelf, *args], check=True, stdout=subprocess.DEVNULL)

loader_name = loader.name if loader is not None else None
libs = []
if libdir is not None and libdir.is_dir():
    # The loader is packaged on its own (as libproot_loader.so): never treat it as a shared library.
    libs = sorted(
        p for p in libdir.iterdir()
        if p.is_file() and not p.is_symlink() and p.name != loader_name
    )

mapping: dict[str, str] = {}
used: set[str] = set()
for lib in libs:
    target = lib.name if lib.name in SYSTEM else canonical(lib.name)
    if lib.name in SYSTEM:
        continue
    while target in used or target in SYSTEM:
        target = target[:-3] + "_x.so"
    used.add(target)
    mapping[lib.name] = target

if not patchelf:
    print("::warning title=patchelf missing::versioned bionic libraries keep their names")

if patchelf:
    for lib in libs:
        if lib.name in mapping:
            patch("--set-soname", mapping[lib.name], str(lib))
    for binary in [proot, *[l for l in libs if l.name in mapping]]:
        for old, new in mapping.items():
            if old == new:
                continue
            try:
                patch("--replace-needed", old, new, str(binary))
            except subprocess.CalledProcessError:
                pass  # the binary simply did not need that library

shutil.copy2(proot, out / "libproot.so")
os.chmod(out / "libproot.so", 0o755)
print(f"libproot.so       <- {proot.name} ({proot.stat().st_size} bytes)")

if loader is not None:
    shutil.copy2(loader, out / "libproot_loader.so")
    os.chmod(out / "libproot_loader.so", 0o755)
    print(f"libproot_loader.so <- {loader.name} ({loader.stat().st_size} bytes)")

for lib in libs:
    target = mapping.get(lib.name)
    if target is None:
        continue
    shutil.copy2(lib, out / target)
    os.chmod(out / target, 0o644)
    print(f"{target} <- {lib.name} ({lib.stat().st_size} bytes)")
PY

if command -v file >/dev/null 2>&1; then
  file "$OUT"/libproot*.so || true
fi
# Sanity: a valid aarch64 ELF must be there, otherwise the release would ship a broken runtime.
head -c 4 "$OUT/libproot.so" | od -An -tx1 | tr -d ' \n' | grep -qx '7f454c46' \
  || { echo "::error title=proot is not an ELF::libproot.so has no ELF magic"; exit 1; }
ls -la "$OUT"
