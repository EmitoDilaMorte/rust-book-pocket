#!/usr/bin/env bash
#
# Builds the optional on-device Rust toolchain archive from pinned Termux
# packages.
#
# The app targets SDK 28, which keeps the legacy behaviour that lets an app
# execute files it wrote itself. That is what makes this possible: the archive
# is a plain zip, unpacked at first launch into the app's files directory with
# its original file names and SONAMEs. No patching, no renaming.
#
# Output:
#   pocket/app/src/main/assets/toolchain/toolchain.zip
#
# Usage:
#   pocket/spikes/local-exec/build-toolchain.sh [--clean] [--output DIR]
#
# Requirements: curl, sha256sum, tar, zip, and either dpkg-deb or ar.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../../.." && pwd)"
manifest="$here/toolchain-packages.txt"

mirror="${TERMUX_MIRROR:-https://packages.termux.dev/apt/termux-main}"
cache="${POCKET_TOOLCHAIN_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/pocket-toolchain}"
work="$cache/work"
stage="$work/stage"
output="$repo_root/pocket/app/src/main/assets/toolchain"
clean=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --clean) clean=1; shift ;;
        --output) output="$2"; shift 2 ;;
        --cache) cache="$2"; work="$cache/work"; stage="$work/stage"; shift 2 ;;
        -h|--help) sed -n '2,20p' "${BASH_SOURCE[0]}"; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

