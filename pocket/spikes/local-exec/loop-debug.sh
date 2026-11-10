#!/system/bin/sh
# Debug helper: verifies that the compile loop really compiles each iteration.
root="${1:-/data/local/tmp/spike}"
iterations="${2:-5}"
cd "$root" || exit 1

export LD_LIBRARY_PATH="$root/lib/rustlib/aarch64-linux-android/lib:$root/lib"
export TMPDIR="$root/tmp"
mkdir -p "$TMPDIR"

i=1
while [ $i -le "$iterations" ]; do
    rm -f load
    up_before=$(cut -d' ' -f1 /proc/uptime)
    ./bin/rustc --edition 2024 \
        -C "linker=$root/bin/clang" \
        -C "link-arg=-L$root/lib" \
        load.rs -o load 2>err.txt
    code=$?
    up_after=$(cut -d' ' -f1 /proc/uptime)
    size=$(ls -l load 2>/dev/null | awk '{print $5}')
    err_lines=$(wc -l < err.txt)
    echo "iter=$i exit=$code before=$up_before after=$up_after size=${size:-none} err_lines=$err_lines"
    i=$((i + 1))
done
