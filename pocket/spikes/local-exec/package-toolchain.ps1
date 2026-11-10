# Packages the staged Termux toolchain into the Android app.
#
# The app targets SDK 28, the same trade-off Termux makes, so `execve` on files
# the app wrote is still allowed. That means the toolchain can be a plain zip in
# assets, unpacked at first launch, keeping its original file names and SONAMEs.
# Nothing has to be renamed, patched, or moved into jniLibs.
#
# Output: app/src/main/assets/toolchain/toolchain.zip (~150 MB)
param(
    [string]$Stage = "$env:TEMP\termux-rust\stage",
    [string]$App = "$PSScriptRoot\..\..\app"
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Stage)) {
    throw "No staged toolchain at $Stage. Run stage-toolchain.ps1, merge-packages.ps1 and trim-stage.ps1 first."
}

$assets = Join-Path $App 'src\main\assets\toolchain'
if (Test-Path $assets) { Remove-Item -Recurse -Force $assets }
New-Item -ItemType Directory -Force -Path $assets | Out-Null

$payload = Join-Path $env:TEMP 'pocket-toolchain-payload'
if (Test-Path $payload) { Remove-Item -Recurse -Force $payload }
New-Item -ItemType Directory -Force -Path $payload | Out-Null

foreach ($item in @('bin\rustc', 'bin\clang', 'bin\lld', 'lib', 'include')) {
    $source = Join-Path $Stage $item
    if (-not (Test-Path $source)) {
        Write-Warning "missing $item"
        continue
    }
    $target = Join-Path $payload $item
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -Recurse $source $target
}

# Drop the front-end tools the app never invokes, but keep everything clang
# and rustc shell out to. clang execs `ld`, which Termux ships as a symlink
# that Windows tar cannot create, so the aliases are materialised below.
$drop = @(
    'cargo', 'cargo-clippy', 'cargo-fmt', 'cargo-fix', 'cargo-miri',
    'clippy-driver', 'rustdoc', 'rustfmt', 'rust-gdb', 'rust-gdbgui', 'rust-lldb',
    'clangd', 'clang-tidy', 'clang-doc', 'clang-tblgen', 'diagtool',
    'clang-check', 'clang-query', 'clang-format', 'clang-refactor',
    'clang-repl', 'clang-scan-deps', 'clang-linker-wrapper'
)
Get-ChildItem (Join-Path $payload 'bin') -File | Where-Object {
    $drop -contains $_.Name
} | ForEach-Object { Remove-Item -Force $_.FullName }

$binDir = Join-Path $payload 'bin'
$lldPath = Join-Path $binDir 'lld'
if (Test-Path $lldPath) {
    foreach ($alias in @('ld', 'ld.lld', 'rust-lld', 'aarch64-linux-android-ld')) {
        $target = Join-Path $binDir $alias
        if (-not (Test-Path -LiteralPath $target)) {
            Copy-Item $lldPath $target
        }
    }
}

# Termux ships versioned libraries with real symlinks (libz.so.1 -> libz.so.1.4).
# Windows tar cannot create symlinks, and the loader asks for the SONAME, so
# materialise the links as copies.
$libDir = Join-Path $payload 'lib'
Get-ChildItem $libDir -File | Where-Object { $_.Name -match '^(lib[^.]+\.so)\.(\d+)\.' } | ForEach-Object {
    $soname = "$($Matches[1]).$($Matches[2])"
    $link = Join-Path $libDir $soname
    if (-not (Test-Path -LiteralPath $link)) {
        Copy-Item $_.FullName $link
    }
}

# Same for the unversioned name when the SONAME has a major version.
Get-ChildItem $libDir -File | Where-Object { $_.Name -match '^(lib[^.]+\.so)\.(\d+)$' } | ForEach-Object {
    $plain = $Matches[1]
    $link = Join-Path $libDir $plain
    if (-not (Test-Path -LiteralPath $link)) {
        Copy-Item $_.FullName $link
    }
}

$payloadMb = (Get-ChildItem $payload -Recurse -File | Measure-Object Length -Sum).Sum / 1MB

$zip = Join-Path $assets 'toolchain.zip'
if (Test-Path $zip) { Remove-Item $zip }

Write-Host ("==> Zipping {0:N0} MB" -f $payloadMb)
Push-Location $payload
try {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    # `-a` makes bsdtar pick the format from the extension; a plain zip is what
    # java.util.zip.ZipInputStream expects.
    tar -a -cf $zip *
    $ErrorActionPreference = $previous
}
finally { Pop-Location }

if (-not (Test-Path $zip)) { throw 'tar failed to create the archive' }

Write-Output ("toolchain.zip: {0:N0} MB" -f ((Get-Item $zip).Length / 1MB))
Write-Output ("Unpacked size on device: about {0:N0} MB" -f $payloadMb)
Write-Output ''
Write-Output 'Bump LocalToolchain.VERSION whenever you repackage the toolchain.'
