param([Parameter(Mandatory=$true)][string]$PackageZip)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$tokens=$null;$errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile((Join-Path $root 'Setup.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw ($errors | Out-String)}
foreach($name in @('Asset','FindOne','PeMachine','InstallYtDirectory','CommitYtDirectory')){
 $node=$ast.FindAll({param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst]},$false)|Where-Object Name -eq $name
 . ([scriptblock]::Create($node.Extent.Text))
}
function Assert([bool]$ok,[string]$message){if(-not $ok){throw "FAIL: $message"};Write-Host "PASS: $message"}
$stage=Join-Path ([IO.Path]::GetTempPath()) ('lowtube-portable-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
try{
 $release=@{assets=@(@{name='yt-dlp.exe'},@{name='yt-dlp_win.zip'},@{name='yt-dlp_win_x86.zip'},@{name='yt-dlp_win_arm64.zip'})}
 Assert ((Asset $release '^yt-dlp_win\.zip$').name -eq 'yt-dlp_win.zip') 'Only the x64 onedir package is selected'
 $source=Join-Path $stage 'ready'
 InstallYtDirectory $PackageZip $source
 Assert ((PeMachine (Join-Path $source 'yt-dlp.exe')) -eq 0x8664) 'Actual portable yt-dlp executable is x64'
 $original=Join-Path $stage 'yt-extracted'
 $all=@(Get-ChildItem -LiteralPath $original -Recurse -File)
 $copied=@(Get-ChildItem -LiteralPath $source -Recurse -File)
 Assert ($all.Count -eq $copied.Count -and $all.Count -gt 20) 'Full distribution copied, not just the executable'
 foreach($file in $all){
  $relative=$file.FullName.Substring($original.Length+1)
  if((Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath (Join-Path $source $relative)).Hash){throw "Changed file: $relative"}
 }
 Assert $true 'Every copied dependency and license matches the archive'
 $tools=Join-Path $stage 'tools';$dest=Join-Path $tools 'yt-dlp'
 New-Item -ItemType Directory -Path $dest -Force | Out-Null
 [IO.File]::WriteAllText((Join-Path $dest 'old.txt'),'old')
 [IO.File]::WriteAllText((Join-Path $tools 'mpv-marker.txt'),'keep')
 CommitYtDirectory $source $dest
 Assert ((Test-Path -LiteralPath (Join-Path $dest 'yt-dlp.exe')) -and -not (Test-Path -LiteralPath (Join-Path $dest 'old.txt'))) 'Targeted update replaces the old yt-dlp directory'
 Assert ([IO.File]::ReadAllText((Join-Path $tools 'mpv-marker.txt')) -eq 'keep') 'Unrelated components are preserved'
 $bad=Join-Path $stage 'does-not-exist';$failed=$false
 try{CommitYtDirectory $bad $dest}catch{$failed=$true}
 Assert ($failed -and (Test-Path -LiteralPath (Join-Path $dest 'yt-dlp.exe'))) 'Failed replacement restores the previous working yt-dlp directory'
}finally{Remove-Item -LiteralPath $stage -Recurse -Force}
