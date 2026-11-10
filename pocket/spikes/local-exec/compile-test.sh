#!/system/bin/sh
# Compiles and runs a hello-world with the staged toolchain, timing each step.
# Usage: sh compile-test.sh /data/local/tmp/spike
root="${1:-/data/local/tmp/spike}"
cd "$root" || exit 1

export LD_LIBRARY_PATH="$root/lib/rustlib/aarch64-linux-android/lib:$root/lib"
export TMPDIR="$root/tmp"
mkdir -p "$TMPDIR"

cat > hello.rs <<'RS'
fn main() {
    let values = vec![1, 2, 3];
    let total: i32 = values.iter().sum();
    println!("hello from the phone: {total}");
}
RS

echo "--- rustc version"
./bin/rustc --version

echo "--- cold compile"
start=$(date +%s%N)
./bin/rustc --edition 2024 \
    -C "linker=$root/bin/clang" \
    -C "link-arg=-L$root/lib" \
    hello.rs -o hello 2>&1 | head -30
status=$?
end=$(date +%s%N)
echo "compile_ms=$(( (end - start) / 1000000 )) exit=$status"

if [ -x ./hello ]; then
    echo "--- run"
    ./hello
    start=$(date +%s%N)
    ./bin/rustc --edition 2024 \
        -C "linker=$root/bin/clang" \
        -C "link-arg=-L$root/lib" \
        hello.rs -o hello2 2>&1 | head -5
    end=$(date +%s%N)
    echo "warm_compile_ms=$(( (end - start) / 1000000 ))"
    echo "--- binary"
    ls -l hello
else
    echo "no binary produced"
fi
