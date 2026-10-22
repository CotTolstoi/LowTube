param([switch]$Update,[switch]$YtDlpOnly)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$root = $PSScriptRoot
$headers = @{ 'User-Agent' = 'LowTube-Setup/0.1'; 'Accept' = 'application/vnd.github+json' }
$stage = Join-Path $root ('.setup-' + [Guid]::NewGuid().ToString('N'))
$toolStage = Join-Path $stage 'tools'
$versions = @()
$cache = Join-Path $root 'download-cache'
$diagnostic = Join-Path $root 'setup-diagnostics.txt'
$keepStage = $false

function Latest([string]$repo) {
    return Invoke-RestMethod -Uri ('https://api.github.com/repos/' + $repo + '/releases/latest') -Headers $headers -TimeoutSec 60
}
function Asset($release, [string]$pattern) {
    $found = @($release.assets | Where-Object { $_.name -match $pattern })
    if ($found.Count -ne 1) { throw "Expected one asset for $pattern; found $($found.Count). See README_UK.txt for manual setup." }
    return $found[0]
}
function Fetch($asset, [string]$destination) {
    $uri = [Uri]$asset.browser_download_url
    if ($uri.Scheme -ne 'https' -or $uri.Host -notin @('github.com','sdk.lunarg.com','nodejs.org')) { throw 'Unexpected download host.' }
    if ($asset.digest -notmatch '^sha256:[a-fA-F0-9]{64}$') { throw "Publisher SHA256 digest missing for $($asset.name). Download manually after verification." }
    $parent = Split-Path -Parent $destination
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    New-Item -ItemType Directory -Force -Path $cache | Out-Null
    $expected = $asset.digest.Substring(7).ToLowerInvariant()
    $cached = Join-Path $cache ($expected + '-' + [IO.Path]::GetFileName($asset.name))
    $valid = (Test-Path -LiteralPath $cached -PathType Leaf) -and ((Get-FileHash -Algorithm SHA256 -LiteralPath $cached).Hash.ToLowerInvariant() -eq $expected)
    if (-not $valid) {
        Write-Host "Downloading $($asset.name)..."
        $partial = $cached + '.part'
        Invoke-WebRequest -UseBasicParsing -Uri $uri.AbsoluteUri -OutFile $partial -TimeoutSec 900
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $partial).Hash.ToLowerInvariant() -ne $expected) {
            Remove-Item -LiteralPath $partial -Force
            throw "SHA256 mismatch: $($asset.name). Download was not used."
        }
        Move-Item -LiteralPath $partial -Destination $cached -Force
    } else { Write-Host "Using verified cache: $($asset.name)" }
    Copy-Item -LiteralPath $cached -Destination $destination -Force
    $hash = $expected
    $script:versions += [pscustomobject]@{ asset=$asset.name; url=$uri.AbsoluteUri; sha256=$hash }
}
function InstallNode22 {
    $index = Invoke-RestMethod -Uri 'https://nodejs.org/dist/index.json' -TimeoutSec 60
    $release = @($index | Where-Object { $_.version -match '^v22\.' })[0]
    if (-not $release -or $release.version -notmatch '^v22\.\d+\.\d+$') { throw 'Could not find a Node.js 22 release.' }
    $base = 'https://nodejs.org/dist/' + $release.version + '/'
    $name = 'node-' + $release.version + '-win-x64.zip'
    $sums = (Invoke-WebRequest -UseBasicParsing -Uri ($base + 'SHASUMS256.txt') -TimeoutSec 60).Content
    $match = [regex]::Match($sums, '(?m)^([a-fA-F0-9]{64})\s+' + [regex]::Escape($name) + '\s*$')
    if (-not $match.Success) { throw 'Node.js publisher checksum missing.' }
    $asset = @{name=$name;digest=('sha256:'+$match.Groups[1].Value);browser_download_url=($base+$name)}
    $archive = Join-Path $stage 'node22.zip'
    Fetch $asset $archive
    $extract = Join-Path $stage 'node22-extracted'
    Expand-Archive -LiteralPath $archive -DestinationPath $extract
    $node = FindOne $extract 'node.exe'
    $destination = Join-Path $toolStage 'js\node22'
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    Copy-Item -LiteralPath $node -Destination (Join-Path $destination 'node.exe') -Force
    Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $node) 'LICENSE') -Destination (Join-Path $destination 'LICENSE') -Force
}

