# Creates a minimal Termux rust toolchain tree for the Android spike.
#
# The Termux `rustc` is a thin launcher that dlopens `librustc_driver-*.so`
# from the sibling `lib/rustlib/<target>/lib` directory, so the layout has to
# keep `bin/` and `lib/` together. Any prefix works.
param(
    [string]$Source = "$env:TEMP\termux-rust\sysroot\data\data\com.termux\files\usr",
    [string]$Dest = "$env:TEMP\termux-rust\stage"
)

$ErrorActionPreference = 'Stop'

if (Test-Path $Dest) { Remove-Item -Recurse -Force $Dest }
New-Item -ItemType Directory -Force -Path "$Dest\bin", "$Dest\lib\rustlib" | Out-Null

Copy-Item "$Source\bin\rustc" "$Dest\bin\rustc"

# Target libraries: std, core, alloc, the driver and proc-macro helpers.
New-Item -ItemType Directory -Force -Path "$Dest\lib\rustlib\aarch64-linux-android" | Out-Null
Copy-Item -Recurse "$Source\lib\rustlib\aarch64-linux-android\lib" "$Dest\lib\rustlib\aarch64-linux-android\lib"
Copy-Item -Recurse "$Source\lib\rustlib\aarch64-linux-android\bin" "$Dest\lib\rustlib\aarch64-linux-android\bin"

# Shared libraries the driver and the linker need at run time.
foreach ($lib in @('libLLVM.so', 'libLTO.so', 'libRemarks.so', 'libpthread.so', 'librt.so', 'libutil.so')) {
    $path = Join-Path "$Source\lib" $lib
    if (Test-Path $path) { Copy-Item $path "$Dest\lib\$lib" }
}

# Objects and archives used when linking a binary.
Get-ChildItem "$Source\lib" -File | Where-Object { $_.Extension -in @('.a', '.o') } |
    ForEach-Object { Copy-Item $_.FullName "$Dest\lib\$($_.Name)" }

# The sysroot expects a `lib/rustlib/etc` for some diagnostics; optional.
if (Test-Path "$Source\lib\rustlib\etc") {
    Copy-Item -Recurse "$Source\lib\rustlib\etc" "$Dest\lib\rustlib\etc"
}

$bytes = (Get-ChildItem $Dest -Recurse -File | Measure-Object Length -Sum).Sum
Write-Output ("Staged {0:N1} MB at {1}" -f ($bytes / 1MB), $Dest)

Get-ChildItem $Dest -Recurse -File |
    Group-Object { $_.FullName.Substring($Dest.Length + 1).Split('\')[0] } |
    ForEach-Object {
        $sum = ($_.Group | Measure-Object Length -Sum).Sum
        Write-Output ("  {0,-8} {1,8:N1} MB" -f $_.Name, ($sum / 1MB))
    }
