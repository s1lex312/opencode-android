#!/usr/bin/env bash
# Move the proot runtime out of the app's private data directory and into `jniLibs`.
#
# Why: since Android 10 (targetSdk >= 29) SELinux forbids `execve()` on files in the app's data
# dir, so running `opencode-env/proot/proot` fails with "error=13, Permission denied". Files that
# ship as `lib*.so` inside the APK are extracted by the package installer into
# `applicationInfo.nativeLibraryDir`, a mount that *is* executable — the standard workaround.
#
# Output (only `lib*.so` names: AGP packages nothing else from jniLibs and the installer extracts
# nothing else from the APK):
#   libproot.so           the proot executable
#   libproot_loader.so    its 64-bit loader (PROOT_LOADER), when the bundle ships one
#   libproot_loader32.so  its 32-bit loader (PROOT_LOADER_32), when the bundle ships one
#   lib<name>.so          every shared library proot transitively needs, nothing more
#
# Dependency resolution follows the dynamic linker, not file names: a DT_NEEDED entry is matched
# against the SONAME of the files in `proot-libs/`. Termux ships talloc as the regular file
# `libtalloc.so.2.5.0` (SONAME `libtalloc.so.2`) plus two symlinks that `find -type f` drops, so a
# file-name lookup never connects proot's `libtalloc.so.2` to it — that is what broke 1.1.12.
#
# Versioned names cannot live in jniLibs, so `libtalloc.so.2` becomes `libtalloc.so` in proot's
# DT_NEEDED, in talloc's own DT_SONAME and in any DT_VERNEED file reference. The rewrite is done in
# place inside `.dynstr`, located through PT_DYNAMIC/DT_STRTAB: the new name is never longer than
# the old one, so it is written over the old string and padded with NULs. No section, segment or
# offset moves, which is exactly why this works on Termux binaries where patchelf does not.
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

if ! command -v readelf >/dev/null 2>&1; then
  echo "::error title=readelf missing::install binutils; the proot runtime cannot be verified"
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Only the runtime pieces; the rootfs itself stays in the APK assets. build-rootfs.sh packs with
# `-C bundle .`, so members are `./proot`; fall back to bare names, then to the whole archive.
tar -xzf "$BUNDLE" -C "$WORK" ./proot ./proot-libs 2>/dev/null \
  || tar -xzf "$BUNDLE" -C "$WORK" proot proot-libs 2>/dev/null \
  || tar -xzf "$BUNDLE" -C "$WORK"

[[ -f "$WORK/proot" ]] || { echo "::error title=proot missing::bundle has no proot binary"; exit 1; }

# The Termux proot is dynamically linked against bionic: without its libraries the linker fails
# with `library "libtalloc.so.2" not found: needed by main executable`.
if [[ -z "$(ls -A "$WORK/proot-libs" 2>/dev/null)" ]]; then
  echo "::error title=proot deps missing::the bundle has no proot-libs/*.so, proot could never link"
  exit 1
fi

mkdir -p "$OUT"
# Leftovers of an earlier run (e.g. a library proot no longer needs) must not reach the APK.
find "$OUT" -maxdepth 1 \( -type f -o -type l \) -name 'lib*' -delete

python3 - "$WORK" "$OUT" <<'PY'
import os, pathlib, re, shutil, struct, subprocess, sys

work = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2])
proot = work / "proot"
libdir = work / "proot-libs"

# Libraries the device provides: never ship them, never rewrite their names. Compared by stem so a
# glibc-style `libc.so.6` is recognised too. libc++_shared.so is NOT here: it is an NDK library
# that has to travel with whoever needs it.
SYSTEM = {
    "libc.so", "libdl.so", "libm.so", "liblog.so", "libz.so", "libandroid.so",
    "libstdc++.so", "libpthread.so", "librt.so", "libld-android.so",
}
SYSTEM_STEMS = {name.split(".so")[0] for name in SYSTEM}
RESERVED = {"libproot.so", "libproot_loader.so", "libproot_loader32.so"}

PT_LOAD, PT_DYNAMIC = 1, 2
DT_NULL, DT_NEEDED, DT_STRTAB, DT_STRSZ, DT_SONAME = 0, 1, 5, 10, 14
DT_VERNEED, DT_VERNEEDNUM = 0x6FFFFFFE, 0x6FFFFFFF


def log(message: str) -> None:
    print(message, flush=True)


def is_system(name: str) -> bool:
    return name.split(".so")[0] in SYSTEM_STEMS


def canonical(name: str) -> str:
    """`libtalloc.so.2.5.0` / `libtalloc.so.2` -> `libtalloc.so`: the only shape jniLibs accepts."""
    base = re.sub(r"\.so(\..*)?$", "", name)
    base = base if base.startswith("lib") else "lib" + base
    return base + ".so"


