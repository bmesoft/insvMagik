<#
Builds insvMagik on this computer, installs it on the phone connected over USB (or wireless
debugging) and starts it. Same idea as mobicut's platform/android/tools/install-test.ps1.

The versionCode is minutes since 2026-01-01, so each build installs as an update.

Needs: the Android SDK (sdk.dir in android/local.properties, or ANDROID_HOME), a JDK 17-21,
USB debugging on the phone.

  .\tools\install.ps1            # build, install, launch
  .\tools\install.ps1 -Logcat    # ... then show the app's log (Ctrl+C stops it)
  .\tools\install.ps1 -NoBuild   # launch what's installed
  .\tools\install.ps1 -ApkOnly   # just build; the APK is android\app\build\outputs\apk\debug\app-debug.apk
#>
param([switch]$Logcat, [switch]$NoBuild, [switch]$ApkOnly)
$ErrorActionPreference = "Stop"
$project = Join-Path (Split-Path $PSScriptRoot -Parent) "android"
$package = "eu.bmesoft.insvmagik"

# Android SDK: local.properties (written by Android Studio), else ANDROID_HOME / ANDROID_SDK_ROOT.
$sdk = $null
$props = Join-Path $project "local.properties"
if (Test-Path $props) {
    $line = Select-String -Path $props -Pattern '^sdk\.dir=(.*)$' | Select-Object -First 1
    if ($line) { $sdk = $line.Matches[0].Groups[1].Value -replace '\\(.)', '$1' }
}
if (-not $sdk) {
    $sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk")) |
        Where-Object { $_ -and (Test-Path (Join-Path $_ "platform-tools\adb.exe")) } |
        Select-Object -First 1
    if ($sdk) { $env:ANDROID_HOME = $sdk }
}
if (-not $sdk -or -not (Test-Path (Join-Path $sdk "platform-tools\adb.exe"))) {
    throw "Android SDK not found: set sdk.dir in android\local.properties or ANDROID_HOME."
}
$adb = Join-Path $sdk "platform-tools\adb.exe"

# A stale JAVA_HOME is common: fall back to the newest JDK 21 or 17 in the usual places.
function Test-Jdk($dir) { $dir -and (Test-Path (Join-Path $dir "bin\java.exe")) }
if (-not (Test-Jdk $env:JAVA_HOME)) {
    $found = Get-ChildItem "C:\Program Files\Java", "C:\Program Files\Microsoft", "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^jdk-?(21|17)[.-]' -and (Test-Jdk $_.FullName) } |
        Sort-Object { ($_.Name -replace '^jdk-?', '' -replace '[^0-9.].*$', '') -as [version] } -Descending |
        Select-Object -First 1
    if (-not $found) { throw "No usable JDK: JAVA_HOME is '$env:JAVA_HOME'. Install JDK 21 or set JAVA_HOME." }
    $env:JAVA_HOME = $found.FullName
}

$env:BUILD_NUMBER = [int](((Get-Date).ToUniversalTime() - [datetime]'2026-01-01').TotalMinutes)
if ($ApkOnly) {
    & (Join-Path $project "gradlew.bat") -p $project assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build failed." }
    Write-Host "APK: $project\app\build\outputs\apk\debug\app-debug.apk" -ForegroundColor Green
    return
}

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
if (-not $devices) {
    & $adb devices
    throw "No phone ready: connect it, turn on USB debugging and allow this computer on the phone."
}
if (@($devices).Count -gt 1 -and -not $env:ANDROID_SERIAL) {
    throw "More than one device connected; set ANDROID_SERIAL to the one to use (adb devices)."
}

if (-not $NoBuild) {
    Write-Host "Building $package, versionCode $env:BUILD_NUMBER"
    & (Join-Path $project "gradlew.bat") -p $project installDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build or install failed." }
}

& $adb shell am force-stop $package
& $adb shell monkey -p $package -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Couldn't start $package (is it installed?)." }
Write-Host "insvMagik started." -ForegroundColor Green

if ($Logcat) {
    Start-Sleep -Seconds 1
    $appPid = (& $adb shell pidof $package).Trim()
    if (-not $appPid) { throw "$package isn't running (crashed at start?). Try: adb logcat -b crash" }
    & $adb logcat --pid=$appPid
}
