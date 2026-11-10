# Running Rust on the device

Status: **not implemented**. The app currently routes every run to the Rust
Playground (public or self-hosted). This document is the working plan for the
local executor.

## Goal

Compile and run the book's code snippets with no network connection, fast
enough to feel like a notebook: a few seconds for the small programs in
chapters 1–6, and no more than ~10 seconds for the longest examples.

## Constraints

- **No `execve` from the app's data directory.** Since Android 10, apps cannot
  execute files they wrote to their private storage ([W^X]). Only code inside
  the APK (native libraries) is executable directly.
- **No Play Store.** The app ships as an APK from GitHub Releases, so download
  size and bundling rules are much more forgiving than for a store listing. A
  ~300 MB optional toolchain pack is acceptable if the user opts in.
- **Only `std`.** Snippets that use `rand` (chapter 2) or `trpl` (chapter 17)
  must stay on the playground unless those crates are vendored too.
- **Device storage and RAM vary.** The toolchain should be optional, verified
  with a checksum, and removable from the settings screen.

[W^X]: https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission

## Approach A (preferred): bundled `rustc` + `std` + `lld`, launched with `memfd`

1. Build or obtain `rustc`, `rust-std` and `rust-lld` for
   `aarch64-linux-android`. The Termux project already maintains the patches
   needed to build this toolchain for Android; pin a version and record the
   patches used.
2. Ship the binaries in an APK asset pack or as native libraries under
   `jniLibs/arm64-v8a/`. Native libraries are extracted to
   `nativeLibraryDir`, which is executable.
3. For the compiler output (a freshly written ELF binary), read the file into
   memory and execute it with a small native helper that calls
   `memfd_create` + `fexecve`, or use `/proc/self/fd/<n>`. This keeps the
   binary out of the app's data directory and satisfies W^X.
4. Run `rustc --edition 2024 --crate-type bin` with a private
   `--sysroot` inside a scratch directory, a wall-clock timeout, and a byte
   limit on captured output.

### Open questions

- Does `rustc` itself run when launched from `nativeLibraryDir`, or does it
  also need the `memfd` helper (it spawns `rust-lld` and reads its own
  sysroot)?
- How large is a stripped `rustc` + `std` + `lld` for one ABI? Estimate:
  250–400 MB installed.
- Cold compile time on a mid-range phone: expect 1–4 s for a hello world with
  `-C debuginfo=0 -C opt-level=0`.

## Approach B: userland sandbox (fallback)

Ship a minimal Linux userland (Termux-style) and run the toolchain inside it
with `proot`. This avoids the `exec` restriction but adds a lot of size and
complexity, and slows every compile. Only pursue if Approach A fails.

## Approach C: WASM (rejected)

Running a Rust compiler inside a WASM runtime on the phone is not practical:
there is no maintained `rustc` build targeting WASI that can also produce
Android binaries.

## Integration points (already in place)

- `RustExecutor` is the only interface the bridge sees.
- `ExecutorRouter` implements `AUTO` / `REMOTE` / `LOCAL` and already prefers
  the local executor when it is available and the snippet is `std`-only.
- `CodeHeuristics.requiresExternalCrates` decides when a snippet must go to
  the playground.
- The settings sheet already shows the three modes; `LOCAL` is disabled until
  the executor reports itself available.

Wiring the toolchain in means:

1. Implement `LocalExecutor : RustExecutor` (`isAvailable`, `execute`).
2. Implement `LocalToolchainManager` (install / verify / delete / size).
3. Pass the manager's executor to `ExecutorRouter` in `ReaderViewModel`.
4. Enable the `LOCAL` radio button when `isAvailable()` is true.

## Spike checklist

- [ ] `rustc --version` runs on a physical arm64 device from
      `nativeLibraryDir`.
- [ ] A hello-world binary produced at runtime runs through the `memfd`
      helper.
- [ ] A snippet with a compile error returns the compiler's stderr unchanged.
- [ ] A snippet that loops forever is killed after the timeout.
- [ ] Peak RSS stays below 1.5 GB for the chapter 1–6 examples.
- [ ] Install size, cold/warm compile times and battery impact are recorded in
      this document.

## Decision log

| Date | Decision | Reason |
|---|---|---|
| — | Ship remote first | Unblocks reading and running while the toolchain spike runs. |