say() { printf '==> %s\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

for tool in curl sha256sum tar; do
    command -v "$tool" >/dev/null || die "'$tool' is required"
done

if ! command -v zip >/dev/null && ! command -v python3 >/dev/null; then
    die "need 'zip' or 'python3' to create the archive"
fi

if command -v dpkg-deb >/dev/null; then
    extract_deb() { dpkg-deb --extract "$1" "$2"; }
elif command -v ar >/dev/null; then
    extract_deb() {
        local deb="$1" dest="$2" tmp
        tmp="$(mktemp -d)"
        ( cd "$tmp" && ar x "$deb" )
        mkdir -p "$dest"
        local payload
        payload="$(find "$tmp" -maxdepth 1 -name 'data.tar.*' | head -1)"
        [[ -n "$payload" ]] || die "no data.tar in $deb"
        tar -xf "$payload" -C "$dest"
        rm -rf "$tmp"
    }
else
    die "need dpkg-deb (Debian/Ubuntu) or ar + tar"
fi

if [[ $clean -eq 1 ]]; then
    rm -rf "$work"
fi
mkdir -p "$cache/debs" "$stage/bin" "$stage/lib"

# ---------------------------------------------------------------------------
# 1. Fetch and verify
# ---------------------------------------------------------------------------

say "Verifying packages ($mirror)"

downloaded=0
while IFS='|' read -r name path sha; do
    [[ -z "$name" || "$name" == \#* ]] && continue

    file="$cache/debs/$(basename "$path")"
    if [[ -f "$file" ]] && echo "$sha  $file" | sha256sum -c --quiet - 2>/dev/null; then
        continue
    fi

    printf '    downloading %s\n' "$name"
    # The openssl filename contains an escaped colon, which curl leaves alone.
    curl -fsSL --retry 3 --retry-delay 2 -o "$file" "$mirror/$path" \
        || die "could not download $name from $mirror/$path"
    echo "$sha  $file" | sha256sum -c --quiet - \
        || die "checksum mismatch for $name (expected $sha)"
    downloaded=$((downloaded + 1))
done < "$manifest"

if [[ $downloaded -eq 0 ]]; then
    say "All packages already cached"
fi

# ---------------------------------------------------------------------------
# 2. Extract and merge
# ---------------------------------------------------------------------------

say "Extracting into the staging tree"
rm -rf "$stage"
mkdir -p "$stage/bin" "$stage/lib"

while IFS='|' read -r name path sha; do
    [[ -z "$name" || "$name" == \#* ]] && continue

    file="$cache/debs/$(basename "$path")"
    unpacked="$work/unpack/$name"
    if [[ ! -d "$unpacked" ]]; then
        mkdir -p "$unpacked"
        extract_deb "$file" "$unpacked"
    fi

    prefix="$unpacked/data/data/com.termux/files/usr"
    [[ -d "$prefix" ]] || die "unexpected layout in $name"

    # Copy bin/, lib/ and include/ payloads, following symlinks. Following them
    # is deliberate: Android's zip extraction turns a symlink into a file whose
    # content is the link text, which would break every library.
    for sub in bin lib include; do
        [[ -d "$prefix/$sub" ]] || continue
        cp -rL "$prefix/$sub/." "$stage/$sub/" 2>/dev/null || true
    done
done < "$manifest"

# ---------------------------------------------------------------------------
# 3. Trim to what compilation actually needs
# ---------------------------------------------------------------------------

say "Trimming"

# Other Android ABIs are not used by the app.
for target in "$stage"/lib/rustlib/*/; do
    base="$(basename "$target")"
    case "$base" in
        aarch64-linux-android|etc) ;;
        *) rm -rf "$target" ;;
    esac
done

# Only the compiler, the linker and lld are launched by the app.
for bin in "$stage"/bin/*; do
    case "$(basename "$bin")" in
        rustc|clang|lld) ;;
        *) rm -f "$bin" ;;
    esac
done

# `clang` invokes the linker by name, and rustc sometimes asks for `rust-lld`.
# Termux ships those as symlinks, which a zip cannot carry, so recreate them as
# copies. Without this, linking fails with
# "clang: error: unable to execute command: No such file or directory".
for alias in ld ld.lld rust-lld; do
    cp "$stage/bin/lld" "$stage/bin/$alias"
done

# rustc does not read C headers; the linker needs the crt objects and archives
# in lib/, not this tree.
rm -rf "$stage/include"

# Drop the copies of rustlib shared objects that Termux also exposes flat in
# lib/. The app puts both directories on LD_LIBRARY_PATH, so the originals are
# enough and this saves ~95 MB.
if [[ -d "$stage/lib/rustlib/aarch64-linux-android/lib" ]]; then
    for lib in "$stage"/lib/rustlib/aarch64-linux-android/lib/*.so*; do
        [[ -e "$lib" ]] || continue
        rm -f "$stage/lib/$(basename "$lib")"
    done
fi

# Keep only the shared libraries and linker inputs that remain useful.
find "$stage/lib" -maxdepth 1 -type f \
    \( -name '*.so' -o -name '*.so.*' -o -name '*.a' -o -name '*.o' \) \
    -print0 | xargs -0 -r chmod 0644 2>/dev/null || true

chmod 0755 "$stage/bin/rustc" "$stage/bin/clang" "$stage/bin/lld" \
    "$stage/bin/ld" "$stage/bin/ld.lld" "$stage/bin/rust-lld" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 4. Package
# ---------------------------------------------------------------------------

command -v rustc >/dev/null && say "Packaging with $(rustc --version 2>/dev/null || echo 'unknown rustc')"

payload_mb=$(du -sm "$stage" | cut -f1)
say "Staging tree: ${payload_mb} MB"

mkdir -p "$output"
rm -f "$output/toolchain.zip"

say "Writing $output/toolchain.zip"

# The app compares this version with the one it unpacked last time, so a
# repackaged toolchain always replaces the installed copy.
version="$(tr -d '[:space:]' < "$here/toolchain-version.txt")"
printf '%s\n' "$version" > "$stage/.version"
printf '%s\n' "$version" > "$output/version.txt"
say "Toolchain version: $version"

# Symlinks are followed on purpose: Android's zip extraction turns a symlink
# into a file whose content is the link text, which would break every library.
if command -v zip >/dev/null; then
    ( cd "$stage" && zip -r -q -X "$output/toolchain.zip" bin lib .version )
else
    # Some minimal environments have no `zip`; python3 is enough and produces
    # the same layout.
    ( cd "$stage" && python3 - "$output/toolchain.zip" <<'PY'
import os, sys, zipfile

out = sys.argv[1]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
    for root, dirs, files in os.walk("."):
        dirs.sort()
        for name in sorted(files):
            path = os.path.join(root, name)
            arc = os.path.relpath(path, ".")
            zf.write(path, arc)
PY
    )
fi

if command -v unzip >/dev/null; then
    unzip -tq "$output/toolchain.zip" >/dev/null || die "the archive is corrupt"
    entries=$(unzip -l "$output/toolchain.zip" | tail -1 | awk '{print $2}')
else
    entries=$(python3 -c "import zipfile,sys; print(len(zipfile.ZipFile(sys.argv[1]).namelist()))" "$output/toolchain.zip")
fi

zip_mb=$(( $(wc -c < "$output/toolchain.zip") / 1024 / 1024 ))
say "toolchain.zip: ${zip_mb} MB, ${entries} entries"
say "Unpacked on device: ~${payload_mb} MB"
say "next: rebuild the app (`gradlew :app:assembleDebug`); it unpacks automatically"
