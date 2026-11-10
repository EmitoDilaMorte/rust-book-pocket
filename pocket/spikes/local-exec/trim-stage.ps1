# Trims the staging tree down to what a phone actually needs to compile and
# link std-only snippets for aarch64-linux-android.
#
# Removes:
#   - rustlib for targets the app never builds for,
#   - `.rmeta` files (only used by `cargo check`, not by codegen),
#   - editor/tidy-up tools that the reader never invokes from the app.
param(
    [string]$Stage = "$env:TEMP\termux-rust\stage"
)

$ErrorActionPreference = 'Stop'

$before = (Get-ChildItem $Stage -Recurse -File | Measure-Object Length -Sum).Sum

# Other architectures.
Get-ChildItem "$Stage\lib\rustlib" -Directory |
    Where-Object { $_.Name -notin @('aarch64-linux-android', 'etc') } |
    ForEach-Object { Remove-Item -Recurse -Force $_.FullName }

# Metadata-only artifacts. Codegen needs .rlib and .so, not .rmeta.
Get-ChildItem "$Stage\lib\rustlib\aarch64-linux-android\lib" -Filter '*.rmeta' -File |
    ForEach-Object { Remove-Item -Force $_.FullName }

# Front-end tools and docs bundled by the clang and rust packages.
$drop = @(
    'clangd', 'clang-tidy', 'clang-doc', 'clang-tblgen', 'diagtool', 'clang-query',
    'clang-change-namespace', 'clang-check', 'clang-apply-replacements',
    'clang-include-fixer', 'clang-reorder-fields', 'clang-refactor', 'clang-scan-deps',
    'cargo', 'cargo-clippy', 'cargo-fmt', 'cargo-fix', 'cargo-miri', 'clippy-driver',
    'rustdoc', 'rustfmt', 'rust-gdb', 'rust-gdbgui', 'rust-lldb', 'rust-gdbgui',
    'lld-link', 'ld64.lld', 'wasm-ld', 'llvm-*', 'clang-*', 'lldb*'
)
Get-ChildItem "$Stage\bin" -File | Where-Object {
    $name = $_.Name
    $drop | Where-Object { $name -like $_ } | Select-Object -First 1
} | ForEach-Object { Remove-Item -Force $_.FullName }

$after = (Get-ChildItem $Stage -Recurse -File | Measure-Object Length -Sum).Sum
Write-Output ("Trimmed {0:N0} MB -> {1:N0} MB" -f ($before / 1MB), ($after / 1MB))

Get-ChildItem "$Stage\bin" -File | Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,1)}} | Format-Table -AutoSize
