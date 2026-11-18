[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial,

    [ValidateRange(1, 120)]
    [int] $SampleSeconds = 10,

    [string] $State = "unspecified",

    [string] $CsvPath
)

$ErrorActionPreference = "Stop"
$sdkPath = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adbPath = Join-Path $sdkPath "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adbPath)) { throw "ADB was not found at $adbPath" }
$packageName = "com.lowtube.android"

function Invoke-AdbText {
    param([Parameter(Mandatory = $true)][string[]] $AdbArguments)
    $output = & $adbPath -s $Serial @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($output -join ' ')" }
    return ($output -join "`n").Trim()
}

function Get-LowTubeProcess {
    $lines = Invoke-AdbText -AdbArguments @("shell", "ps")
    foreach ($line in ($lines -split "`n")) {
        if ($line -match "^\s*\S+\s+(\d+)\s+.*\b$packageName\s*$") { return $Matches[1] }
    }
    throw "LowTube is not running on device $Serial. Launch it and retry."
}

function Get-ProcessCounters {
    param([Parameter(Mandatory = $true)][string] $ProcessId)
    $stat = Invoke-AdbText -AdbArguments @("shell", "cat", "/proc/$ProcessId/stat")
    $fields = $stat.Substring($stat.LastIndexOf(")") + 1).Trim() -split "\s+"
    if ($fields.Count -lt 13) { throw "Could not read process CPU counters." }
    $status = Invoke-AdbText -AdbArguments @("shell", "cat", "/proc/$ProcessId/status")
    $rssMatch = [regex]::Match($status, "(?m)^VmRSS:\s+(\d+)")
    $peakMatch = [regex]::Match($status, "(?m)^VmHWM:\s+(\d+)")
    [pscustomobject]@{
        CpuTicks = [long]$fields[11] + [long]$fields[12]
        RssKiB = if ($rssMatch.Success) { [long]$rssMatch.Groups[1].Value } else { 0L }
        PeakRssKiB = if ($peakMatch.Success) { [long]$peakMatch.Groups[1].Value } else { 0L }
    }
}

$processId = Get-LowTubeProcess
$clockText = Invoke-AdbText -AdbArguments @("shell", "getconf", "CLK_TCK")
$clockTicksPerSecond = if ($clockText -match "^\d+$") { [double]$clockText } else { 100.0 }
$first = Get-ProcessCounters -ProcessId $processId
Start-Sleep -Seconds $SampleSeconds
$second = Get-ProcessCounters -ProcessId $processId
$cpuPercent = (($second.CpuTicks - $first.CpuTicks) / ($clockTicksPerSecond * $SampleSeconds)) * 100.0

$memoryOutput = Invoke-AdbText -AdbArguments @("shell", "dumpsys", "meminfo", $packageName)
$pssMatch = [regex]::Match($memoryOutput, "(?m)^\s*TOTAL PSS:\s*(\d+)")
if (-not $pssMatch.Success) { $pssMatch = [regex]::Match($memoryOutput, "(?m)^\s*TOTAL:\s*(\d+)\s+TOTAL SWAP") }
if (-not $pssMatch.Success) { throw "Could not parse total PSS from dumpsys meminfo." }
$rssKiB = if ($second.RssKiB -gt 0) { $second.RssKiB } else { 0L }

$systemMemory = Invoke-AdbText -AdbArguments @("shell", "cat", "/proc/meminfo")
$totalMatch = [regex]::Match($systemMemory, "(?m)^MemTotal:\s*(\d+)")
$availableMatch = [regex]::Match($systemMemory, "(?m)^MemAvailable:\s*(\d+)")
if (-not $availableMatch.Success) { $availableMatch = [regex]::Match($systemMemory, "(?m)^MemFree:\s*(\d+)") }
$apiText = Invoke-AdbText -AdbArguments @("shell", "getprop", "ro.build.version.sdk")
$model = Invoke-AdbText -AdbArguments @("shell", "getprop", "ro.product.model")

$result = [pscustomobject]@{
    Timestamp = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
    State = $State
    Model = $model
    AndroidApi = $apiText
    SampleSeconds = $SampleSeconds
    CpuPercentOneCore = [math]::Round($cpuPercent, 1)
    PssMiB = [math]::Round(([double]$pssMatch.Groups[1].Value / 1024.0), 1)
    RssMiB = if ($rssKiB -gt 0) { [math]::Round(($rssKiB / 1024.0), 1) } else { $null }
    PeakRssMiB = if ($second.PeakRssKiB -gt 0) { [math]::Round(($second.PeakRssKiB / 1024.0), 1) } else { $null }
    DeviceRamMiB = if ($totalMatch.Success) { [math]::Round(([double]$totalMatch.Groups[1].Value / 1024.0), 0) } else { $null }
    DeviceAvailableRamMiB = if ($availableMatch.Success) { [math]::Round(([double]$availableMatch.Groups[1].Value / 1024.0), 0) } else { $null }
}

if ($CsvPath) {
    $csvParent = Split-Path -Parent $CsvPath
    if ($csvParent -and -not (Test-Path -LiteralPath $csvParent)) { New-Item -ItemType Directory -Path $csvParent -Force | Out-Null }
    if (Test-Path -LiteralPath $CsvPath) { $result | Export-Csv -LiteralPath $CsvPath -NoTypeInformation -Append }
    else { $result | Export-Csv -LiteralPath $CsvPath -NoTypeInformation }
}
$result
