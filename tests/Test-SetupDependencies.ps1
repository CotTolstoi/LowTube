$ErrorActionPreference = 'Stop'
$setup = Join-Path (Split-Path -Parent $PSScriptRoot) 'Setup.ps1'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($setup,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw ($errors | Out-String) }
foreach ($name in @('Fetch','PeMachine','VulkanDll','CheckComponent')) {
    $definition = $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] },$false) | Where-Object Name -eq $name
    . ([scriptblock]::Create($definition.Extent.Text))
}
$sandbox = Join-Path ([IO.Path]::GetTempPath()) ('lowtube-dependency-tests-' + [Guid]::NewGuid().ToString('N'))
$cache = Join-Path $sandbox 'cache'
$versions = @(); $downloads = 0; $wrongDownload = $false
New-Item -ItemType Directory -Path $sandbox | Out-Null
function Assert([bool]$ok,[string]$message) { if(-not $ok){throw "FAIL: $message"}; Write-Host "PASS: $message" }
# Deliberately mock network: the regression suite must never download or execute remote code.
function Invoke-WebRequest {
    param([switch]$UseBasicParsing,[string]$Uri,[string]$OutFile,[int]$TimeoutSec)
    $script:downloads++
    [IO.File]::WriteAllText($OutFile, $(if($wrongDownload){'wrong'}else{'verified fixture'}))
}
try {
    $fixture = Join-Path $sandbox 'fixture.txt'
    [IO.File]::WriteAllText($fixture,'verified fixture')
    $hash = (Get-FileHash -LiteralPath $fixture).Hash.ToLowerInvariant()
    $asset = @{name='fixture.zip';digest=('sha256:'+$hash);browser_download_url='https://github.com/example/project/releases/download/test/fixture.zip'}
    $destination = Join-Path $sandbox 'stage/fixture.zip'
    Fetch $asset $destination
    Assert ($downloads -eq 1 -and (Get-FileHash -LiteralPath $destination).Hash.ToLowerInvariant() -eq $hash) 'First download verified and staged'
    Fetch $asset $destination
    Assert ($downloads -eq 1) 'Retry uses the verified cache without another download'
    $cacheFile = Join-Path $cache ($hash+'-fixture.zip')
    [IO.File]::WriteAllText($cacheFile,'corrupted')
    Fetch $asset $destination
    Assert ($downloads -eq 2 -and (Get-FileHash -LiteralPath $cacheFile).Hash.ToLowerInvariant() -eq $hash) 'Corrupted cache is replaced by a verified download'
    [IO.File]::WriteAllText($cacheFile,'corrupted again'); $wrongDownload=$true
    $rejected=$false;try{Fetch $asset $destination}catch{$rejected=$_.Exception.Message -like '*SHA256 mismatch*'}
    Assert $rejected 'Bad download hash is rejected'
    Assert ((Get-FileHash -LiteralPath $destination).Hash.ToLowerInvariant() -eq $hash) 'Bad download does not overwrite the staged good file'
    $bad=@{name='bad.zip';digest=$asset.digest;browser_download_url='https://untrusted.invalid/bad.zip'}
    $rejected=$false;try{Fetch $bad $destination}catch{$rejected=$_.Exception.Message -like '*Unexpected download host*'}
    Assert $rejected 'Untrusted download origin rejected'
    $pe = Join-Path (Split-Path -Parent $PSScriptRoot) 'LowTube.exe'
    Assert ((PeMachine $pe) -eq 0x8664) 'Shipping EXE is recognized as x64'
    $runtime = Join-Path $sandbox 'runtime'
    $x64 = Join-Path $runtime 'x64'; $x86 = Join-Path $runtime 'x86'
    New-Item -ItemType Directory -Path $x64,$x86 | Out-Null
    Copy-Item -LiteralPath $pe -Destination (Join-Path $x64 'vulkan-1.dll')
    $bytes=[IO.File]::ReadAllBytes($pe);$offset=[BitConverter]::ToUInt32($bytes,0x3C);$bytes[$offset+4]=0x4C;$bytes[$offset+5]=0x01
    [IO.File]::WriteAllBytes((Join-Path $x86 'vulkan-1.dll'),$bytes)
    Assert ((VulkanDll $runtime) -eq (Join-Path $x64 'vulkan-1.dll')) 'Runtime picker excludes the x86 PE even when names are identical'
    $rejected=$false;try{VulkanDll $x86 | Out-Null}catch{$rejected=$_.Exception.Message -like '*found 0*'}
    Assert $rejected 'x86-only runtime is rejected'
    $rejected=$false;try{PeMachine $fixture | Out-Null}catch{$rejected=$_.Exception.Message -like '*Invalid PE file*'}
    Assert $rejected 'Non-PE payload is rejected'
    $diagnostic=Join-Path $sandbox 'diagnostics.txt';$keepStage=$false
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        CheckComponent '/bin/true' ''
        Assert (Test-Path -LiteralPath $diagnostic) 'Component success is logged'
        $rejected=$false;try{CheckComponent '/bin/false' ''}catch{$rejected=$_.Exception.Message -like '*0x00000001*'}
        Assert ($rejected -and $keepStage) 'Failed component preserves diagnostic stage and reports hex exit code'
    }
} finally { Remove-Item -LiteralPath $sandbox -Recurse -Force }
