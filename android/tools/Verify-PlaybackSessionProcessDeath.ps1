[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Serial
)

$ErrorActionPreference = "Stop"
$sdkPath = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adbPath = Join-Path $sdkPath "platform-tools\adb.exe"
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$appApk = Join-Path $projectRoot "android\app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $projectRoot "android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
$instrumentation = "com.lowtube.android.test/androidx.test.runner.AndroidJUnitRunner"
$testClass = "com.lowtube.android.playback.PlaybackSessionRestorationAcceptanceTest"

if (-not (Test-Path -LiteralPath $adbPath)) { throw "ADB was not found at $adbPath" }
if (-not (Test-Path -LiteralPath $appApk)) { throw "Build the debug APK first: $appApk" }
if (-not (Test-Path -LiteralPath $testApk)) { throw "Build the Android instrumentation APK first: $testApk" }

function Invoke-Device {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $adbPath -s $Serial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($exitCode -ne 0) { throw "ADB failed: $($output -join ' ')" }
    return $output
}

function Invoke-Acceptance {
    param(
        [Parameter(Mandatory = $true)][string] $Method,
        [Parameter(Mandatory = $true)][string] $Argument
    )
    $output = Invoke-Device -Arguments @(
        "shell", "am", "instrument", "-w",
        "-e", "class", "$testClass#$Method",
        "-e", $Argument, "true",
        $instrumentation
    )
    $text = $output -join "`n"
    Write-Output $text
    if ($text -notmatch "OK \(1 test\)") { throw "Playback session acceptance did not pass: $Method" }
}

$fixtureSeeded = $false
try {
    Invoke-Device -Arguments @("install", "-r", $appApk) | Out-Null
    Invoke-Device -Arguments @("install", "-r", $testApk) | Out-Null
    $fixtureSeeded = $true
    Invoke-Acceptance -Method "seedsQueueOwnedPlaybackCheckpointForExternalProcessDeath" -Argument "runPlaybackSessionSeed"

    Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android") | Out-Null
    Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android.test") | Out-Null
    $remainingPids = @()
    foreach ($line in (Invoke-Device -Arguments @("shell", "ps"))) {
        if ($line -match "^\s*\S+\s+(\d+)\s+.*\bcom\.lowtube\.android\s*$") {
            $remainingPids += $Matches[1]
        }
    }
    if ($remainingPids.Count -gt 0) { throw "LowTube process still exists after force-stop: $($remainingPids -join ', ')" }

    Invoke-Acceptance -Method "restoresMiniPlayerAndQueueOwnershipAfterExternalProcessDeathWithoutAutoplay" -Argument "runPlaybackSessionRestore"
    Invoke-Acceptance -Method "cleansPlaybackCheckpointAcceptanceFixture" -Argument "runPlaybackSessionCleanup"
    $fixtureSeeded = $false
    Write-Output "PASS: mini-player checkpoint and queue ownership restored after external process death on $Serial; no media was played."
}
finally {
    if ($fixtureSeeded) {
        try {
            Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android") | Out-Null
            Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android.test") | Out-Null
            Invoke-Acceptance -Method "cleansPlaybackCheckpointAcceptanceFixture" -Argument "runPlaybackSessionCleanup" | Out-Null
        } catch {
            Write-Warning "Playback checkpoint fixture cleanup failed: $_"
        }
    }
    try {
        Invoke-Device -Arguments @("shell", "am", "start", "-n", "com.lowtube.android/.MainActivity") | Out-Null
    } catch {
        Write-Warning "Could not return LowTube to foreground: $_"
    }
}
