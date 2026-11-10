# Extracts the Termux .deb files needed to run rustc and link binaries on
# Android, and merges them into the staging tree.
#
# Termux's rustc depends on: clang, libandroid-execinfo, libc++, libllvm, lld,
# openssl, zlib and rust-std. Windows' tar cannot recreate the packages'
# symlinks (rust-lld -> lld, lib/librustc_driver.so -> rustlib/...), so they
# are rebuilt here as real files, which behave identically for our purposes.
param(
    [string]$DebDir = "$env:TEMP\termux-rust",
    [string]$Stage = "$env:TEMP\termux-rust\stage"
)

$ErrorActionPreference = 'Stop'

$prefixInDeb = 'data/data/com.termux/files/usr'
$debs = @(
    'rust_1.99.1',
    'rust-std-aarch64-linux-android_1.99.1',
    'libllvm_21.1.8-3',
    'lld_21.1.8-3',
    'ndk-sysroot_30',
    'libc++_30',
    'clang_21.1.8-3',
    'libcompiler-rt_21.1.8-3',
    'libandroid-execinfo_0.1-3'
)

foreach ($name in $debs) {
    $deb = Join-Path $DebDir "$name`_aarch64.deb"
    if (-not (Test-Path $deb)) {
        Write-Warning "missing $deb"
        continue
    }

    $work = Join-Path $DebDir "unpack\$name"
    if (-not (Test-Path $work)) {
        New-Item -ItemType Directory -Force -Path $work | Out-Null
        Push-Location $work
        try {
            # Windows' tar reports one error per symlink it cannot create, and
            # PowerShell 5.1 turns native stderr into terminating errors while
            # $ErrorActionPreference is 'Stop'.
            $previous = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            tar -xf $deb 2>$null
            if (Test-Path 'data.tar.xz') { tar -xf 'data.tar.xz' 2>$null }
            elseif (Test-Path 'data.tar.gz') { tar -xf 'data.tar.gz' 2>$null }
            $ErrorActionPreference = $previous
        }
        finally { Pop-Location }
    }

    $prefix = Join-Path $work 'data\data\com.termux\files\usr'
    if (-not (Test-Path $prefix)) {
        Write-Warning "no payload in $name"
        continue
    }

    # Recreate symlinks as copies. Tar on Windows fails per link but keeps
    # extracting, so every target already exists by the time we get here.
    $archive = if (Test-Path (Join-Path $work 'data.tar.xz')) { 'data.tar.xz' } else { 'data.tar.gz' }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $listing = & tar -tvf (Join-Path $work $archive) 2>$null
    $ErrorActionPreference = $previous
    foreach ($line in $listing) {
        if ($line -notmatch '^l\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+\S+\s+(\S+)\s+->\s+(\S+)$') {
            continue
        }

        $linkPath = $Matches[1] -replace '^\./', ''
        $linkTarget = $Matches[2]
        if (-not $linkPath.StartsWith($prefixInDeb)) { continue }

        $linkRel = $linkPath.Substring($prefixInDeb.Length).TrimStart('/')
        if ($linkTarget.StartsWith($prefixInDeb)) {
            $targetRel = $linkTarget.Substring($prefixInDeb.Length).TrimStart('/')
        } elseif ($linkTarget.StartsWith('/')) {
            continue
        } else {
            $parts = $linkRel -split '/'
            $parent = if ($parts.Length -gt 1) { ($parts[0..($parts.Length - 2)] -join '/') } else { '' }
            $targetRel = if ($parent) { "$parent/$linkTarget" } else { $linkTarget }
        }

        $source = Join-Path $prefix $targetRel.Replace('/', '\')
        $dest = Join-Path $prefix $linkRel.Replace('/', '\')
        if (-not (Test-Path -LiteralPath $source)) { continue }

        $destDir = Split-Path $dest -Parent
        if (-not (Test-Path -LiteralPath $destDir)) {
            New-Item -ItemType Directory -Force -Path $destDir | Out-Null
        }
        Copy-Item -LiteralPath $source -Destination $dest -Force
    }

    # Copy bin/ and lib/ payloads into the stage, skipping duplicates.
    foreach ($sub in @('bin', 'lib')) {
        $from = Join-Path $prefix $sub
        if (-not (Test-Path $from)) { continue }
        $to = Join-Path $Stage $sub
        New-Item -ItemType Directory -Force -Path $to | Out-Null

        Get-ChildItem $from -Recurse -File | ForEach-Object {
            $relative = $_.FullName.Substring($from.Length + 1)
            $target = Join-Path $to $relative
            $targetDir = Split-Path $target -Parent
            if (-not (Test-Path $targetDir)) {
                New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
            }
            Copy-Item $_.FullName $target -Force
        }
    }
}

# The Android C runtime pieces the linker needs come from ndk-sysroot and are
# copied by stage-toolchain.ps1 already; re-run it afterwards if lib/ looks thin.

foreach ($lib in @('libandroid-support.so', 'libssl.so.3', 'libcrypto.so.3', 'libz.so.1.4')) {
    $src = Join-Path $env:TEMP "termux-rust\bootstrap\lib\$lib"
    if (Test-Path $src) { Copy-Item $src (Join-Path $Stage "lib\$lib") -Force }
}

$bytes = (Get-ChildItem $Stage -Recurse -File | Measure-Object Length -Sum).Sum
Write-Output ("Stage now {0:N1} MB" -f ($bytes / 1MB))

foreach ($needed in @(
        'bin\rustc',
        'bin\rust-lld',
        'lib\libLLVM.so',
        'lib\libc++_shared.so',
        'lib\libandroid-execinfo.so',
        'lib\rustlib\aarch64-linux-android\lib\librustc_driver-d4b3d460e526cc9c.so')) {
    $path = Join-Path $Stage $needed
    $state = if (Test-Path -LiteralPath $path) { 'ok' } else { 'MISSING' }
    Write-Output ("  {0,-62} {1}" -f $needed, $state)
}
Write-Output ("Stage now {0:N1} MB" -f ($bytes / 1MB))
