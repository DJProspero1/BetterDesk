param([string]$WorkRoot = (Join-Path $env:LOCALAPPDATA 'BetterDesk'), [switch]$SkipTests, [switch]$LowResource)
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
    $resourceArguments = @()
    if ($LowResource) { $resourceArguments = @('--no-parallel', '--max-workers=1', '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8', '-Pkotlin.compiler.execution.strategy=in-process') }
    & (Join-Path $aliasPath 'gradlew.bat') -p $aliasPath @tasks --no-daemon '-Pkotlin.incremental=false' @resourceArguments
    if ($LASTEXITCODE -ne 0) { throw "Local build failed with exit code $LASTEXITCODE" }
    $version = '0.7.1'
    $dist = Join-Path $repoRoot ('desktop\build\distribution-stage-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot 'android\build\outputs\apk\debug\android-debug.apk') -Destination (Join-Path $dist "BetterDesk-Android-$version-debug.apk") -Force
    & (Join-Path $PSScriptRoot 'package-portable.ps1') -Version $version -OutputDirectory $dist
    $artifacts = @("BetterDesk-Windows-$version.exe", "BetterDesk-Android-$version-debug.apk")
    $hashes = foreach ($artifact in $artifacts) {
        $hash = Get-FileHash -LiteralPath (Join-Path $dist $artifact) -Algorithm SHA256
        "$($hash.Hash.ToLowerInvariant())  $artifact"
    }
    Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS.txt') -Value $hashes -Encoding utf8
    @(
        "BetterDesk $version, local development build",
        "Built locally: $([DateTime]::UtcNow.ToString('u'))",
        "Source revision: $(& git -C $repoRoot rev-parse HEAD)",
        "Working tree dirty: $([bool](& git -C $repoRoot status --porcelain))",
        $(if ($SkipTests) { 'Tests: skipped (-SkipTests); compilation and packaging are not runtime validation.' } else { 'Tests: ordinary desktop suite executed by this build; native/device checks are separately opt-in.' }),
        'Windows: unsigned portable x64 EXE, automatic private runtime cache, no installer.',
        'Android: APK signed with the local development key; install as an update.',
        'Update both devices. Direct LAN or BetterDesk Railway relay. Built locally; distribution through private GitHub Releases.'
    ) | Set-Content -LiteralPath (Join-Path $dist 'BUILD-INFO.txt') -Encoding utf8
    & (Join-Path $PSScriptRoot 'publish-distribution.ps1') -Staging $dist -Version $version
    $hashes
} finally {
    $env:JAVA_TOOL_OPTIONS = $priorJavaOptions
    $env:BETTERDESK_BROKER_FIXTURE = $priorBrokerFixture
}
