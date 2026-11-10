# Running Rust on the device

Status: **working**. Both executors ship in the app; the on-device one is used
in `AUTO` mode whenever the toolchain is unpacked and the snippet is `std`-only.

## Measured results

Device: Xiaomi 2602EPTC0G (Snapdragon, arm64), Android 16 (API 36),
12 GB RAM, 869 GB free.

| Metric | Result |
|---|---|
| First compile in the app (cold page cache) | **1022 ms** |
| Subsequent compiles in the app | **408–617 ms** |
| Compiles via shell harness (5 runs) | 366–458 ms |
| Output binary size | 584 KB |
| Compiler errors | forwarded verbatim, paths rewritten to `main.rs` |
| Offline | yes, no network involved |
| Toolchain archive in APK | 226 MB stored |
| Unpacked size on device | 716 MB |
| Unpack time on first launch | ~4 s |
| Debug APK with the toolchain | 242 MB |
| Free RAM during compile | ~3.9 GB of 11.5 GB |

The original target (under 10 s cold, under 3 s warm) is met with a wide margin.

## Sustained load

200 consecutive compiles of a small program, sampled every 50:

| Sample | Battery temp | Wall clock |
|---|---|---|
| Start | 27.6 °C | — |
| 50 | 27.6 °C | 19.8 s |
| 100 | 27.6 °C | 39.7 s |
| 150 | 27.5 °C | 59.6 s |
| 200 | 27.5 °C | 79.2 s |

Average **395 ms per compile**, no measurable temperature rise and no throttling:
compiles stay flat from the first sample to the last. This is expected — each
compile is a ~0.4 s burst of two cores, and the SoC sheds that heat between runs.
The battery sensor barely moves at this load, so energy per compile could not be
isolated; a 30-minute session would be needed to measure that meaningfully.

Caveat: the device was charging, so the charge counter could not be used as an
energy proxy. Re-run `spikes/local-exec/thermal-test.sh` unplugged if that number
matters.

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
   recreated as real files during packaging, because a zip cannot carry a
   symlink. See "The linker-name trap" below.

## Packaging

`build-toolchain.sh` is the authoritative pipeline: it downloads the pinned
packages listed in `toolchain-packages.txt`, verifies their SHA-256, extracts
them, trims what the app never uses, materialises the linker aliases and writes
the archive plus `version.txt`. It is what CI runs, and it works on Linux,
macOS and WSL.

The PowerShell scripts are the original Windows exploration and are kept because
they are useful for inspecting individual pieces; `package-toolchain.ps1` can
still produce a working archive from a tree built by the other three.

| Script | Purpose |
|---|---|
| `build-toolchain.sh` | End-to-end: download, verify, extract, trim, package |
| `toolchain-packages.txt` | Pinned package list with checksums |
| `toolchain-version.txt` | Version stamped into the archive |
| `stage-toolchain.ps1` | Copies the core of the `.deb` payload into a tree |
| `merge-packages.ps1` | Merges clang, libllvm, lld, ndk-sysroot, libc++, rust-std |
| `trim-stage.ps1` | Drops other ABIs, `.rmeta`-free tools, editor binaries |
| `package-toolchain.ps1` | Zips `bin/`, `lib/` into `app/src/main/assets/toolchain/toolchain.zip` |
| `compile-test.sh`, `perf-test.sh` | Shell harnesses used for the measurements |

The archive is stored uncompressed in the APK (`noCompress += "zip"`) and
unpacked at first launch by `LocalToolchainInstaller`, which leaves a `.version`
marker so later launches skip the work.

The `.deb` files themselves are not committed; `build-toolchain.sh` downloads
them into `~/.cache/pocket-toolchain` (override with `--cache` or
`POCKET_TOOLCHAIN_CACHE`) and verifies every checksum.

## Distribution

Two shapes are supported, and both are built from the same sources:

| Build | Size | Local execution |
|---|---|---|
| **Lite** (CI, no toolchain assets) | ~17 MB | unavailable; the settings row is disabled with an explanation |
| **Full** (toolchain bundled) | 242 MB debug | available |

Both are produced by CI: the `lite` job runs on every pull request, and the
`full` job runs on tags and manual dispatches. The full job calls
`spikes/local-exec/build-toolchain.sh`, which is the authoritative pipeline, and
caches the resulting archive keyed on `toolchain-packages.txt`.

## The linker-name trap

`clang` invokes the linker as `ld` (a name it resolves through `PATH`), and
`rustc` sometimes asks for `rust-lld`. Termux ships those as **symlinks**, and a
zip archive cannot carry a symlink: Android's extractor writes a regular file
whose content is the link text instead. The result is a confusing
`clang: error: unable to execute command: No such file or directory` from
inside a compile that otherwise looks fine.

The fix is to materialise them as copies of `lld` before packaging, and to put
the toolchain's `bin/` first on `PATH` when the executor runs the compiler.
`build-toolchain.sh` and `package-toolchain.ps1` both do the former;
`LocalExecutor` does the latter.

## Versioning the archive

`build-toolchain.sh` writes `assets/toolchain/version.txt` next to the archive
and a `.version` marker inside it. On launch,
`LocalToolchainInstaller` compares that value with the marker left by the
previous unpack and re-unpacks only when they differ.

There is no version constant in Kotlin on purpose. An earlier build had one, and
a rebuilt archive was silently skipped because the constant had not been bumped,
which cost an hour of chasing a "broken" compiler that was actually stale.
Bump `toolchain-version.txt` whenever the packaged contents change.

## Remaining work

1. Consider an optional second APK / asset pack so the base download stays
   small and the toolchain is opt-in.
2. Measure energy per compile over a long session on battery (see the caveat
   above).
3. Add a check that the toolchain's assumptions still hold if the pinned Rust
   version is bumped — a mismatched `std` rlib changes the hash in the path.