function Extract7z([string]$archive,[string]$destination) {
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
    & (Join-Path $stage '7zr.exe') x -y "-o$destination" $archive | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not extract $archive" }
}
function FindOne([string]$directory,[string]$filename) {
    $files = @(Get-ChildItem -LiteralPath $directory -Recurse -File -Filter $filename)
    if ($files.Count -ne 1) { throw "Expected one $filename in $directory; found $($files.Count)." }
    return $files[0].FullName
}
function InstallYtDirectory([string]$archive,[string]$destination) {
    $extract = Join-Path $stage 'yt-extracted'
    Expand-Archive -LiteralPath $archive -DestinationPath $extract
    $exe = FindOne $extract 'yt-dlp.exe'
    if ((PeMachine $exe) -ne 0x8664) { throw 'The yt-dlp package is not x64.' }
    # Keep the entire onedir distribution, including its _internal dependencies.
    Copy-Item -LiteralPath (Split-Path -Parent $exe) -Destination $destination -Recurse
}
function CommitYtDirectory([string]$source,[string]$destination) {
    $backup = $destination + '.previous-' + [Guid]::NewGuid().ToString('N')
    $moved = $false
    if (Test-Path -LiteralPath $destination) { Move-Item -LiteralPath $destination -Destination $backup; $moved=$true }
    try { Move-Item -LiteralPath $source -Destination $destination }
    catch { if ($moved) { Move-Item -LiteralPath $backup -Destination $destination }; throw }
    if ($moved) { Remove-Item -LiteralPath $backup -Recurse -Force }
}
function FfmpegBin([string]$directory) {
    $ffmpeg = FindOne $directory 'ffmpeg.exe'
    $bin = Split-Path -Parent $ffmpeg
    if (-not (Test-Path -LiteralPath (Join-Path $bin 'ffprobe.exe') -PathType Leaf)) {
        throw 'The FFmpeg package must contain ffmpeg.exe and ffprobe.exe together. No components were installed.'
    }
    return $bin
}
function PeMachine([string]$file) {
    $stream = [IO.File]::OpenRead($file)
    $reader = New-Object IO.BinaryReader($stream)
    try {
        if ($stream.Length -lt 64 -or $reader.ReadUInt16() -ne 0x5A4D) { throw "Invalid PE file: $file" }
        $stream.Position = 0x3C
        $offset = $reader.ReadUInt32()
        if ($offset -gt $stream.Length - 6) { throw "Invalid PE header: $file" }
        $stream.Position = $offset
        if ($reader.ReadUInt32() -ne 0x4550) { throw "Invalid PE signature: $file" }
        return $reader.ReadUInt16()
    } finally { $reader.Dispose(); $stream.Dispose() }
}
function VulkanDll([string]$directory) {
    $files = @(Get-ChildItem -LiteralPath $directory -Filter 'vulkan-1.dll' -Recurse -File | Where-Object { (PeMachine $_.FullName) -eq 0x8664 })
    if ($files.Count -ne 1) { throw "Expected one x64 Vulkan loader in the official runtime package; found $($files.Count)." }
    return $files[0].FullName
}
function PrepareVulkan {
    # A system runtime can satisfy the loader dependency even with no Vulkan GPU.
    $system = [Environment]::GetFolderPath([Environment+SpecialFolder]::System)
    $systemDll = Join-Path $system 'vulkan-1.dll'
    if ((Test-Path -LiteralPath $systemDll -PathType Leaf) -and (PeMachine $systemDll) -eq 0x8664) {
        Write-Host 'Vulkan loader: using installed x64 Windows runtime.'
        return $null
    }
    # Pin publisher SHA256; never fetch a DLL from a third-party DLL download site.
    $asset = @{
        name = 'VulkanRT-X64-1.4.363.0-Components.zip'
        digest = 'sha256:a25a927aa8b9f0371048f1861cf88ac3b9bc9b1fb332c42d897c8ab32695769a'
        browser_download_url = 'https://sdk.lunarg.com/sdk/download/1.4.363.0/windows/VulkanRT-X64-1.4.363.0-Components.zip?Human=true'
    }
    $zip = Join-Path $stage 'vulkan-runtime.zip'
    # A manually downloaded official ZIP may be placed next to SETUP.cmd.
    $localZip = Join-Path $root $asset.name
    if (Test-Path -LiteralPath $localZip -PathType Leaf) {
        if ((Get-FileHash -LiteralPath $localZip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $asset.digest.Substring(7)) { throw 'The local Vulkan ZIP has the wrong SHA256. Download the exact official 1.4.363.0 Components ZIP.' }
        Copy-Item -LiteralPath $localZip -Destination $zip
    } else {
        try { Fetch $asset $zip } catch {
            throw "The mpv build requires vulkan-1.dll. Automatic runtime download failed: $($_.Exception.Message)`nOpen https://vulkan.lunarg.com/sdk/home#windows and install the Windows x64 Vulkan Runtime (not the SDK), then run SETUP.cmd again. Alternatively place the official $($asset.name) next to SETUP.cmd. Other components have not been downloaded yet."
        }
    }
    $extract = Join-Path $stage 'vulkan-runtime'
    Expand-Archive -LiteralPath $zip -DestinationPath $extract
    return VulkanDll $extract
}
function CheckComponent([string]$exe, [string]$argument) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $exe
    $info.Arguments = $argument
    $info.WorkingDirectory = Split-Path -Parent $exe
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) { $process.Kill(); throw "Component startup timed out: $exe" }
        $outText = $stdout.GetAwaiter().GetResult()
        $errText = $stderr.GetAwaiter().GetResult()
        $code = $process.ExitCode
        $hex = '0x{0:X8}' -f ([BitConverter]::ToUInt32([BitConverter]::GetBytes([int]$code),0))
        @("Component: $exe", "Exit: $code ($hex)", $outText, $errText) | Add-Content -LiteralPath $diagnostic -Encoding UTF8
        if ($code -ne 0) {
            $script:keepStage = $true
            $hint = switch ($hex) {
                '0xC0000135' { 'Windows could not load a required DLL. Vulkan runtime is checked separately; double-click the preserved EXE below to see the missing DLL name.' }
                '0xC000001D' { 'This executable uses an instruction unsupported by the CPU.' }
                '0xC000007B' { 'Windows rejected an executable or DLL image; check x64 architecture and file integrity.' }
                default { 'See setup-diagnostics.txt for component output.' }
            }
            throw "$hint`nExecutable: $exe`nExit: $code ($hex)`nDiagnostic log: $diagnostic`nExisting tools are unchanged."
        }
    } finally { $process.Dispose() }
}

