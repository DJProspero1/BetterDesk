$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$testRoot = Join-Path $repoRoot ('desktop\build\release-script-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $testRoot 'scripts'), (Join-Path $testRoot 'desktop\build'), (Join-Path $testRoot 'dist') -Force | Out-Null
foreach ($name in @('publish-distribution.ps1','publish-release.ps1','check-agent-instructions.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $testRoot "scripts\$name")
}
foreach ($name in @('AGENTS.md','CLAUDE.md','.claude.md')) { Set-Content -LiteralPath (Join-Path $testRoot $name) -Value 'fixture rules' }
function Assert([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Expect-Failure([scriptblock]$Action) {
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    Assert $failed 'Expected the unsafe/incomplete operation to fail.'
}
$stage = Join-Path $testRoot 'desktop\build\distribution-stage-test'
New-Item -ItemType Directory -Path $stage | Out-Null
$names = @('BetterDesk-Windows-0.6.0.exe','BetterDesk-Android-0.6.0-debug.apk')
foreach ($name in $names) { Set-Content -LiteralPath (Join-Path $stage $name) -Value "fixture $name" }
$sums = $names | ForEach-Object { "$((Get-FileHash -LiteralPath (Join-Path $stage $_)).Hash.ToLowerInvariant())  $_" }
Set-Content -LiteralPath (Join-Path $stage 'SHA256SUMS.txt') -Value 'bad checksum'
Set-Content -LiteralPath (Join-Path $stage 'BUILD-INFO.txt') -Value @('Source revision: fixture-revision','Working tree dirty: False')
Set-Content -LiteralPath (Join-Path $testRoot 'dist\BetterDesk-Windows-0.5.0.exe') -Value 'old build'
Set-Content -LiteralPath (Join-Path $testRoot 'dist\notes.txt') -Value 'keep this note'
Expect-Failure { & "$testRoot\scripts\publish-distribution.ps1" -Staging $stage -Version 0.6.0 }
Assert (Test-Path -LiteralPath "$testRoot\dist\BetterDesk-Windows-0.5.0.exe") 'Invalid build removed the previous distribution.'
Set-Content -LiteralPath (Join-Path $stage 'SHA256SUMS.txt') -Value $sums
& "$testRoot\scripts\publish-distribution.ps1" -Staging $stage -Version 0.6.0 | Out-Null
Assert (@(Get-ChildItem -LiteralPath "$testRoot\dist").Count -eq 4) 'Distribution was not cleaned.'
$archives = @(Get-ChildItem -LiteralPath "$testRoot\desktop\build" -Directory -Filter 'distribution-previous-*')
Assert ($archives.Count -eq 1 -and (Get-Content -LiteralPath (Join-Path $archives[0].FullName 'notes.txt')) -eq 'keep this note') 'Unrelated notes were not preserved.'
Assert (-not (Test-Path -LiteralPath (Join-Path $archives[0].FullName 'BetterDesk-Windows-0.5.0.exe'))) 'Old binary remains.'
$link = Join-Path $testRoot 'desktop\build\distribution-stage-link'
New-Item -ItemType Junction -Path $link -Target (Join-Path $testRoot 'dist') | Out-Null
Expect-Failure { & "$testRoot\scripts\publish-distribution.ps1" -Staging $link -Version 0.6.0 }
Assert (@(Get-ChildItem -LiteralPath "$testRoot\dist").Count -eq 4) 'Rejected junction changed the distribution.'

# Offline Git/GitHub doubles: exercise failed uploads and publication-before-deletion
# without making any repository, account or network mutations.
& {
    $ctx = @{ root=$testRoot; failReadback=$true; events=[Collections.Generic.List[string]]::new(); releases=[Collections.Generic.List[object]]::new() }
    $ctx.releases.Add(@{tag_name='v0.5.0';draft=$false})
    Set-Content -LiteralPath "$testRoot\notes.md" -Value 'fixture notes'
    function git { $global:LASTEXITCODE=0; if ($args -contains 'rev-parse') { 'fixture-revision' } elseif ($args -contains 'refs/heads/main') { 'fixture-revision refs/heads/main' } }
    function gh {
        $global:LASTEXITCODE=0
        if ($args[0] -eq 'repo') { return '{"isPrivate":true,"nameWithOwner":"DJProspero1/BetterDesk"}' }
        if ($args[0] -eq 'api') {
            if ($args[1] -like '*/releases/600') { return ($ctx.releases | Where-Object tag_name -eq 'v0.6.0' | ConvertTo-Json -Depth 5) }
            return ConvertTo-Json -InputObject @($ctx.releases) -Depth 5
        }
        $operation=$args[1]
        if ($operation -eq 'edit' -and $args -contains '--target') { return }
        $ctx.events.Add($operation)
        switch ($operation) {
            'create' { $ctx.releases.Add(@{id=600;tag_name='v0.6.0';draft=$true;html_url='https://example.invalid/fixture';assets=@()}) }
            'upload' {
                $release=$ctx.releases | Where-Object tag_name -eq 'v0.6.0'
                $release.assets=@(Get-ChildItem -LiteralPath "$($ctx.root)\dist" -File | ForEach-Object { @{name=$_.Name;state='uploaded'} })
            }
            'download' {
                if ($ctx.failReadback) { $global:LASTEXITCODE=1; return }
                $destination=$args[([array]::IndexOf($args,'--dir')+1)]
                Get-ChildItem -LiteralPath "$($ctx.root)\dist" -File | Copy-Item -Destination $destination
            }
            'edit' {
                $release=$ctx.releases | Where-Object tag_name -eq 'v0.6.0'
                $release.draft=$false
                $release.assets=@(Get-ChildItem -LiteralPath "$($ctx.root)\dist" -File | ForEach-Object { @{name=$_.Name;state='uploaded'} })
            }
            'delete' { }
            default { throw 'Unexpected fixture GitHub call.' }
        }
    }
    Expect-Failure { & "$testRoot\scripts\publish-release.ps1" -Version 0.6.0 -NotesFile "$testRoot\notes.md" }
    Assert (-not $ctx.events.Contains('delete') -and -not $ctx.events.Contains('edit')) 'Failed readback removed or published a release.'
    $ctx.failReadback=$false; $ctx.events.Clear()
    & "$testRoot\scripts\publish-release.ps1" -Version 0.6.0 -NotesFile "$testRoot\notes.md" | Out-Null
    Assert ($ctx.events.IndexOf('download') -lt $ctx.events.IndexOf('edit') -and $ctx.events.IndexOf('edit') -lt $ctx.events.IndexOf('delete')) 'Old release deleted before new asset verification/publication.'
    Assert (-not $ctx.events.Contains('create')) 'Retry failed to reuse the draft.'
}
Write-Output 'Passed: checksum failure preserves old distribution; clean replacement preserves unrelated notes; junction rejected; failed readback retains old release; retry verifies/publishes before deleting old release.'
Write-Output "Isolated fixture evidence retained in $testRoot"
