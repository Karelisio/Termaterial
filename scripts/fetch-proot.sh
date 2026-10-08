#!/usr/bin/env bash
# Stages Termux's prebuilt proot (and the two libraries it links) for every ABI the app ships, as
# app/src/main/jniLibs/<abi>/lib*.so, so that they are packaged in the APK and extracted to the
# app's nativeLibraryDir at install time: the one place an app can always execute files from.
#
#   libproot.so           <- bin/proot
#   libproot-loader.so    <- libexec/proot/loader     (static helper proot execs for every program)
#   libproot-loader32.so  <- libexec/proot/loader32   (64-bit ABIs only, for 32-bit programs)
#   libtalloc.so          <- lib/libtalloc.so.2
#   libandroid-shmem.so   <- lib/libandroid-shmem.so
#
# The binaries are Termux's own, unmodified except for a few dynamic-section strings, rewritten in
# place by patch-elf-strings.py (nothing in the files moves) so that they load from
# nativeLibraryDir: proot's DT_RUNPATH (Termux's /data/data/com.termux/files/usr/lib) becomes
# $ORIGIN, and libtalloc.so.2 becomes libtalloc.so (Android only extracts files named lib*.so from
# an APK).
#
# Each .deb is located through the repository's package index and checked against the SHA-256
# it lists. Requires: curl, python3, dpkg-deb, readelf, sha256sum (and xz/gzip for the index).
#
# Usage: scripts/fetch-proot.sh [output jniLibs dir]
set -euo pipefail

PATCH_ELF="$(dirname "$0")/patch-elf-strings.py"
REPO="${TERMUX_REPO:-https://packages-cf.termux.dev/apt/termux-main}"
OUT="${1:-app/src/main/jniLibs}"
PACKAGES=(proot libtalloc libandroid-shmem)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

abi_for_arch() {
    case "$1" in
        aarch64) echo arm64-v8a ;;
        arm) echo armeabi-v7a ;;
        x86_64) echo x86_64 ;;
        i686) echo x86 ;;
        *) echo "unknown arch $1" >&2; return 1 ;;
    esac
}

# Downloads the package index of one architecture, in whichever compression the repository
# offers, to $2 (uncompressed).
fetch_index() {
    local arch="$1" dest="$2" base="$REPO/dists/stable/main/binary-$1/Packages"
    if curl -fsSL "$base.xz" -o "$dest.xz"; then
        xz -d -c "$dest.xz" > "$dest"
    elif curl -fsSL "$base.gz" -o "$dest.gz"; then
        gzip -d -c "$dest.gz" > "$dest"
    else
        curl -fsSL "$base" -o "$dest"
    fi
}

# Copies $2 (a path under the extracted package root $1) to $3, following symlinks - re-rooting
# absolute ones into $1, since they name on-device Termux paths.
copy_resolved() {
    local root="$1" path="$2" dest="$3" target
    while [ -L "$path" ]; do
        target="$(readlink "$path")"
        case "$target" in
            /*) path="$root$target" ;;
            *) path="$(dirname "$path")/$target" ;;
        esac
    done
    install -m 0755 "$path" "$dest"
}

# Prints "<Filename> <SHA256> <Version>" of package $2 from index $1 - its newest version, should
# the index list several - or nothing if it is not there.
package_entry() {
    python3 - "$1" "$2" <<'EOF' | sort -V -k3 | tail -n 1
import sys
index, wanted = sys.argv[1], sys.argv[2]
for stanza in open(index, encoding="utf-8").read().split("\n\n"):
    fields = {}
    for line in stanza.splitlines():
        if line and not line[0].isspace() and ":" in line:
            key, value = line.split(":", 1)
            fields[key] = value.strip()
    if fields.get("Package") == wanted:
        print(fields["Filename"], fields["SHA256"], fields["Version"])
EOF
}

for arch in aarch64 arm x86_64 i686; do
    abi="$(abi_for_arch "$arch")"
    index="$WORK/Packages-$arch"
    root="$WORK/root-$arch"
    fetch_index "$arch" "$index"

    for pkg in "${PACKAGES[@]}"; do
        filename="" sha256="" version=""
        read -r filename sha256 version < <(package_entry "$index" "$pkg") || true
        if [ -z "$filename" ]; then
            echo "$pkg not found for $arch in $REPO" >&2
            exit 1
        fi
        deb="$WORK/$pkg-$arch.deb"
        curl -fsSL "$REPO/$filename" -o "$deb"
        echo "$sha256  $deb" | sha256sum -c --quiet -
        dpkg-deb -x "$deb" "$root"
        echo "$abi: $pkg $version ($filename)"
        if [ -n "${PROOT_MANIFEST:-}" ]; then
            echo "$abi $pkg $version $REPO/$filename" >> "$PROOT_MANIFEST"
        fi
    done

    prefix="$root/data/data/com.termux/files/usr"
    dest="$OUT/$abi"
    mkdir -p "$dest"
    copy_resolved "$root" "$prefix/bin/proot" "$dest/libproot.so"
    copy_resolved "$root" "$prefix/libexec/proot/loader" "$dest/libproot-loader.so"
    if [ -e "$prefix/libexec/proot/loader32" ]; then
        copy_resolved "$root" "$prefix/libexec/proot/loader32" "$dest/libproot-loader32.so"
    fi
    copy_resolved "$root" "$prefix/lib/libtalloc.so.2" "$dest/libtalloc.so"
    copy_resolved "$root" "$prefix/lib/libandroid-shmem.so" "$dest/libandroid-shmem.so"

    python3 "$PATCH_ELF" "$dest/libproot.so" --runpath '$ORIGIN' --replace-needed libtalloc.so.2 libtalloc.so
    python3 "$PATCH_ELF" "$dest/libtalloc.so" --soname libtalloc.so
    # The two libraries only need Android's own libraries: their RUNPATH, if any, is just made
    # harmless rather than searched in vain.
    for lib in libtalloc.so libandroid-shmem.so; do
        if readelf -d "$dest/$lib" | grep -qE '\((RUNPATH|RPATH)\)'; then
            python3 "$PATCH_ELF" "$dest/$lib" --runpath '$ORIGIN'
        fi
    done

    # Everything proot links must now be either bundled next to it or provided by Android.
    for needed in $(readelf -d "$dest/libproot.so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'); do
        case "$needed" in
            libc.so|libdl.so|libm.so|liblog.so) ;;
            *) [ -f "$dest/$needed" ] || { echo "libproot.so needs $needed, which is not bundled" >&2; exit 1; } ;;
        esac
    done
done
