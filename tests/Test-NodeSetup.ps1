$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot '../Setup.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw ($errors | Out-String)}
foreach($name in @('InstallNode22','FindOne','Fetch')) {
 $fn=$ast.FindAll({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst]},$false) | Where-Object Name -eq $name
 . ([scriptblock]::Create($fn.Extent.Text))
}
$stage=Join-Path ([IO.Path]::GetTempPath()) ('lowtube-node-test-'+[guid]::NewGuid().ToString('N'))
$toolStage=Join-Path $stage 'tools';$cache=Join-Path $stage 'cache';$versions=@()
try {
 $fixture=Join-Path $stage 'fixture/node-v22.23.3-win-x64';New-Item -ItemType Directory -Force $fixture | Out-Null
 [IO.File]::WriteAllText((Join-Path $fixture 'node.exe'),'fake test executable - not run')
 [IO.File]::WriteAllText((Join-Path $fixture 'LICENSE'),'fixture license')
 $zip=Join-Path $stage 'fixture.zip';Compress-Archive -Path $fixture -DestinationPath $zip
 $checksum=(Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
 function Invoke-RestMethod {param($Uri,$TimeoutSec) return @(@{version='v24.0.0'},@{version='v22.23.3'})}
 function Invoke-WebRequest {
  param([switch]$UseBasicParsing,$Uri,$OutFile,$TimeoutSec)
  if($Uri -like '*SHASUMS256.txt'){return @{Content=($checksum+'  node-v22.23.3-win-x64.zip'+"`n")}}
  if($Uri -ne 'https://nodejs.org/dist/v22.23.3/node-v22.23.3-win-x64.zip'){throw 'Wrong release URL'}
  Copy-Item $zip $OutFile
 }
 InstallNode22
 if(-not (Test-Path (Join-Path $toolStage 'js/node22/node.exe'))){throw 'node.exe missing'}
 if(-not (Test-Path (Join-Path $toolStage 'js/node22/LICENSE'))){throw 'license missing'}
 if($versions.Count -ne 1){throw 'download provenance missing'}
 Write-Host 'PASS: Node22 branch, official checksum, package layout and license (offline fixture)'
}finally {if(Test-Path $stage){Remove-Item $stage -Recurse -Force}}