class Elf:
    """Just enough of an ELF parser to find and rewrite the dynamic string table in place."""

    def __init__(self, path: pathlib.Path):
        self.path = path
        self.data = bytearray(path.read_bytes())
        if self.data[:4] != b"\x7fELF":
            raise ValueError(f"{path.name} is not an ELF file")
        self.bits = {1: 32, 2: 64}[self.data[4]]
        self.endian = {1: "<", 2: ">"}[self.data[5]]
        e = self.endian
        if self.bits == 64:
            phoff, = struct.unpack_from(e + "Q", self.data, 0x20)
            phentsize, phnum = struct.unpack_from(e + "HH", self.data, 0x36)
            phdr_fmt, dyn_fmt = e + "IIQQQQQQ", e + "qQ"
        else:
            phoff, = struct.unpack_from(e + "I", self.data, 0x1C)
            phentsize, phnum = struct.unpack_from(e + "HH", self.data, 0x2A)
            phdr_fmt, dyn_fmt = e + "IIIIIIII", e + "iI"

        self.loads: list[tuple[int, int, int]] = []  # (vaddr, offset, filesz)
        dynamic = None
        for i in range(phnum):
            fields = struct.unpack_from(phdr_fmt, self.data, phoff + i * phentsize)
            if self.bits == 64:
                p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz = fields[:6]
            else:
                p_type, p_offset, p_vaddr, _paddr, p_filesz = fields[:5]
            if p_type == PT_LOAD:
                self.loads.append((p_vaddr, p_offset, p_filesz))
            elif p_type == PT_DYNAMIC:
                dynamic = (p_offset, p_filesz)

        self.dyn: list[tuple[int, int]] = []
        if dynamic is not None:
            size = struct.calcsize(dyn_fmt)
            offset, filesz = dynamic
            for pos in range(offset, offset + filesz - size + 1, size):
                tag, value = struct.unpack_from(dyn_fmt, self.data, pos)
                if tag == DT_NULL:
                    break
                self.dyn.append((tag, value))

        self.strtab = self.strsz = None
        if self.dyn:
            self.strtab = self.file_offset(self.tag(DT_STRTAB))
            self.strsz = self.tag(DT_STRSZ)
            if self.strtab is None or self.strsz is None:
                raise ValueError(f"{path.name}: .dynamic has no usable DT_STRTAB/DT_STRSZ")

    @property
    def dynamic(self) -> bool:
        return bool(self.dyn)

    def tag(self, wanted: int):
        return next((value for tag, value in self.dyn if tag == wanted), None)

    def file_offset(self, vaddr):
        if vaddr is None:
            return None
        for start, offset, filesz in self.loads:
            if start <= vaddr < start + filesz:
                return vaddr - start + offset
        return None

    def string(self, index: int) -> str:
        start = self.strtab + index
        end = self.data.index(b"\0", start, self.strtab + self.strsz)
        return self.data[start:end].decode()

    def string_refs(self) -> list[tuple[str, int]]:
        """(kind, strtab index) of every name the dynamic linker matches: NEEDED, SONAME, verneed."""
        refs = [("NEEDED", v) for t, v in self.dyn if t == DT_NEEDED]
        refs += [("SONAME", v) for t, v in self.dyn if t == DT_SONAME]
        verneed, count = self.file_offset(self.tag(DT_VERNEED)), self.tag(DT_VERNEEDNUM) or 0
        while verneed is not None and count > 0:
            _version, _cnt, vn_file, _aux, vn_next = struct.unpack_from(self.endian + "HHIII", self.data, verneed)
            refs.append(("VERNEED", vn_file))
            count -= 1
            verneed = verneed + vn_next if vn_next else None
        return refs

    def needed(self) -> list[str]:
        return [self.string(v) for t, v in self.dyn if t == DT_NEEDED]

    def soname(self):
        index = self.tag(DT_SONAME)
        return None if index is None else self.string(index)

    def rename(self, renames: dict[str, str]) -> list[str]:
        changes = []
        for kind, index in self.string_refs():
            old = self.string(index)
            new = renames.get(old)
            if new is None or new == old:
                continue
            if len(new) > len(old):
                raise ValueError(f"{self.path.name}: {old} -> {new} does not fit in .dynstr")
            start = self.strtab + index
            self.data[start:start + len(old)] = new.encode() + b"\0" * (len(old) - len(new))
            changes.append(f"{kind} {old} -> {new}")
        return changes

    def save(self, target: pathlib.Path, mode: int) -> None:
        target.write_bytes(bytes(self.data))
        os.chmod(target, mode)


def readelf(path: pathlib.Path) -> tuple[list[str], str | None]:
    """NEEDED/SONAME as binutils sees them: an independent check of the Python rewrite."""
    result = subprocess.run(["readelf", "-d", str(path)], capture_output=True, text=True)
    text = result.stdout if result.returncode == 0 else ""
    sonames = re.findall(r"\(SONAME\).*?\[(.+?)\]", text)
    return re.findall(r"\(NEEDED\).*?\[(.+?)\]", text), (sonames[0] if sonames else None)


problems: list[str] = []

