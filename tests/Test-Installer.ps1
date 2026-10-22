param([string]$PackageZip)
$ErrorActionPreference = 'Stop'
$setup = Join-Path (Split-Path -Parent $PSScriptRoot) 'Setup.ps1'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($setup, [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw ($errors | Out-String) }
# Load only these pure local helpers; never run the installer's network/main block.
foreach ($name in @('Asset','FindOne','FfmpegBin')) {
    $function = $ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $false) | Where-Object Name -eq $name
    . ([scriptblock]::Create($function.Extent.Text))
}
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('lowtube-installer-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
function Assert([bool]$ok, [string]$description) {
    if (-not $ok) { throw "FAIL: $description" }
    Write-Host "PASS: $description"
}
try {
    $fixture = Join-Path $tempRoot 'fixture'
    $bin = Join-Path $fixture 'package/bin'
    New-Item -ItemType Directory -Path $bin -Force | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $bin 'ffmpeg.exe'), [byte[]]@())
    $rejected = $false
    try { FfmpegBin $fixture | Out-Null } catch { $rejected = $_.Exception.Message -like '*ffprobe.exe together*' }
    Assert $rejected 'FFmpeg without ffprobe is rejected before installation'
    [IO.File]::WriteAllBytes((Join-Path $bin 'ffprobe.exe'), [byte[]]@())
    Assert ((FfmpegBin $fixture) -eq $bin) 'Nested matching pair is found'
    $extra = Join-Path $fixture 'other'
    New-Item -ItemType Directory -Path $extra | Out-Null
    [IO.File]::WriteAllBytes((Join-Path $extra 'ffmpeg.exe'), [byte[]]@())
    $rejected = $false
    try { FfmpegBin $fixture | Out-Null } catch { $rejected = $_.Exception.Message -like '*found 2*' }
    Assert $rejected 'Ambiguous FFmpeg directories are rejected'
    $release = @{ assets=@(
        @{name='ffmpeg-master-latest-win64-gpl.zip'},
        @{name='ffmpeg-master-latest-win64-gpl-shared.zip'},
        @{name='ffmpeg-master-latest-win64-v3-gpl-shared.zip'}
    ) }
    Assert ((Asset $release '^ffmpeg-master-latest-win64-gpl-shared\.zip$').name -eq 'ffmpeg-master-latest-win64-gpl-shared.zip') 'Correct complete win64 asset selected, not v3'
    if ($PackageZip) {
        $extract = Join-Path $tempRoot 'extracted'
        Expand-Archive -LiteralPath $PackageZip -DestinationPath $extract
        $actualBin = FfmpegBin $extract
        $destination = Join-Path $tempRoot 'installed'
        Copy-Item -LiteralPath $actualBin -Destination $destination -Recurse
        Assert (Test-Path -LiteralPath (Join-Path $destination 'ffmpeg.exe') -PathType Leaf) 'Actual ffmpeg.exe copied to final directory'
        Assert (Test-Path -LiteralPath (Join-Path $destination 'ffprobe.exe') -PathType Leaf) 'Actual ffprobe.exe copied to final directory'
        $dlls = @(Get-ChildItem -LiteralPath $actualBin -Filter '*.dll' -File)
        Assert ($dlls.Count -gt 0) 'Shared package has runtime DLLs'
        foreach ($dll in $dlls) {
            $copy = Join-Path $destination $dll.Name
            Assert ((Get-FileHash -LiteralPath $dll.FullName).Hash -eq (Get-FileHash -LiteralPath $copy).Hash) ('DLL preserved: ' + $dll.Name)
        }
    }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force
}
