#!/usr/bin/env pwsh
#
# Builds the mobile-oriented mdBook bundle and copies it into the Android
# module's assets so the app can serve it offline through WebViewAssetLoader.
#
# Usage: pocket/scripts/build-book.ps1 [-Clean]
[CmdletBinding()]
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Push-Location $repoRoot
try {
    $dest = 'pocket/app/src/main/assets/book'

    foreach ($tool in @('cargo', 'mdbook', 'mdbook-quiz')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "'$tool' is not on PATH. Install with: cargo install mdbook --version 0.5.2 --locked && cargo install mdbook-quiz --version 0.5.0 --locked"
        }
    }

    if ($Clean) {
        Remove-Item -Recurse -Force 'pocket/build/book', $dest -ErrorAction SilentlyContinue
    }

    Write-Host '==> Building book (pocket/book/book.toml)'
    & mdbook build pocket/book
    if ($LASTEXITCODE -ne 0) { throw "mdbook build failed with exit code $LASTEXITCODE" }

    # mdBook gives the playground call a hard 6 s timeout. A cold Rust
    # Playground container often needs longer, and the native bridge adds a
    # round trip, so raise it before the bundle is packaged.
    Write-Host '==> Patching the playground timeout'
    $patched = 0
    Get-ChildItem 'pocket/build/book' -Filter 'book-*.js' | ForEach-Object {
        $content = [System.IO.File]::ReadAllText($_.FullName)
        if ($content.Contains('timeout = 6000')) {
            $content = $content.Replace('timeout = 6000', 'timeout = 180000')
            $utf8 = New-Object System.Text.UTF8Encoding($false)
            [System.IO.File]::WriteAllText($_.FullName, $content, $utf8)
            $patched++
        }
    }
    if ($patched -eq 0) {
        Write-Warning 'Could not find mdBook''s 6 s playground timeout. Check the generated bundle before shipping.'
    }

    Write-Host "==> Copying bundle into $dest"
    Remove-Item -Recurse -Force $dest -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -Recurse -Force 'pocket/build/book/*' $dest

    if (-not (Test-Path (Join-Path $dest 'index.html'))) {
        throw 'The bundle has no index.html'
    }

    $pages = (Get-ChildItem -Recurse -Filter '*.html' $dest | Measure-Object).Count
    $sizeMb = [math]::Round(((Get-ChildItem -Recurse -File $dest | Measure-Object Length -Sum).Sum / 1MB), 1)
    Write-Host "==> Bundle ready: $pages HTML pages, $sizeMb MB"
}
finally {
    Pop-Location
}
