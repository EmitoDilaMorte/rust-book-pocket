# Rust Book Pocket

An Android reader for *The Rust Programming Language* that keeps the book's
interactive quizzes and can run the code samples, so you can study from a phone
without a laptop.

The app is a thin native shell around the same HTML that `mdbook` produces for
the book. Nothing in `src/`, `quizzes/` or `listings/` is modified:
`pocket/book/book.toml` reads them in place, and it is now the only mdBook
configuration in the repository.

## What works today

- Full book, all 21 chapters and appendices, bundled offline in the APK.
- All 93 quizzes (222 questions) rendered by `mdbook-quiz`.
- Search, themes, sidebar navigation and the book's own typography.
- **Run** buttons on code blocks, with two executors:
  - **on this device** — a Rust 1.99.1 toolchain unpacked from the APK,
    offline, ~0.4–1 s per compile on a mid-range phone;
  - **Rust Playground** — public instance or any self-hosted one.
- A settings sheet (gear icon in the book's menu bar) with:
  - execution mode: automatic / playground / on this device,
  - custom playground URL for self-hosted instances,
  - text size,
  - clear reading progress,
  - update check against GitHub Releases,
  - licenses and attribution.
- Reading progress (last chapter and scroll position) stored locally.

## Not implemented yet

- Editing code blocks. The bundled `mdBook` only makes blocks editable when the
  book source marks them ` ```rust,editable `.
- Crates in on-device mode. Snippets that use `rand` (chapter 2) or `trpl`
  (chapter 17) are sent to the playground automatically, because the bundled
  toolchain only ships `std`.

## Two builds

| Build | Size | On-device execution |
|---|---|---|
| **Lite** (CI, every pull request) | ~17 MB | no; the settings row is disabled |
| **Full** (CI on tags) | 242 MB debug | yes |

The toolchain is not committed. To build the full APK yourself:

```bash
bash pocket/spikes/local-exec/build-toolchain.sh   # downloads + repacks, verifies checksums
cd pocket && bash gradlew :app:assembleDebug
```

The script caches the Termux packages in `~/.cache/pocket-toolchain`, so a
second run takes a few seconds. On Windows it works from WSL.

## Minimum API level and the `targetSdk = 28` trade-off

The app targets SDK 28 on purpose. Since Android 10, an app cannot `execve()`
files it wrote itself, which would rule out running a compiler from the app's
own storage. Keeping the legacy target keeps that behaviour, the same choice
Termux makes, and it is what makes on-device Rust possible.

The app is distributed as an APK from GitHub Releases, so the Play Store rule
that requires a recent target does not apply. See
[`docs/local-execution.md`](docs/local-execution.md) for the measurements and
the alternatives that were ruled out.

## Requirements

- JDK 17 or 21 (the Android Gradle Plugin in this project is tested with those;
  JDK 25 is too new for Gradle 8.11).
- Android SDK with platform 35 and build-tools 34.0.0 or newer.
- Rust toolchain (for `cargo`).
- `mdbook` 0.5.2 and `mdbook-quiz` 0.5.0:

  ```bash
  cargo install mdbook --version 0.5.2 --locked
  cargo install mdbook-quiz --version 0.5.0 --locked
  ```

## Build

```powershell
# Windows
.\pocket\scripts\build-book.ps1
cd pocket
.\gradlew.bat :app:assembleDebug
```

```bash
# macOS / Linux
bash pocket/scripts/build-book.sh
cd pocket
bash gradlew :app:assembleDebug
```

The first command builds the mobile book bundle and copies it to
`pocket/app/src/main/assets/book/`. The APK is written to
`pocket/app/build/outputs/apk/debug/app-debug.apk`: around 17 MB with the book
inside, or around 242 MB when the optional toolchain archive is present.

If you open the project in Android Studio, run the book script first (or the
app will show a "book bundle not found" screen).

## Release builds

Tag a commit with `pocket-v*` and push the tag. The
[`pocket-release`](../.github/workflows/pocket-release.yml) workflow builds the
book, runs the unit tests, assembles the APK and attaches it to a GitHub
Release.

Signing is optional. To produce a signed APK, add these repository secrets:

| Secret | Value |
|---|---|
| `POCKET_KEYSTORE_BASE64` | base64-encoded keystore |
| `POCKET_KEYSTORE_PASSWORD` | keystore password |
| `POCKET_KEY_ALIAS` | key alias |
| `POCKET_KEY_PASSWORD` | key password |

Without them the release contains an unsigned `app-release-unsigned.apk`; Android
will only install it after manual signing.

The update checker reads `pocket.updateRepo` (set by the workflow to
`owner/repo`). Local builds leave it empty, which hides the functionality.

## How code execution works

`mdBook`'s frontend posts to `https://play.rust-lang.org/evaluate.json` and
prints `result` in a block under the code. `pocket.js` replaces `window.fetch`
so that, when the native bridge exists, those requests go to Kotlin instead of
the network:

```
book JS  ->  pocket.js  ->  window.RustPocket.post(id, body)
                                |
                                v
                    ExecutorRouter
                     /            \
      LocalExecutor            RemoteExecutor (OkHttp)
   (bundled toolchain)        (play.rust-lang.org)
                     \            /
                      v          v
            window.__rustPocketResolve(id, {success, stdout, stderr})
```

The bridge answers `/meta/crates` locally (so Run buttons appear offline) and
maps the legacy `evaluate.json` shape to the modern `/execute` response. It also
raises mdBook's hard-coded 6 second timeout during the bundle build.

`ExecutorRouter` picks an executor per snippet: `LOCAL` never uploads silently,
`REMOTE` always uses the network, and `AUTO` prefers the device when the
toolchain is installed and the snippet does not import a crate that only the
playground has.

One quirk is worth knowing: mdBook asks `/meta/crates` while the page loads to
decide whether to show a Run button, and that can happen before the bridge is
installed. `pocket.js` re-applies the same rule locally, so the buttons work
offline and in either script order.

Rust Playground has no SLA and no documented API contract. Failures are mapped
to readable messages (offline, rate limited, timeout, server error) instead of
crashing the page. The playground URL is configurable for self-hosting.

## Project layout

```
pocket/
  book/            alternate mdBook config, mobile CSS and the JS bridge
  scripts/         build the bundle and copy it into the app assets
  app/             Android module (Compose shell + WebView)
    src/main/java/dev/rustbook/pocket/
      book/        WebView setup, asset loading and the JS bridge
      runner/      request/result models, executors, router, toolchain layout
      data/        DataStore preferences and reading progress
      ui/          Compose settings sheet and reader screen
      update/      GitHub Releases check
  spikes/          scripts that produced the on-device toolchain
  docs/            design notes and decisions
```

## Licensing and attribution

The book and quizzes are dual-licensed MIT/Apache-2.0 by their authors:

- *The Rust Programming Language* by Steve Klabnik, Carol Nichols and Chris
  Krycho, with contributions from the Rust Community.
- Quiz content by the Cognitive Engineering Lab at Brown University.

The app is an unofficial reader. It is not affiliated with, endorsed by or
sponsored by the Rust Project, the Rust Foundation, the authors, Brown
University or No Starch Press. “Rust” and the Rust logo are trademarks of the
Rust Foundation.

This app itself is released under the MIT or Apache-2.0 license, at your option.
