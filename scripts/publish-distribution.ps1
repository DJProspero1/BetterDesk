param([Parameter(Mandatory)][string]$Staging, [Parameter(Mandatory)][string]$Version)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid distribution version.' }
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$buildRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'desktop\build'))
$stagePath = (Resolve-Path -LiteralPath $Staging).Path
$dist = [IO.Path]::GetFullPath((Join-Path $repoRoot 'dist'))
$separator = [IO.Path]::DirectorySeparatorChar
$comparison = if ($separator -eq '\') { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
if (-not $stagePath.StartsWith($buildRoot + $separator + 'distribution-stage-', $comparison)) { throw 'Staging must be a distribution-stage directory inside desktop/build.' }
function Assert-LocalTree([string]$Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    if (-not $resolved.StartsWith($repoRoot + $separator, $comparison)) { throw 'Distribution path escapes the checkout.' }
    $ancestor = Get-Item -LiteralPath $resolved -Force
    while ($ancestor.FullName -ne $repoRoot) {
        if ($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Distribution ancestors cannot be links or junctions.' }
        $ancestor = $ancestor.Parent
        if (-not $ancestor) { throw 'Distribution ancestor escapes the checkout.' }
    }
    $items = @(Get-Item -LiteralPath $Path -Force) + @(Get-ChildItem -LiteralPath $Path -Force -Recurse)
    foreach ($item in $items) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Distribution paths cannot contain links or junctions.' }
        if (-not $item.FullName.StartsWith($repoRoot + $separator, $comparison)) { throw 'Distribution child escapes the checkout.' }
    }
}
Assert-LocalTree $stagePath
$artifacts = @("BetterDesk-Windows-$Version.exe", "BetterDesk-Android-$Version-debug.apk")
$expected = $artifacts + @('SHA256SUMS.txt', 'BUILD-INFO.txt', 'update.json')
$items = @(Get-ChildItem -LiteralPath $stagePath -Force)
if ($items.Count -ne $expected.Count -or @($items | Where-Object { $_.PSIsContainer -or $_.Name -notin $expected }).Count) { throw 'Staged distribution is incomplete or contains unexpected files.' }
$checksums = @(Get-Content -LiteralPath (Join-Path $stagePath 'SHA256SUMS.txt'))
foreach ($artifact in $artifacts) {
    $hash = (Get-FileHash -LiteralPath (Join-Path $stagePath $artifact) -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($checksums -notcontains "$hash  $artifact") { throw "Staged checksum mismatch: $artifact" }
}
$update = Get-Content -LiteralPath (Join-Path $stagePath 'update.json') -Raw | ConvertFrom-Json
if ($update.version -ne $Version) { throw 'Update manifest names a different version.' }
foreach ($entry in @(@($update.windows, $artifacts[0]), @($update.android, $artifacts[1]))) {
    $file = Get-Item -LiteralPath (Join-Path $stagePath $entry[1])
    if ($entry[0].name -ne $entry[1] -or $entry[0].size -ne $file.Length -or
        $entry[0].sha256 -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) { throw "Update manifest mismatch: $($entry[1])" }
}
if ([long]$update.android.versionCode -lt 1) { throw 'Update manifest lacks the Android versionCode.' }
$archive = Join-Path $buildRoot ('distribution-previous-' + [guid]::NewGuid().ToString('N'))
if (Test-Path -LiteralPath $dist) {
    Assert-LocalTree $dist
    # Both resolved directories are inside this checkout, and links were rejected above.
    Move-Item -LiteralPath $dist -Destination $archive
}
try { Move-Item -LiteralPath $stagePath -Destination $dist }
catch {
    if ((Test-Path -LiteralPath $archive) -and -not (Test-Path -LiteralPath $dist)) { Move-Item -LiteralPath $archive -Destination $dist }
    throw
}
# Only remove recognized build outputs, after the complete replacement is in place.
# Preserve unrelated previews/notes outside dist instead of silently deleting them.
if (Test-Path -LiteralPath $archive) {
    Assert-LocalTree $archive
    Get-ChildItem -LiteralPath $archive -File -Force | Where-Object {
        $_.Name -match '^BetterDesk-(Windows|Android)-[0-9].*\.(exe|apk|zip)$' -or $_.Name -in @('SHA256SUMS.txt','BUILD-INFO.txt','update.json')
    } | ForEach-Object { Remove-Item -LiteralPath $_.FullName }
    if (@(Get-ChildItem -LiteralPath $archive -Force).Count -eq 0) { Remove-Item -LiteralPath $archive }
    else { Write-Output "Preserved previous non-build files in $archive" }
}
Write-Output "dist now contains only the verified $Version build and its metadata."