try {
    if (-not [Environment]::Is64BitOperatingSystem) { throw 'Windows 10/11 x64 is required.' }
    if (-not [Environment]::Is64BitProcess) { throw 'Run SETUP.cmd using 64-bit Windows PowerShell.' }
    $framework = Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\NET Framework Setup\NDP\v4\Full' -ErrorAction SilentlyContinue
    if (-not $framework -or $framework.Release -lt 528040) { throw 'Install .NET Framework 4.8 first: https://dotnet.microsoft.com/download/dotnet-framework/net48' }
    if (Get-Process -Name LowTube -ErrorAction SilentlyContinue) { throw 'Close LowTube before installing or updating components.' }
    Write-Host 'LowTube setup. LowTube itself does not require administrator rights.'
    if ($YtDlpOnly) { Write-Host 'Updates only yt-dlp to the official unpacked x64 package for faster HDD startup.' }
    else { Write-Host 'Downloads yt-dlp, QuickJS-NG, mpv, FFmpeg and 7-Zip from GitHub, and Node.js 22 from nodejs.org.' }
    Write-Host 'Uses baseline x86_64 builds, never x86_64-v3. SHA256 is verified before execution.'
    if (-not $YtDlpOnly) { Write-Host 'Checks the Vulkan loader required by mpv. Verified downloads are cached.' }
    @('LowTube setup diagnostics', ('UTC: ' + [DateTime]::UtcNow.ToString('o')), ('OS: ' + [Environment]::OSVersion.VersionString)) | Set-Content -LiteralPath $diagnostic -Encoding UTF8
    New-Item -ItemType Directory -Force -Path $toolStage | Out-Null
    $vulkanDll = $null
    if (-not $YtDlpOnly) { $vulkanDll = PrepareVulkan }
    $yt = Latest 'yt-dlp/yt-dlp'
    Fetch (Asset $yt '^yt-dlp_win\.zip$') (Join-Path $stage 'yt-dlp.zip')
    InstallYtDirectory (Join-Path $stage 'yt-dlp.zip') (Join-Path $toolStage 'yt-dlp')
    if ($YtDlpOnly) {
        CheckComponent (Join-Path $toolStage 'yt-dlp\yt-dlp.exe') '--version'
        $versions | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $toolStage 'yt-dlp\lowtube-version.json') -Encoding UTF8
        $tools = Join-Path $root 'tools'
        New-Item -ItemType Directory -Force -Path $tools | Out-Null
        CommitYtDirectory (Join-Path $toolStage 'yt-dlp') (Join-Path $tools 'yt-dlp')
        Write-Host 'Ready. Unpacked yt-dlp installed. mpv, FFmpeg and QuickJS were not changed.' -ForegroundColor Green
        return
    }
    # Preserve optional JavaScript runtimes during a complete component update.
    $existingJs = Join-Path $root 'tools\js'
    if (Test-Path -LiteralPath $existingJs) { Copy-Item -LiteralPath $existingJs -Destination (Join-Path $toolStage 'js') -Recurse }
    InstallNode22
    $qjs = Latest 'quickjs-ng/quickjs'
    Fetch (Asset $qjs '^qjs-windows-x86_64\.exe$') (Join-Path $toolStage 'qjs.exe')
    $seven = Latest 'ip7z/7zip'
    Fetch (Asset $seven '^7zr\.exe$') (Join-Path $stage '7zr.exe')
    $mpv = Latest 'shinchiro/mpv-winbuild-cmake'
    Fetch (Asset $mpv '^mpv-x86_64-\d{8}-[^/]+\.7z$') (Join-Path $stage 'mpv.7z')
    # shinchiro's FFmpeg is built with --disable-ffprobe. Use the complete
    # yt-dlp FFmpeg package instead; retain its adjacent shared-library DLLs.
    $ffmpegRelease = Latest 'yt-dlp/FFmpeg-Builds'
    Fetch (Asset $ffmpegRelease '^ffmpeg-master-latest-win64-gpl-shared\.zip$') (Join-Path $stage 'ffmpeg.zip')
    $mpvExtract = Join-Path $stage 'mpv-extracted'
    $ffExtract = Join-Path $stage 'ffmpeg-extracted'
    Extract7z (Join-Path $stage 'mpv.7z') $mpvExtract
    Expand-Archive -LiteralPath (Join-Path $stage 'ffmpeg.zip') -DestinationPath $ffExtract
    # Preserve adjacent DLLs and license files in each distribution.
    $mpvDir = Split-Path -Parent (FindOne $mpvExtract 'mpv.exe')
    $ffDir = FfmpegBin $ffExtract
    Copy-Item -LiteralPath $mpvDir -Destination (Join-Path $toolStage 'mpv') -Recurse
    if ($vulkanDll) {
        Copy-Item -LiteralPath $vulkanDll -Destination (Join-Path $toolStage 'mpv\vulkan-1.dll')
        $runtimeLicense = Join-Path $toolStage 'mpv\vulkan-runtime-docs'
        New-Item -ItemType Directory -Path $runtimeLicense -Force | Out-Null
        Get-ChildItem -LiteralPath (Join-Path $stage 'vulkan-runtime') -File -Recurse | Where-Object { $_.Name -match '(?i)license|copyright|notice|readme' } | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $runtimeLicense -Force }
    }
    Copy-Item -LiteralPath $ffDir -Destination (Join-Path $toolStage 'ffmpeg') -Recurse
    # Keep the distribution's license and documentation alongside the binaries.
    $ffPackage = Split-Path -Parent $ffDir
    foreach ($entry in @(Get-ChildItem -LiteralPath $ffPackage)) {
        if ($entry.FullName -ne $ffDir) { Copy-Item -LiteralPath $entry.FullName -Destination (Join-Path $toolStage 'ffmpeg') -Recurse }
    }
    $checks = @(
        @{file='yt-dlp\yt-dlp.exe'; arg='--version'},
        @{file='qjs.exe'; arg='--help'},
        @{file='js\node22\node.exe'; arg='--version'},
        @{file='mpv\mpv.exe'; arg='--version'},
        @{file='ffmpeg\ffmpeg.exe'; arg='-version'},
        @{file='ffmpeg\ffprobe.exe'; arg='-version'}
    )
    foreach ($check in $checks) {
        $exe = Join-Path $toolStage $check.file
        CheckComponent $exe $check.arg
    }
    $versions | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $toolStage 'versions.json') -Encoding UTF8
    & (Join-Path $root 'Build.ps1')
    if (-not (Test-Path -LiteralPath (Join-Path $root 'LowTube.exe'))) { throw 'Client build did not produce LowTube.exe.' }
    $destination = Join-Path $root 'tools'
    $backup = Join-Path $root ('tools.previous-' + [Guid]::NewGuid().ToString('N'))
    $moved = $false
    if (Test-Path -LiteralPath $destination) { Move-Item -LiteralPath $destination -Destination $backup; $moved=$true }
    try { Move-Item -LiteralPath $toolStage -Destination $destination }
    catch { if ($moved) { Move-Item -LiteralPath $backup -Destination $destination }; throw }
    if ($moved) { Remove-Item -LiteralPath $backup -Recurse -Force }
    Write-Host 'Ready. Start LowTube.exe or START.cmd.' -ForegroundColor Green
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    if (Test-Path -LiteralPath $diagnostic) { $_.Exception.Message | Add-Content -LiteralPath $diagnostic -Encoding UTF8 }
    exit 1
} finally {
    if ((Test-Path -LiteralPath $stage) -and -not $keepStage) { Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue }
}
