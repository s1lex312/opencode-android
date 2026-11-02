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
# names (`libtalloc.so.2`) cannot live in jniLibs, so DT_NEEDED/DT_SONAME are rewritten:
#   1. `patchelf`, when it works;
#   2. otherwise a direct `.dynstr` byte patch (`libtalloc.so.2` -> `libtalloc.so\0\0`), which keeps
#      the string table layout intact — the standard Android hack for bionic binaries.
# Static files (the loader usually is one) never reach `patchelf`: it aborts with
# "cannot find section '.dynamic'" on them.
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

if ! command -v readelf >/dev/null 2>&1; then
  echo "::error title=readelf missing::install binutils; the proot runtime cannot be verified"
  exit 1
fi
if ! command -v patchelf >/dev/null 2>&1; then
  echo "::notice title=patchelf missing::falling back to direct .dynstr byte patching"
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
    "libstdc++.so", "libc++_shared.so", "libpthread.so", "librt.so", "libld-android.so",
}
# bionic names SONAMEs without versions, but glibc-built helpers (and our own test fixtures) may
# carry e.g. `libc.so.6`; compare stems when deciding whether a dependency is a system one.
SYSTEM_STEMS = {name.split(".so")[0] for name in SYSTEM}

patchelf = shutil.which("patchelf")

def log(message: str) -> None:
    print(message, flush=True)

def elf_dynamic(path: pathlib.Path) -> str:
    """`readelf -d` output, or "" when the file has no .dynamic (static binary)."""
    result = subprocess.run(["readelf", "-d", str(path)], capture_output=True, text=True)
    return result.stdout if result.returncode == 0 and "Dynamic section" in result.stdout else ""

def needed(path: pathlib.Path) -> list[str]:
    return re.findall(r"\(NEEDED\).*?\[(.+?)\]", elf_dynamic(path))

def soname(path: pathlib.Path) -> str | None:
    match = re.search(r"\(SONAME\).*?\[(.+?)\]", elf_dynamic(path))
    return match.group(1) if match else None

def replace_dynstr(path: pathlib.Path, old: str, new: str) -> None:
    """Patch a NUL-terminated string in `.dynstr` in place, keeping the table length identical."""
    if len(new) > len(old):
        raise ValueError(f"{path.name}: {old} -> {new} does not fit")
    raw = path.read_bytes()
    needle = old.encode() + b"\0"
    replacement = new.encode() + b"\0" * (len(needle) - len(new.encode()))
    if needle not in raw:
        raise RuntimeError(f"{path.name}: '{old}' is not present in the string table")
    path.write_bytes(raw.replace(needle, replacement))

PATCH_PROBLEMS: list[str] = []

def patch_needed(path: pathlib.Path, old: str, new: str) -> None:
    if old == new:
        return
    if patchelf:
        result = subprocess.run(
            [patchelf, "--replace-needed", old, new, str(path)],
            capture_output=True, text=True,
        )
        if result.returncode == 0 and old not in needed(path) and new in needed(path):
            log(f"    patchelf   {path.name}: DT_NEEDED {old} -> {new}")
            return
        detail = (result.stderr or result.stdout or "").strip().splitlines()
        log(f"    patchelf failed on {path.name} ({old}): {detail[0] if detail else 'exit ' + str(result.returncode)}")
    try:
        replace_dynstr(path, old, new)
        log(f"    byte patch {path.name}: DT_NEEDED {old} -> {new}")
    except Exception as error:  # noqa: BLE001 - reported below, never a traceback in CI logs
        PATCH_PROBLEMS.append(f"{path.name}: cannot rewrite DT_NEEDED {old} ({error})")

def patch_soname(path: pathlib.Path, new: str) -> None:
    current = soname(path)
    if current is None or current == new:
        return
    if patchelf:
        result = subprocess.run(
            [patchelf, "--set-soname", new, str(path)],
            capture_output=True, text=True,
        )
        if result.returncode == 0 and soname(path) == new:
            log(f"    patchelf   {path.name}: SONAME {current} -> {new}")
            return
        detail = (result.stderr or result.stdout or "").strip().splitlines()
        log(f"    patchelf failed on {path.name} (SONAME): {detail[0] if detail else 'exit ' + str(result.returncode)}")
    try:
        replace_dynstr(path, current, new)
        log(f"    byte patch {path.name}: SONAME {current} -> {new}")
    except Exception as error:  # noqa: BLE001
        # DT_SONAME only affects de-duplicated loads; the linker looks the file name up from
        # DT_NEEDED, so a stale SONAME is survivable — surface it, do not fail the build.
        log(f"    notice      {path.name}: keeping SONAME {current} ({error})")

