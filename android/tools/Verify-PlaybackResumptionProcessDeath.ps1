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
$testClass = "com.lowtube.android.playback.PlaybackResumptionAfterProcessDeathAcceptanceTest"

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
    if ($text -notmatch "OK \(1 test\)") { throw "Playback resumption acceptance did not pass: $Method" }
}

$fixtureSeeded = $false
try {
    Invoke-Device -Arguments @("install", "-r", $appApk) | Out-Null
    Invoke-Device -Arguments @("install", "-r", $testApk) | Out-Null
    Invoke-Acceptance -Method "seedsPublicVideoCheckpointAndPreservesPreviousState" -Argument "runPlaybackResumptionProcessDeathSeed"
    $fixtureSeeded = $true

    Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android") | Out-Null
    Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android.test") | Out-Null
    $processes = Invoke-Device -Arguments @("shell", "ps")
    if ($processes -match "com\.lowtube\.android(?:\s|$)") {
        throw "LowTube process is still present after force-stop."
    }

    Invoke-Acceptance -Method "resumesPublicVideoFromSavedCheckpointAfterExternalForceStop" -Argument "runPlaybackResumptionAfterProcessDeath"
    Invoke-Acceptance -Method "restoresPreviousPlaybackCheckpointAndResumeSetting" -Argument "runPlaybackResumptionProcessDeathCleanup"
    $fixtureSeeded = $false
    Write-Output "PASS: a fresh LowTube process resolved and played the saved video after force-stop; playback stayed below two minutes."
}
finally {
    if ($fixtureSeeded) {
        try {
            Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android") | Out-Null
            Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android.test") | Out-Null
            Invoke-Acceptance -Method "restoresPreviousPlaybackCheckpointAndResumeSetting" -Argument "runPlaybackResumptionProcessDeathCleanup" | Out-Null
        } catch {
            Write-Warning "Playback resumption acceptance fixture cleanup failed: $_"
        }
    }
    try {
        Invoke-Device -Arguments @("shell", "am", "start", "-n", "com.lowtube.android/.MainActivity") | Out-Null
    } catch {
        Write-Warning "Could not return LowTube to foreground: $_"
    }
}
