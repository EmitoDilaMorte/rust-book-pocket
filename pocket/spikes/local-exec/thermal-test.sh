#!/system/bin/sh
# Measures sustained-load behaviour of the on-device toolchain.
#
# Runs repeated compiles and records wall-clock time and battery temperature, so
# the heat cost of a compile session can be characterised.
#
# Two Android quirks shaped this script:
#   * `date +%s%N` cannot be used for differences: `/system/bin/sh` does 32-bit
#     arithmetic, so 19-digit nanosecond values wrap and produce nonsense.
#     `/proc/uptime` in centiseconds fits.
#   * the device must be unplugged for the charge counter to mean anything.
#
# Usage: sh thermal-test.sh /data/local/tmp/spike [iterations]
root="${1:-/data/local/tmp/spike}"
iterations="${2:-200}"
cd "$root" || exit 1

export LD_LIBRARY_PATH="$root/lib/rustlib/aarch64-linux-android/lib:$root/lib"
export TMPDIR="$root/tmp"
mkdir -p "$TMPDIR"

cat > load.rs <<'RS'
fn main() {
    let mut total: u64 = 0;
    let mut values: Vec<u64> = (1..=2000).map(|n| n as u64).collect();
    values.sort_by(|a, b| b.cmp(a));
    for value in &values {
        total = total.wrapping_add(value * value);
    }
    println!("{total}");
}
RS

# Centiseconds since boot as an integer small enough for 32-bit arithmetic.
now_cs() {
    cut -d' ' -f1 /proc/uptime | tr -d '.' | cut -c1-9
}

battery_field() {
    dumpsys battery | grep -E "^  $1:" | head -1 | sed 's/.*: *//'
}

status=$(battery_field status)
start_temp=$(battery_field temperature)
start_charge=$(battery_field 'Charge counter')
start_level=$(battery_field level)

echo "--- before"
echo "temp_c_x10=$start_temp level=$start_level status=$status charge_uah=${start_charge:-n/a}"

start=$(now_cs)
i=1
while [ $i -le "$iterations" ]; do
    rm -f load
    ./bin/rustc --edition 2024 \
        -C "linker=$root/bin/clang" \
        -C "link-arg=-L$root/lib" \
        load.rs -o load 2>err.txt || { head -5 err.txt; break; }

    if [ $((i % 50)) -eq 0 ]; then
        elapsed=$(( $(now_cs) - start ))
        echo "  after $i compiles: temp_c_x10=$(battery_field temperature) elapsed_cs=$elapsed"
    fi

    i=$((i + 1))
done
end=$(now_cs)

done_count=$((i - 1))
elapsed_cs=$((end - start))
end_temp=$(battery_field temperature)
end_charge=$(battery_field 'Charge counter')

echo "--- after"
echo "temp_c_x10=$end_temp level=$(battery_field level) charge_uah=${end_charge:-n/a}"

if [ "$done_count" -gt 0 ] && [ "$elapsed_cs" -gt 0 ]; then
    echo "iterations=$done_count total_ms=$((elapsed_cs * 10)) avg_ms=$((elapsed_cs * 10 / done_count))"
else
    echo "iterations=$done_count total_ms=? avg_ms=? (timing unavailable)"
fi

echo "temp_delta_c_x10=$((end_temp - start_temp))"

if [ "$status" -eq 2 ]; then
    echo "charge_delta_uah=n/a (device is charging)"
elif [ -n "$start_charge" ] && [ -n "$end_charge" ]; then
    delta=$((start_charge - end_charge))
    echo "charge_delta_uah=$delta"
    if [ "$done_count" -gt 0 ]; then
        echo "charge_per_compile_uah=$((delta / done_count))"
    fi
else
    echo "charge_delta_uah=n/a"
fi

echo "--- battery capacity"
dumpsys battery | grep -E "Charge counter|Capacity|Cycle" || true
