# Rust Book Pocket

An Android reader for *The Rust Programming Language* that keeps the book's
interactive quizzes and can compile and run the code samples on the device,
so you can study from a phone without a laptop.

The app lives in [`pocket/`](pocket/README.md). This repository holds the book
sources it renders, so the content and the reader evolve together.

## What this repository contains

| Path | What it is |
|---|---|
| `src/` | The book's chapters, in Markdown |
| `quizzes/` | 93 quiz files (222 questions) embedded in those chapters |
| `listings/` | The standalone Cargo projects the chapters quote |
| `words.dic` | Dictionary used when validating quiz text |
| `packages/mdbook-trpl/` | The mdBook preprocessors for notes and listings |
| `packages/trpl/` | The async helper crate used by chapter 17's examples |
| `pocket/` | The Android app, its mdBook config and the toolchain scripts |

## Building the app

```bash
cargo install mdbook --version 0.5.2 --locked
cargo install mdbook-quiz --version 0.5.0 --locked

bash pocket/scripts/build-book.sh          # Windows: pocket/scripts/build-book.ps1
cd pocket && bash gradlew :app:assembleDebug
```

The APK lands in `pocket/app/build/outputs/apk/debug/app-debug.apk`. See
[`pocket/README.md`](pocket/README.md) for the two build flavours (with and
without the on-device Rust toolchain), how code execution works, and the
`targetSdk = 28` trade-off that makes on-device compilation possible.

## What was removed

This started as a fork of the interactive edition maintained by the Cognitive
Engineering Lab at Brown University. The print pipeline, the three older
editions, the browser extensions and the upstream automation were removed
because the app is the only way this copy of the book is consumed:

- `first-edition/`, `second-edition/`, `2018-edition/`
- `nostarch/`, `redirects/`, `dot/`
- `js-extensions/`, `ci/`, `tools/`, `packages/tools/`
- `packages/mdbook-trpl-listing/`, `packages/mdbook-trpl-note/`
- the root `book.toml`, `ferris.css`, `ferris.js` and `theme/`
  (the Ferris icons now live in `pocket/book/`)

Upstream remains at
[`cognitive-engineering-lab/rust-book`](https://github.com/cognitive-engineering-lab/rust-book);
the book itself is maintained by the Rust Project at
[`rust-lang/book`](https://github.com/rust-lang/book).

## Licensing and attribution

The book and quizzes are dual-licensed MIT/Apache-2.0:

- *The Rust Programming Language* by Steve Klabnik, Carol Nichols and Chris
  Krycho, with contributions from the Rust Community.
- Quiz content by the Cognitive Engineering Lab at Brown University.

The app is an unofficial reader. It is not affiliated with, endorsed by or
sponsored by the Rust Project, the Rust Foundation, the authors, Brown
University or No Starch Press. "Rust" and the Rust logo are trademarks of the
Rust Foundation.

The app itself is released under the MIT or Apache-2.0 license, at your option.
