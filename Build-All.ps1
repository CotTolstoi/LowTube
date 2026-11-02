$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Install .NET Framework 4.8 (Windows x64).' }
& (Join-Path $root 'Build.ps1')
$common = @('/nologo','/target:exe','/platform:x64','/optimize+','/codepage:65001','/reference:System.dll','/reference:System.Core.dll','/reference:System.Xml.dll','/reference:System.Security.dll','/reference:System.Drawing.dll','/reference:System.Windows.Forms.dll',('/reference:' + (Join-Path $root 'LowTube.exe')))
function Compile-Tool([string]$output, [string[]]$sources, [string[]]$extra) {
    $arguments = $common + @(('/out:' + (Join-Path $root $output))) + $extra + $sources
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed: $output" }
    Write-Host "Built $output"
}
$benchmark = @(Get-ChildItem -LiteralPath (Join-Path $root 'benchmark') -Filter '*.cs' | Sort-Object Name | ForEach-Object { $_.FullName })
Compile-Tool -output 'LowTube.Benchmark.exe' -sources $benchmark -extra @('/reference:System.Management.dll')
Compile-Tool -output 'LowTube.Hybrid.exe' -sources @((Join-Path $root 'hybrid\Hybrid.cs')) -extra @()
Compile-Tool -output 'LowTube.Hybrid.Compare.exe' -sources @((Join-Path $root 'hybrid\Compare.cs')) -extra @()
Write-Host 'All four executables built. External tools are needed only for runtime playback/extraction.'
