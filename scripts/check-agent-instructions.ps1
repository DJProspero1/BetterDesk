param([switch]$Sync)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$source = Join-Path $repoRoot 'AGENTS.md'
$mirrors = @('CLAUDE.md', '.claude.md')
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw 'AGENTS.md is missing.' }
if ($Sync) {
    $bytes = [IO.File]::ReadAllBytes($source)
    foreach ($name in $mirrors) { [IO.File]::WriteAllBytes((Join-Path $repoRoot $name), $bytes) }
}
$expected = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
foreach ($name in $mirrors) {
    $path = Join-Path $repoRoot $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expected) {
        throw "Agent instructions differ: $name. Edit AGENTS.md, then run scripts/check-agent-instructions.ps1 -Sync and commit all three copies."
    }
}
Write-Output 'Agent instructions match: AGENTS.md, CLAUDE.md, .claude.md.'
