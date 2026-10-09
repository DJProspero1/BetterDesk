param([string]$WorkRoot = (Join-Path $env:LOCALAPPDATA 'BetterDesk'), [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'check-agent-instructions.ps1')
if ($WorkRoot -match '[^\x00-\x7F]') { throw 'Choose an ASCII-only -WorkRoot path for the JDK packaging tools.' }
New-Item -ItemType Directory -Path $WorkRoot -Force | Out-Null
$aliasPath = Join-Path $WorkRoot 'workspace'
if (Test-Path -LiteralPath $aliasPath) {
    $item = Get-Item -LiteralPath $aliasPath
    if ($item.LinkType -ne 'Junction' -or $item.Target -ne $repoRoot) { throw "The existing build alias points elsewhere: $aliasPath" }
} else {
    New-Item -ItemType Junction -Path $aliasPath -Target $repoRoot | Out-Null
}
$priorJavaOptions = $env:JAVA_TOOL_OPTIONS
$priorBrokerFixture = $env:BETTERDESK_BROKER_FIXTURE
try {
    $env:JAVA_TOOL_OPTIONS = (($priorJavaOptions, '-Dfile.encoding=UTF-8') -join ' ').Trim()
    $brokerFixture = Join-Path (Split-Path $repoRoot -Parent) 'BetterDesk-Infrastructure\scripts\broker-fixture.mjs'
    if (-not $env:BETTERDESK_BROKER_FIXTURE -and (Test-Path -LiteralPath $brokerFixture)) { $env:BETTERDESK_BROKER_FIXTURE = $brokerFixture }
    $tasks = @(':desktop:createDistributable', ':android:assembleDebug')
    if (-not $SkipTests) { $tasks = @(':desktop:test') + $tasks }
    & (Join-Path $aliasPath 'gradlew.bat') -p $aliasPath @tasks --no-daemon '-Pkotlin.incremental=false'
    if ($LASTEXITCODE -ne 0) { throw "Local build failed with exit code $LASTEXITCODE" }
    $dist = Join-Path $repoRoot 'dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot 'android\build\outputs\apk\debug\android-debug.apk') -Destination (Join-Path $dist 'BetterDesk-Android-0.5.0-debug.apk') -Force
    & (Join-Path $PSScriptRoot 'package-portable.ps1') -Version '0.5.0'
    $artifacts = @('BetterDesk-Windows-0.5.0.exe', 'BetterDesk-Android-0.5.0-debug.apk')
    $hashes = foreach ($artifact in $artifacts) {
        $hash = Get-FileHash -LiteralPath (Join-Path $dist $artifact) -Algorithm SHA256
        "$($hash.Hash.ToLowerInvariant())  $artifact"
    }
    Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Value $hashes -Encoding utf8
    @(
        'BetterDesk 0.5.0, local development build',
        "Built locally: $([DateTime]::UtcNow.ToString('u'))",
        'Windows: unsigned portable x64 EXE, automatic private runtime cache, no installer.',
        'Android: APK signed with the local development key; install as an update.',
        'Update both devices. Direct LAN or BetterDesk Railway relay. No cloud builds or public downloads.'
    ) | Set-Content -LiteralPath (Join-Path $dist 'BUILD-INFO.txt') -Encoding utf8
    $hashes
} finally {
    $env:JAVA_TOOL_OPTIONS = $priorJavaOptions
    $env:BETTERDESK_BROKER_FIXTURE = $priorBrokerFixture
}