# ---------------------------------------------------------------- inventory
candidates = sorted(p for p in libdir.iterdir() if p.is_file()) if libdir.is_dir() else []
loaders: dict[int, Elf] = {}
providers: dict[str, Elf] = {}
parsed: dict[pathlib.Path, Elf] = {}  # one object per real file, whatever symlinks point at it
for path in sorted({*candidates, *(p for p in work.iterdir() if p.is_file() and "loader" in p.name)}):
    real = path.resolve()
    try:
        elf = parsed[real] if real in parsed else parsed.setdefault(real, Elf(real))
    except (ValueError, KeyError, struct.error) as error:
        log(f"    skipped     {path.name}: {error}")
        continue
    if "loader" in path.name and not path.name.endswith(".so") and ".so." not in path.name:
        # Prefer the canonical `loader` / `loader32` names when a bundle carries several.
        if elf.bits not in loaders or path.name in ("loader", "loader32"):
            loaders[elf.bits] = elf
        continue
    if not elf.dynamic:
        log(f"    skipped     {path.name}: static, not a shared library")
        continue
    # Every name the linker could ask for: the file, its symlink alias and its SONAME.
    for name in {path.name, elf.path.name, elf.soname() or path.name}:
        providers.setdefault(name, elf)


def provider_for(dependency: str):
    if dependency in providers:
        return providers[dependency]
    wanted = canonical(dependency)
    return next((elf for name, elf in providers.items() if canonical(name) == wanted), None)


# ---------------------------------------------------------------- closure
main = Elf(proot)
if not main.dynamic:
    log("proot is static: nothing to resolve")
shipped: dict[pathlib.Path, tuple[Elf, str]] = {}
renames: dict[str, str] = {}
queue = list(main.needed())
seen: set[str] = set()
while queue:
    dependency = queue.pop(0)
    if dependency in seen or is_system(dependency):
        continue
    seen.add(dependency)
    elf = provider_for(dependency)
    if elf is None:
        problems.append(f"{dependency} is needed but missing from proot-libs/")
        continue
    target = canonical(elf.soname() or elf.path.name)
    if target in RESERVED:
        problems.append(f"{elf.path.name} would be packaged as reserved name {target}")
        continue
    clash = next((other for other, name in shipped.values() if name == target and other is not elf), None)
    if clash is not None:
        problems.append(f"{elf.path.name} and {clash.path.name} both map to {target}")
        continue
    if elf.path not in shipped:
        shipped[elf.path] = (elf, target)
        queue.extend(elf.needed())
    renames[dependency] = target
    if elf.soname():
        renames[elf.soname()] = target

log("dependency closure of proot (resolved by SONAME):")
for elf, target in shipped.values():
    log(f"    {target:<24} <- {elf.path.name} (SONAME {elf.soname() or '-'})")
used = set(shipped) | {e.path for e in loaders.values()}
unused = sorted(p.name for p in candidates if p.resolve() not in used)
if unused:
    log(f"    not needed by proot, not packaged: {', '.join(unused)}")

# ---------------------------------------------------------------- rewrite + copy
log("rewriting versioned names in .dynstr:")
outputs: list[tuple[Elf, str, int]] = [(main, "libproot.so", 0o755)]
outputs += [(elf, target, 0o644) for elf, target in shipped.values()]
if 64 in loaders:
    outputs.append((loaders[64], "libproot_loader.so", 0o755))
if 32 in loaders:
    outputs.append((loaders[32], "libproot_loader32.so", 0o755))
for elf, name, mode in outputs:
    if elf.dynamic:
        try:
            for change in elf.rename(renames):
                log(f"    {name}: {change}")
        except ValueError as error:
            problems.append(str(error))
    elf.save(out / name, mode)
    kind = "dynamic" if elf.dynamic else "static"
    log(f"{name:<22} <- {elf.path.name} ({len(elf.data)} bytes, ELF{elf.bits}, {kind})")

# ---------------------------------------------------------------- verification (binutils)
# Every DT_NEEDED that is not a system library must be shipped under exactly that name, every
# shipped library must answer to its file name, and no versioned bionic name may survive.
packaged = {p.name for p in out.iterdir() if p.is_file()}
for name in sorted(packaged):
    if not re.fullmatch(r"lib[^/]*\.so", name):
        problems.append(f"{name} is not a lib*.so name; AGP and the installer would drop it")
    needs, soname = readelf(out / name)
    log(f"NEEDED {name}: {', '.join(needs) if needs else '(none)'}" + (f"  SONAME {soname}" if soname else ""))
    for dependency in needs:
        if is_system(dependency):
            continue
        if re.search(r"\.so\.\d", dependency):
            problems.append(f"{name} still needs the versioned name {dependency}")
        elif dependency not in packaged:
            problems.append(f"{name} needs {dependency}, which is not packaged")
    if soname is not None and name != "libproot.so" and soname != name:
        problems.append(f"{name} carries SONAME {soname}; the linker would not match it to DT_NEEDED")

if problems:
    log("::error title=unresolved proot dependencies::" + "; ".join(problems))
    sys.exit(1)

log(f"dependency closure ok ({len(packaged)} native files)")
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
    libc.so*|libdl.so*|libm.so*|liblog.so*|libz.so*|libandroid.so*|libstdc++.so*|libpthread.so*|librt.so*|libld-android.so*) continue ;;
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
  file "$OUT"/lib*.so || true
fi
ls -la "$OUT"
