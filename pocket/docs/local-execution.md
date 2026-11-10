# Running Rust on the device

Status: **working**. Both executors ship in the app; the on-device one is used
in `AUTO` mode whenever the toolchain is unpacked and the snippet is `std`-only.

## Measured results

Device: Xiaomi 2602EPTC0G (Snapdragon, arm64), Android 16 (API 36),
12 GB RAM, 869 GB free.

| Metric | Result |
|---|---|
| First compile in the app (cold page cache) | **1022 ms** |
| Subsequent compiles in the app | **408 ms** |
| Compiles via shell harness (5 runs) | 366–458 ms |
| Output binary size | 584 KB |
| Compiler errors | forwarded verbatim, paths rewritten to `main.rs` |
| Offline | yes, no network involved |
| Toolchain archive in APK | 192 MB stored |
| Release APK with the toolchain | **199 MB** |
| Debug APK with the toolchain | 209 MB |
| Free RAM during compile | ~3.9 GB of 11.5 GB |

The original target (under 10 s cold, under 3 s warm) is met with a wide margin.

## The decision that made it possible: `targetSdk = 28`

Android 10+ refuses `execve()` on files in the app's data directory
(W^X). Measurements from inside the app:

| Route | Result |
|---|---|
| Executable in `filesDir` | `error=13, Permission denied` |
| Executable in `filesDir` via a memfd helper in `filesDir` | same failure — the helper itself cannot run |
| Executable in `nativeLibraryDir` | works |
| Executable in `filesDir`, with `targetSdk = 28` | **works** |

Keeping the legacy target keeps the old behaviour, which is exactly what
Termux does. That single line removes three problems at once:

- the toolchain unpacks from assets into an ordinary directory, keeping its
  original file names and SONAMEs (no `patchelf`, no renames, no `jniLibs`),
- the compiler runs directly,
- the binaries it produces run directly.

Downsides: Play Store would reject this target; the app is distributed as an
APK from GitHub Releases, so that trade-off is acceptable. Android may show a
compatibility warning on very new releases; nothing else changes.

## What the spike established

1. **Termux's `rustc` runs on Android without Termux installed.** The `.deb`
   payloads are extracted on the build machine and repackaged. Version:
   Rust 1.99.1 (`58cf8f9c3`, 2026-10-13).
2. **The toolchain needs a specific layout.** `rustc` is a 4.7 KB launcher
   whose `DT_NEEDED` is `librustc_driver-<hash>.so`; the driver needs
   `libLLVM.so`, `libc++_shared.so`, `libandroid-execinfo.so`,
   `libandroid-support.so`, `libxml2.so.16`, `libz.so.1`, `libzstd.so.1`,
   `libffi.so`, `libncursesw.so.6`, `libiconv.so` and `liblzma.so.5`.
3. **`.rmeta` files are mandatory.** Termux builds Rust with metadata stubs in
   the `.rlib`s; deleting `.rmeta` to save space breaks every compile with
   "only metadata stub found for rlib dependency `std`".
4. **Linking needs clang plus `-L<libdir>`**, for `libunwind.a` and the crt
   objects. `-C linker=<clang>` alone fails with `unable to find library
   -lunwind`.
5. **Termux's symlinks matter.** `bin/rust-lld` → `lld` and the
   `lib/lib*.so` → `rustlib/aarch64-linux-android/lib/*.so` links are
   recreated as real files during packaging, because Windows' `tar` cannot
   create symlinks.

## Packaging

`pocket/spikes/local-exec/` contains the whole pipeline:

| Script | Purpose |
|---|---|
| `stage-toolchain.ps1` | Copies the core of the `.deb` payload into a tree |
| `merge-packages.ps1` | Merges clang, libllvm, lld, ndk-sysroot, libc++, rust-std |
| `trim-stage.ps1` | Drops other ABIs, `.rmeta`-free tools, editor binaries |
| `package-toolchain.ps1` | Zips `bin/`, `lib/` into `app/src/main/assets/toolchain/toolchain.zip` |
| `compile-test.sh`, `perf-test.sh` | Shell harnesses used for the measurements |

The archive is stored uncompressed in the APK (`noCompress += "zip"`) and
unpacked once at first launch by `LocalToolchainInstaller`, which marks the
install with `.version`. Bump `LocalToolchain.VERSION` when repackaging.

The `.deb` files themselves are not committed; re-download them from
`packages.termux.dev` if the toolchain has to be rebuilt.

## Distribution

Two shapes are supported, and both are built from the same sources:

| Build | Size | Local execution |
|---|---|---|
| **Lite** (CI, no toolchain assets) | ~17 MB | unavailable; the settings row is disabled with an explanation |
| **Full** (built locally with `package-toolchain.ps1`) | 199 MB release | available |

The CI workflow produces the lite APK because the packaging scripts are
PowerShell. A release that wants on-device execution attaches the full APK
built on a Windows machine.

## Remaining work

1. Rewrite the packaging scripts in bash so CI can produce the full APK.
2. Measure battery and thermal behaviour over ten consecutive compiles.
3. Consider an optional second APK / asset pack so the base download stays
   small and the toolchain is opt-in.
4. Add a check that the toolchain's glibc-free assumptions still hold if the
   pinned Rust version is bumped.
