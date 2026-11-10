#!/system/bin/sh
# Samples SoC thermal zones while a compile loop runs in the background.
#
# The battery sensor barely reacts to short CPU bursts, so this reads the
# thermal zones directly to see how hot the SoC gets during sustained compiles.
#
# Usage: sh load-sample.sh /data/local/tmp/spike [iterations] [sample_seconds]
root="${1:-/data/local/tmp/spike}"
iterations="${2:-400}"
sample_seconds="${3:-10}"
cd "$root" || exit 1

export LD_LIBRARY_PATH="$root/lib/rustlib/aarch64-linux-android/lib:$root/lib"
export TMPDIR="$root/tmp"
mkdir -p "$TMPDIR"

zones_of_interest() {
    for zone in /sys/class/thermal/thermal_zone*; do
        [ -r "$zone/temp" ] || continue
        kind=$(cat "$zone/type" 2>/dev/null)
        value=$(cat "$zone/temp" 2>/dev/null)
        [ -n "$value" ] && echo "$kind=$value"
    done
}

echo "--- zones at rest"
zones_of_interest | sort | head -14

# Background load loop.
(
    i=1
    while [ $i -le "$iterations" ]; do
        rm -f load
        ./bin/rustc --edition 2024 \
            -C "linker=$root/bin/clang" \
            -C "link-arg=-L$root/lib" \
            load.rs -o load 2>>err.txt || break
        i=$((i + 1))
    done
    echo done > load.finished
) &

rm -f load.finished

elapsed=0
while [ "$elapsed" -lt 60 ]; do
    sleep "$sample_seconds"
    elapsed=$((elapsed + sample_seconds))
    echo "--- t=${elapsed}s"
    zones_of_interest | sort | head -8
    [ -f load.finished ] && break
done

rm -f load.finished
echo "--- final battery"
dumpsys battery | grep -E "temperature|level|status"
