$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'Install .NET Framework 4.8: https://dotnet.microsoft.com/download/dotnet-framework/net48' }
$sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'src') -Filter '*.cs' | ForEach-Object { $_.FullName })
$argsList = @('/nologo','/target:winexe','/platform:x64','/optimize+','/utf8output','/codepage:65001',
    '/reference:System.dll','/reference:System.Xml.dll','/reference:System.Core.dll','/reference:System.Drawing.dll',
    '/reference:System.Windows.Forms.dll','/reference:System.Security.dll',
    ('/win32manifest:' + (Join-Path $root 'app.manifest')),
    ('/out:' + (Join-Path $root 'LowTube.exe'))) + $sources
& $compiler $argsList
if ($LASTEXITCODE -ne 0) { throw 'C# compilation failed. Copy the compiler error text for troubleshooting.' }
Write-Host 'LowTube.exe built successfully.'
