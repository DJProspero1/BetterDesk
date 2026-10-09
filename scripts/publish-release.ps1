param([Parameter(Mandatory)][string]$Version, [Parameter(Mandatory)][string]$NotesFile)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Use a semantic version such as 0.6.0.' }
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$repo = 'DJProspero1/BetterDesk'
$tag = "v$Version"
$dist = Join-Path $repoRoot 'dist'
function Invoke-Gh([string[]]$Arguments) {
    $output = & gh @Arguments
    if ($LASTEXITCODE -ne 0) { throw "GitHub command failed: $($Arguments[0]) $($Arguments[1])" }
    return $output
}
& (Join-Path $PSScriptRoot 'check-agent-instructions.ps1')
$metadata = Invoke-Gh @('repo','view',$repo,'--json','isPrivate,nameWithOwner') | ConvertFrom-Json
if (-not $metadata.isPrivate -or $metadata.nameWithOwner -ne $repo) { throw 'This workflow publishes only to the authorized private BetterDesk repository.' }
if (@(& git -C $repoRoot status --porcelain).Count) { throw 'Commit source and documentation before publishing a build.' }
$revision = (& git -C $repoRoot rev-parse HEAD).Trim()
$remote = (& git -C $repoRoot ls-remote origin refs/heads/main) -split '\s+'
if ($LASTEXITCODE -ne 0 -or $remote[0] -ne $revision) { throw 'Push the verified source revision to origin/main before publishing.' }
$tagRefs = @(& git -C $repoRoot ls-remote origin "refs/tags/$tag" "refs/tags/$tag^{}")
if ($LASTEXITCODE -ne 0) { throw 'Could not verify the release tag.' }
if ($tagRefs.Count) {
    $peeled = @($tagRefs | Where-Object { $_ -match '\^\{\}$' })
    $target = if ($peeled.Count) { ($peeled[0] -split '\s+')[0] } else { ($tagRefs[0] -split '\s+')[0] }
    if ($target -ne $revision) { throw 'The release tag already names different source. Increment the version.' }
}
$files = @("BetterDesk-Windows-$Version.exe", "BetterDesk-Android-$Version-debug.apk", 'SHA256SUMS.txt', 'BUILD-INFO.txt')
$checksums = @(Get-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt'))
$info = @(Get-Content -LiteralPath (Join-Path $dist 'BUILD-INFO.txt'))
if ($info -notcontains "Source revision: $revision" -or $info -notcontains 'Working tree dirty: False') { throw 'Rebuild locally from the clean committed revision before publishing.' }
foreach ($name in $files) {
    $path = Join-Path $dist $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing release file: $name" }
    if ($name -match '\.(exe|apk)$') {
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($checksums -notcontains "$hash  $name") { throw "Release checksum mismatch: $name" }
    }
}
$notes = (Resolve-Path -LiteralPath $NotesFile).Path
$releases = @(Invoke-Gh @('api',"repos/$repo/releases?per_page=100") | ConvertFrom-Json)
$existing = $releases | Where-Object tag_name -eq $tag
if ($existing -and -not $existing.draft) { throw 'This version is already published. Increment the version for a new build.' }
if (-not $existing) {
    Invoke-Gh @('release','create',$tag,'--repo',$repo,'--draft','--target',$revision,'--title',"BetterDesk $Version",'--notes-file',$notes) | Out-Null
}
# A retry may finish an incomplete draft. Published releases are never overwritten.
$upload = @('release','upload',$tag,'--repo',$repo,'--clobber') + @($files | ForEach-Object { Join-Path $dist $_ })
Invoke-Gh $upload | Out-Null
$verify = Join-Path $repoRoot ('desktop\build\release-verification-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $verify -Force | Out-Null
Invoke-Gh @('release','download',$tag,'--repo',$repo,'--dir',$verify) | Out-Null
foreach ($name in $files) {
    $uploaded = Join-Path $verify $name
    if (-not (Test-Path -LiteralPath $uploaded -PathType Leaf) -or
        (Get-FileHash -LiteralPath $uploaded).Hash -ne (Get-FileHash -LiteralPath (Join-Path $dist $name)).Hash) { throw "Uploaded asset failed readback verification: $name" }
}
$draft = Invoke-Gh @('api',"repos/$repo/releases/tags/$tag") | ConvertFrom-Json
if (-not $draft.draft -or @($draft.assets).Count -ne $files.Count -or
    @($draft.assets | Where-Object { $_.name -notin $files -or $_.state -ne 'uploaded' }).Count) { throw 'Draft assets are incomplete/unexpected; old releases have been retained.' }
Invoke-Gh @('release','edit',$tag,'--repo',$repo,'--draft=false','--prerelease=false','--latest') | Out-Null
$published = Invoke-Gh @('api',"repos/$repo/releases/tags/$tag") | ConvertFrom-Json
if ($published.draft -or @($published.assets).Count -ne $files.Count -or
    @($published.assets | Where-Object { $_.name -notin $files -or $_.state -ne 'uploaded' }).Count) { throw 'New release was not fully published; old releases have been retained.' }
# Only prior versioned app releases, never unrelated drafts or Git source tags.
$releases = @(Invoke-Gh @('api',"repos/$repo/releases?per_page=100") | ConvertFrom-Json)
foreach ($old in $releases | Where-Object { -not $_.draft -and $_.tag_name -match '^v\d+\.\d+\.\d+$' -and [version]($_.tag_name.Substring(1)) -lt [version]$Version }) {
    Invoke-Gh @('release','delete',$old.tag_name,'--repo',$repo,'--yes') | Out-Null
}
Write-Output $published.html_url
