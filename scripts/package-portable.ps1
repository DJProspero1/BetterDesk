param([string]$Version = '0.3.0')
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$app = Join-Path $repoRoot 'desktop\build\compose\binaries\main\app\BetterDesk'
if (-not (Test-Path -LiteralPath (Join-Path $app 'BetterDesk.exe'))) { throw 'Build the Windows app first.' }
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Windows .NET Framework compiler is missing.' }
$temporary = Join-Path $repoRoot 'desktop\build\portable'
$dist = Join-Path $repoRoot 'dist'
New-Item -ItemType Directory -Path $temporary,$dist -Force | Out-Null
# A unique intermediate avoids deleting/replacing a payload used by another build.
$payload = Join-Path $temporary (([guid]::NewGuid().ToString('N')) + '.zip')
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($app, $payload, [IO.Compression.CompressionLevel]::Optimal, $false)
$output = Join-Path $dist "BetterDesk-Windows-$Version.exe"
$source = Join-Path $repoRoot 'desktop\launcher\Portable.cs'
& $compiler /nologo /target:winexe /platform:x64 /optimize+ /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.IO.Compression.dll /r:System.IO.Compression.FileSystem.dll "/resource:$payload,BetterDesk.Payload" "/out:$output" $source
if ($LASTEXITCODE -ne 0) { throw 'Portable launcher compilation failed.' }
Get-Item -LiteralPath $output | Select-Object Name,Length,FullName
