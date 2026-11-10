#!/system/bin/sh
# Measures compile performance with the staged toolchain.
# Usage: sh perf-test.sh /data/local/tmp/spike
root="${1:-/data/local/tmp/spike}"
cd "$root" || exit 1

export LD_LIBRARY_PATH="$root/lib/rustlib/aarch64-linux-android/lib:$root/lib"
export TMPDIR="$root/tmp"
mkdir -p "$TMPDIR"

cat > perf.rs <<'RS'
fn main() {
    let mut values: Vec<i32> = (1..=100).rev().collect();
    values.sort();
    let total: i32 = values.iter().sum();
    println!("sum={total} first={} last={}", values[0], values[99]);
}
RS

echo "--- compile timings (ms)"
i=1
while [ $i -le 5 ]; do
    rm -f perf
    start=$(date +%s%N)
    ./bin/rustc --edition 2024 \
        -C "linker=$root/bin/clang" \
        -C "link-arg=-L$root/lib" \
        perf.rs -o perf 2>err.txt
    if [ $? -ne 0 ]; then
        head -5 err.txt
        break
    fi
    end=$(date +%s%N)
    echo "run$i=$(( (end - start) / 1000000 ))"
    i=$((i + 1))
done

echo "--- output"
./perf

echo "--- sizes"
ls -l perf | awk '{print "binary_bytes=" $5}'
du -sk "$root" | awk '{print "toolchain_kb=" $1}'

echo "--- error path"
printf 'fn main() { let x = 1; x = 2; }\n' > bad.rs
./bin/rustc --edition 2024 \
    -C "linker=$root/bin/clang" \
    -C "link-arg=-L$root/lib" \
    bad.rs -o bad 2>&1 | head -6
