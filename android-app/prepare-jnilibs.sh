#!/usr/bin/env bash
# Copies repo's bin/ and lib/ + files/ into the Android project as jniLibs / assets.
# Android 10+ only allows execve() of files under applicationInfo.nativeLibraryDir, and
# the package installer only extracts entries whose names match lib*.so. So every
# executable we want to run on-device has to be renamed lib<name>.so and dropped into
# arm64-v8a/.
#
# Mapping (must match NativeExec.kt):
#   bin/payload-dumper  -> libpayload_dumper.so
#   bin/extract.erofs   -> libextract_erofs.so
#   bin/mkfs.erofs      -> libmkfs_erofs.so
#   bin/lpmake          -> liblpmake.so
#   bin/magiskboot      -> libmagiskboot.so
#   bin/dex2oat         -> libdex2oat.so
#   bin/ksud            -> libksud.so
#   bin/kptools         -> libkptools.so
#   bin/busybox         -> libbusybox.so
#   bin/zipalign        -> libzipalign.so          (you must drop a static arm64 zipalign into bin/)
#   bin/kpimg           -> assets/blobs/kpimg      (kernel image, not executable)
#   lib/lib*.so         -> arm64-v8a/lib*.so       (verbatim — dex2oat needs them)
#   files/**            -> assets/files/**
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/android-app/app/src/main"
JNI="$APP/jniLibs/arm64-v8a"
BLOBS="$APP/assets/blobs"
FILES_DST="$APP/assets/files"

mkdir -p "$JNI" "$BLOBS"

copy_bin() {
    local src="$ROOT/bin/$1"
    local dst="$JNI/$2"
    if [[ ! -f "$src" ]]; then
        echo "  [skip] missing $src" >&2
        return
    fi
    install -m 0755 "$src" "$dst"
    echo "  bin/$1 -> jniLibs/arm64-v8a/$2"
}

copy_bin payload-dumper libpayload_dumper.so
copy_bin extract.erofs  libextract_erofs.so
copy_bin mkfs.erofs     libmkfs_erofs.so
copy_bin lpmake         liblpmake.so
copy_bin magiskboot     libmagiskboot.so
copy_bin dex2oat        libdex2oat.so
copy_bin ksud           libksud.so
copy_bin kptools        libkptools.so
copy_bin busybox        libbusybox.so
copy_bin zipalign       libzipalign.so

# kpimg is a kernel image (not exec)
if [[ -f "$ROOT/bin/kpimg" ]]; then
    install -m 0644 "$ROOT/bin/kpimg" "$BLOBS/kpimg"
    echo "  bin/kpimg -> assets/blobs/kpimg"
fi

# ART shared libs — only the closure that bin/dex2oat actually links against.
# Everything else under repo lib/ (libopenjdk*, libjdwp*, libadbconnection*, libart-disassembler,
# libartservice, libarttools, libnpt, libperfetto_hprof, libziparchive, libnativehelper,
# libexpat, libjavacore, libandroidio, libdt_*) is for dalvikvm / oatdump / artd / debugger and is
# *not* loaded by a bare dex2oat run. Verified via `readelf -d bin/dex2oat` + recursive closure.
ART_KEEP=(
    libart.so
    libart-compiler.so
    libart-dexlayout.so
    libartbase.so
    libartpalette.so
    libbase.so
    libc++.so
    libcrypto.so
    libdexfile.so
    libjsoncpp.so
    liblz4.so
    liblzma.so
    libnativebridge.so
    libnativeloader.so
    libprofile.so
    libsigchain.so
    libunwindstack.so
)
for name in "${ART_KEEP[@]}"; do
    src="$ROOT/lib/$name"
    if [[ -f "$src" ]]; then
        install -m 0755 "$src" "$JNI/$name"
        echo "  lib/$name -> jniLibs/arm64-v8a/$name"
    else
        echo "  [warn] missing required ART lib: $src" >&2
    fi
done

# apktool / APKEditor / D8 are no longer required: Settings.apk + every dex-only patch goes
# through ARSCLib + dexlib2 + smali assembler, all linked as Gradle deps. assets/tools/ is empty.

# files/ tree (replacement APKs, smali patches, vbmeta/cust prebuilt images, config lists, flash.bat)
rm -rf "$FILES_DST"
mkdir -p "$FILES_DST"
cp -a "$ROOT/files/." "$FILES_DST/"
echo "  files/ -> assets/files/"

echo "Done. jniLibs and assets prepared under $APP"