def canonical(name: str) -> str:
    base = re.sub(r"\.so(\..*)?$", ".so", name)
    return base if base.startswith("lib") else "lib" + base

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
    if lib.name in SYSTEM:
        continue
    target = canonical(lib.name)
    while target in used or target in SYSTEM:
        target = target[:-3] + "_x.so"
    used.add(target)
    mapping[lib.name] = target

# ---------------------------------------------------------------- rewriting
log("rewriting bionic names for jniLibs:")
for lib in libs:
    if lib.name not in mapping:
        continue
    if not elf_dynamic(lib):
        log(f"    static      {lib.name}: skipped (no .dynamic section)")
        continue
    patch_soname(lib, mapping[lib.name])

binaries = [proot, *[l for l in libs if l.name in mapping]]
for binary in binaries:
    if not elf_dynamic(binary):
        log(f"    static      {binary.name}: skipped (no .dynamic section)")
        continue
    for old, new in mapping.items():
        if old in needed(binary):
            patch_needed(binary, old, new)

# ---------------------------------------------------------------- copy
shutil.copy2(proot, out / "libproot.so")
os.chmod(out / "libproot.so", 0o755)
log(f"libproot.so        <- {proot.name} ({proot.stat().st_size} bytes)")

if loader is not None:
    shutil.copy2(loader, out / "libproot_loader.so")
    os.chmod(out / "libproot_loader.so", 0o755)
    kind = "dynamic" if elf_dynamic(loader) else "static"
    log(f"libproot_loader.so <- {loader.name} ({loader.stat().st_size} bytes, {kind})")

for lib in libs:
    target = mapping.get(lib.name)
    if target is None:
        continue
    shutil.copy2(lib, out / target)
    os.chmod(out / target, 0o644)
    log(f"{target} <- {lib.name} ({lib.stat().st_size} bytes)")

# ---------------------------------------------------------------- verification
# Every DT_NEEDED that is not a system library must be shipped next to libproot.so, and no versioned
# bionic name may survive: the device linker resolves exact SONAMEs only.
shipped = {p.name for p in out.iterdir() if p.is_file()}
problems: list[str] = []
for name in sorted(shipped):
    path = out / name
    if not elf_dynamic(path):
        log(f"NEEDED {name}: (static)")
        continue
    deps = needed(path)
    log(f"NEEDED {name}: {', '.join(deps) if deps else '(none)'}")
    for dep in deps:
        if dep.split(".so")[0] in SYSTEM_STEMS:
            continue
        if re.search(r"\.so\.\d", dep):
            problems.append(f"{name} still needs the versioned name {dep} (patching failed)")
        elif dep not in shipped:
            problems.append(f"{name} needs {dep}, which is not packaged")

problems.extend(PATCH_PROBLEMS)
if problems:
    log("::error title=unresolved proot dependencies::" + "; ".join(problems))
    sys.exit(1)

log(f"dependency closure ok ({len(shipped)} native files)")
PY

# The packaged runtime must really be an ELF, otherwise the release would ship a broken APK.
head -c 4 "$OUT/libproot.so" | od -An -tx1 | tr -d ' \n' | grep -qx '7f454c46' \
  || { echo "::error title=proot is not an ELF::libproot.so has no ELF magic"; exit 1; }

# Final, independent check on the file that actually goes into the APK.
NEEDED_FINAL="$(readelf -d "$OUT/libproot.so" 2>/dev/null \
  | awk '/NEEDED/ {gsub(/[\[\]]/, "", $NF); print $NF}' | sort -u || true)"
echo "libproot.so NEEDED: $(printf '%s' "$NEEDED_FINAL" | tr '\n' ' ')"
VERSIONED=""
while IFS= read -r dep; do
  [ -n "$dep" ] || continue
  case "$dep" in
    # system libraries, whatever their naming scheme
    libc.so*|libdl.so*|libm.so*|liblog.so*|libz.so*|libandroid.so*|libstdc++.so*|libc++_shared.so*|libpthread.so*|librt.so*|libld-android.so*) continue ;;
  esac
  case "$dep" in
    *.so.[0-9]*) VERSIONED="$VERSIONED $dep" ;;
  esac
done <<< "$NEEDED_FINAL"
if [ -n "$VERSIONED" ]; then
  echo "::error title=unpatched proot::libproot.so still asks for versioned names:$VERSIONED"
  exit 1
fi

if command -v file >/dev/null 2>&1; then
  file "$OUT"/libproot*.so || true
fi
ls -la "$OUT"
