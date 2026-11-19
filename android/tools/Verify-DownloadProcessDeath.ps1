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
$testClass = "com.lowtube.android.downloads.DownloadServiceAcceptanceTest"
$databaseName = "lowtube-process-death-$([guid]::NewGuid().ToString('N')).db"

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

function Invoke-Instrumentation {
    param(
        [Parameter(Mandatory = $true)][string] $Method,
        [Parameter(Mandatory = $true)][string] $OptInArgument
    )
    $output = Invoke-Device -Arguments @(
        "shell", "am", "instrument", "-w",
        "-e", "class", "$testClass#$Method",
        "-e", $OptInArgument, "true",
        "-e", "downloadProcessDeathDatabase", $databaseName,
        $instrumentation
    )
    $text = $output -join "`n"
    Write-Output $text
    if ($text -notmatch "OK \(1 test\)") { throw "Instrumentation did not pass: $Method" }
}

$fixtureSeeded = $false
try {
    Invoke-Device -Arguments @("install", "-r", $appApk) | Out-Null
    Invoke-Device -Arguments @("install", "-r", $testApk) | Out-Null

    $fixtureSeeded = $true
    Invoke-Instrumentation -Method "seedInterruptedJobForExternalProcessDeathAcceptance" -OptInArgument "runDownloadProcessDeathSeed"

    # The force-stop is the event under test; it kills the app and its foreground service.
    Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android") | Out-Null
    Invoke-Device -Arguments @("shell", "am", "force-stop", "com.lowtube.android.test") | Out-Null
    $remainingPid = $null
    foreach ($line in (Invoke-Device -Arguments @("shell", "ps"))) {
        if ($line -match "^\s*\S+\s+(\d+)\s+.*\bcom\.lowtube\.android\s*$") {
            $remainingPid = $Matches[1]
            break
        }
    }
    if ($remainingPid) { throw "LowTube process still exists after force-stop: PID $remainingPid" }

    Invoke-Instrumentation -Method "resumesPersistedPartialAfterExternalProcessDeath" -OptInArgument "runDownloadProcessDeathRecovery"
    $fixtureSeeded = $false
    Write-Output "PASS: download partial resumed from persisted byte offset after Android force-stop on $Serial."
}
finally {
    if ($fixtureSeeded) {
        try {
            Invoke-Instrumentation -Method "cleanupExternalProcessDeathAcceptanceFixture" -OptInArgument "runDownloadProcessDeathCleanup" | Out-Null
        } catch {
            Write-Warning "Fixture cleanup failed: $_"
        }
    }
    try {
        Invoke-Device -Arguments @("shell", "am", "start", "-n", "com.lowtube.android/.MainActivity") | Out-Null
    } catch {
        Write-Warning "Could not return LowTube to foreground: $_"
    }
}
