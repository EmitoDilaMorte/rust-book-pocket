#!/system/bin/sh
# Recreates the SONAME symlinks Termux ships (libz.so.1.4 -> libz.so.1, etc.)
# and the executables that Android needs by their canonical names.
#
# Run on the device after extracting the toolchain payload:
#   adb push fixup-toolchain.sh /data/local/tmp/spike/
#   adb shell sh /data/local/tmp/spike/fixup-toolchain.sh /data/local/tmp/spike

root="${1:-/data/local/tmp/spike}"
lib="$root/lib"

cd "$lib" || exit 1
for f in lib*.so.*.*; do
    [ -e "$f" ] || continue
    soname=$(echo "$f" | sed -E 's/^(lib[^.]*\.so\.[0-9]+)\.*.*/\1/')
    if [ "$soname" != "$f" ]; then
        ln -sf "$f" "$soname"
    fi
done

# rustc loads its driver from the target lib dir on some builds.
for so in "$lib"/rustlib/aarch64-linux-android/lib/librustc_driver-*.so; do
    [ -e "$so" ] || continue
    ln -sf "$so" "$lib/$(basename "$so")"
done

cd "$root" || exit 1
chmod -R 755 bin lib 2>/dev/null

echo "libs: $(ls "$lib" | wc -l)"
echo "bin:  $(ls bin | wc -l)"
