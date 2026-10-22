param([switch]$WindowsMedia)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
& (Join-Path $root 'Build-All.ps1')
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$references = @('/reference:System.dll','/reference:System.Core.dll','/reference:System.Xml.dll','/reference:System.Security.dll','/reference:System.Drawing.dll','/reference:System.Windows.Forms.dll',('/reference:' + (Join-Path $root 'LowTube.exe')))
$testNames = @('CoreTests','FeatureTests','GoogleTests','ContinuityTests','InnerTubeTests','InnerTubeReliabilityTests','StreamOpenTests','PlaybackSelectionTests','BenchPlanTests')
if ($WindowsMedia) { $testNames += @('WindowsStreamOpenTests','WindowsPlayerStopTests') }
foreach ($name in $testNames) {
    $source = if ($name -eq 'BenchPlanTests') { Join-Path $root 'benchmark\tests\PlanTests.cs' } else { Join-Path $root ('tests\' + $name + '.cs') }
    $output = Join-Path $root ('Test.' + $name + '.exe')
    $arguments = @('/nologo','/target:exe','/platform:x64','/codepage:65001',('/out:' + $output)) + $references
    if ($name -eq 'BenchPlanTests') { $arguments += '/reference:' + (Join-Path $root 'LowTube.Benchmark.exe') }
    $arguments += $source
    & $compiler @arguments
    if ($LASTEXITCODE -ne 0) { throw "Test compilation failed: $name" }
    & $output
    if ($LASTEXITCODE -ne 0) { throw "Test failed: $name" }
}
$python = Get-Command python -ErrorAction SilentlyContinue
if ($python) {
    & $python.Source (Join-Path $root 'tests\validate_localization.py')
    if ($LASTEXITCODE -ne 0) { throw 'Localization audit failed.' }
    & $python.Source -c "import importlib.util,sys;sys.exit(0 if importlib.util.find_spec('lupa') else 1)"
    if ($LASTEXITCODE -eq 0) {
        & $python.Source (Join-Path $root 'benchmark\tests\test_sample.py')
        if ($LASTEXITCODE -ne 0) { throw 'Benchmark sample test failed.' }
    } else { Write-Host 'Lua sampler check skipped: install optional requirements-dev.txt dependencies.' }
} else { Write-Host 'Python checks skipped: Python 3 not found.' }
Write-Host 'Checks completed. Windows playback/GPU performance and long benchmarks are separate manual validations.'
