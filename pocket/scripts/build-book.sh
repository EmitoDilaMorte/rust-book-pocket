#!/usr/bin/env bash
#
# Builds the mobile-oriented mdBook bundle and copies it into the Android
# module's assets so the app can serve it offline through WebViewAssetLoader.
#
# Usage: pocket/scripts/build-book.sh [--clean]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

dest="pocket/app/src/main/assets/book"

for tool in cargo mdbook mdbook-quiz; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: '$tool' is not on PATH." >&2
    echo "       Install with: cargo install mdbook --version 0.5.2 --locked" >&2
    echo "                     cargo install mdbook-quiz --version 0.5.0 --locked" >&2
    exit 1
  fi
done

if [[ "${1:-}" == "--clean" ]]; then
  rm -rf pocket/build/book "$dest"
fi

echo "==> Building book (pocket/book/book.toml)"
mdbook build pocket/book

# mdBook gives the playground call a hard 6 s timeout. A cold Rust Playground
# container often needs longer, and the native bridge adds a round trip, so
# raise it before the bundle is packaged.
echo "==> Patching the playground timeout"
patched=0
while IFS= read -r file; do
  perl -pi -e 's/timeout = 6000/timeout = 180000/g' "$file"
  patched=$((patched + 1))
done < <(grep -rl "timeout = 6000" pocket/build/book --include="book-*.js" || true)

if [[ "$patched" -eq 0 ]]; then
  echo "warning: could not find mdBook's 6 s playground timeout; check the bundle before shipping." >&2
fi

echo "==> Copying bundle into $dest"
rm -rf "$dest"
mkdir -p "$dest"
cp -R pocket/build/book/. "$dest/"

if [[ ! -f "$dest/index.html" ]]; then
  echo "error: the bundle has no index.html" >&2
  exit 1
fi

pages=$(find "$dest" -name '*.html' | wc -l | tr -d ' ')
echo "==> Bundle ready: $pages HTML pages"
du -sh "$dest" 2>/dev/null || true
