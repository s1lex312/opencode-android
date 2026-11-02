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

# The Termux proot is dynamically linked against bionic: without its libraries the linker fails
# with `library "libtalloc.so.2" not found: needed by main executable`.
if [[ -z "$(ls -A "$WORK/proot-libs" 2>/dev/null)" ]]; then
  echo "::error title=proot deps missing::the bundle has no proot-libs/*.so, proot could never link"
  exit 1
fi

# Rewriting DT_NEEDED/DT_SONAME is mandatory, not best effort: without it the packaged proot
# still asks the linker for `libtalloc.so.2`, which cannot exist inside jniLibs.
if ! command -v patchelf >/dev/null 2>&1; then
  echo "::error title=patchelf missing::install patchelf; the proot runtime cannot be repackaged"
  exit 1
fi

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
if not patchelf:
    sys.exit("patchelf is required")

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

# ------------------------------------------------ dependency closure
# Every DT_NEEDED that is not a system library must be shipped next to libproot.so, otherwise the
# bionic linker fails on the device ("library X not found"). Fail the build here instead.
SYSTEM_NEEDED = {
    "libc.so", "libdl.so", "libm.so", "liblog.so", "libz.so", "libandroid.so",
    "libstdc++.so", "libc++_shared.so", "libpthread.so", "librt.so", "libld-android.so",
}

def needed(path: pathlib.Path) -> list[str]:
    out_ = subprocess.run([patchelf, "--print-needed", str(path)], capture_output=True, text=True)
    return [line.strip() for line in out_.stdout.splitlines() if line.strip()]

shipped = {p.name for p in out.iterdir() if p.is_file()}
problems: list[str] = []
# bionic/glibc names differ, so compare stems: `libc.so` matches `libc.so.6`, but `libtalloc.so.2`
# does not match anything system-side and must therefore be rewritten.
SYSTEM_STEMS = {name.split(".so")[0] for name in SYSTEM_NEEDED}

for binary in sorted(shipped):
    deps = needed(out / binary)
    print(f"NEEDED {binary}: {', '.join(deps) if deps else '(static)'}")
    for dep in deps:
        if dep.split(".so")[0] in SYSTEM_STEMS:
            continue
        if re.search(r"\.so\.\d", dep):
            problems.append(f"{binary} still needs the versioned name {dep} (patchelf failed)")
        elif dep not in shipped:
            problems.append(f"{binary} needs {dep}, which is not packaged")

if problems:
    print("::error title=unresolved proot dependencies::" + "; ".join(problems))
    sys.exit(1)
print(f"dependency closure ok ({len(shipped)} native files)")
PY

if command -v file >/dev/null 2>&1; then
  file "$OUT"/libproot*.so || true
fi
# Sanity: a valid aarch64 ELF must be there, otherwise the release would ship a broken runtime.
head -c 4 "$OUT/libproot.so" | od -An -tx1 | tr -d ' \n' | grep -qx '7f454c46' \
  || { echo "::error title=proot is not an ELF::libproot.so has no ELF magic"; exit 1; }
ls -la "$OUT"
