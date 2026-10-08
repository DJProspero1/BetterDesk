param([string]$WorkRoot = (Join-Path $env:LOCALAPPDATA 'BetterDesk'), [switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
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
try {
    $env:JAVA_TOOL_OPTIONS = (($priorJavaOptions, '-Dfile.encoding=UTF-8') -join ' ').Trim()
    $tasks = @(':desktop:createDistributable', ':android:assembleDebug')
    if (-not $SkipTests) { $tasks = @(':desktop:test') + $tasks }
    & (Join-Path $aliasPath 'gradlew.bat') -p $aliasPath @tasks --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Local build failed with exit code $LASTEXITCODE" }
    $dist = Join-Path $repoRoot 'dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoRoot 'android\build\outputs\apk\debug\android-debug.apk') -Destination (Join-Path $dist 'BetterDesk-Android-0.1.0-debug.apk') -Force
    Compress-Archive -LiteralPath (Join-Path $repoRoot 'desktop\build\compose\binaries\main\app\BetterDesk') -DestinationPath (Join-Path $dist 'BetterDesk-Windows-0.1.0.zip') -Force
    Get-ChildItem -LiteralPath $dist -File | Get-FileHash -Algorithm SHA256 | Format-Table Hash,Path -AutoSize
} finally {
    $env:JAVA_TOOL_OPTIONS = $priorJavaOptions
}
