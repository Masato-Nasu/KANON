$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "=== KANON v0.1.0 ===" -ForegroundColor Cyan
Write-Host ""

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$sdk = $null
$candidateList = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    (Join-Path $env:LOCALAPPDATA "Android\Sdk")
)
foreach ($candidate in $candidateList) {
    if ($candidate -and (Test-Path $candidate)) {
        $sdk = (Resolve-Path $candidate).Path
        break
    }
}
if (-not $sdk) {
    throw "Android SDK not found. Open Android Studio once and install the Android SDK."
}

$env:ANDROID_HOME = $sdk
$env:ANDROID_SDK_ROOT = $sdk
Write-Host "Android SDK: $sdk"

$platformsDir = Join-Path $sdk "platforms"
$installedApis = @()
if (Test-Path $platformsDir) {
    $installedApis = @(Get-ChildItem -Path $platformsDir -Directory -ErrorAction SilentlyContinue |
        ForEach-Object {
            if ($_.Name -match '^android-(\d+)$') { [int]$Matches[1] }
        } |
        Sort-Object -Descending)
}
$compileSdk = $installedApis | Where-Object { $_ -ge 35 } | Select-Object -First 1
if (-not $compileSdk) {
    $found = if ($installedApis.Count -gt 0) { ($installedApis -join ", ") } else { "none" }
    throw "Android SDK Platform 35 or newer is required. Installed API levels: $found."
}
$env:KANON_COMPILE_SDK = [string]$compileSdk
Write-Host "Compile SDK: API $compileSdk"

$javaHome = $null
$javaCandidates = @(
    (Join-Path $env:ProgramFiles "Android\Android Studio\jbr"),
    (Join-Path $env:ProgramFiles "Android\Android Studio\jre"),
    $env:JAVA_HOME
)
foreach ($candidate in $javaCandidates) {
    if ($candidate -and (Test-Path (Join-Path $candidate "bin\java.exe"))) {
        $javaHome = (Resolve-Path $candidate).Path
        break
    }
}
if ($javaHome) {
    $env:JAVA_HOME = $javaHome
    $env:Path = "$javaHome\bin;$env:Path"
}

$javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
if (-not $javaCommand) {
    throw "Java not found. Android Studio includes Java; install Android Studio or set JAVA_HOME."
}
Write-Host "Java: $($javaCommand.Source)"

$gradleVersion = "8.11.1"
$tools = Join-Path $PSScriptRoot ".build-tools"
$gradleHome = Join-Path $tools "gradle-$gradleVersion"
$gradleExe = Join-Path $gradleHome "bin\gradle.bat"

if (-not (Test-Path $gradleExe)) {
    New-Item -ItemType Directory -Force -Path $tools | Out-Null
    $zip = Join-Path $tools "gradle-$gradleVersion-bin.zip"
    Write-Host "Downloading Gradle $gradleVersion..." -ForegroundColor Yellow
    Invoke-WebRequest -UseBasicParsing "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip" -OutFile $zip
    Write-Host "Extracting Gradle..." -ForegroundColor Yellow
    Expand-Archive $zip -DestinationPath $tools -Force
    Remove-Item $zip -Force
}

Push-Location $PSScriptRoot
try {
    Write-Host ""
    Write-Host "Building APK..." -ForegroundColor Yellow
    & $gradleExe --no-daemon assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }

    $apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) {
        throw "APK not found after build: $apk"
    }

    $out = Join-Path $PSScriptRoot "KANON-v0.1.0.apk"
    Copy-Item $apk $out -Force
    Write-Host ""
    Write-Host "BUILD OK: $out" -ForegroundColor Green

    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        Write-Host "ADB was not found. Copy the APK to your Android phone and install it manually." -ForegroundColor Yellow
        exit 0
    }

    $devices = & $adb devices
    $connected = @($devices | Select-String "`tdevice$")
    if ($connected.Count -gt 0) {
        Write-Host "Android device detected. Installing..." -ForegroundColor Yellow
        & $adb install -r $out
        if ($LASTEXITCODE -eq 0) {
            Write-Host "INSTALL OK" -ForegroundColor Green
            & $adb shell am start -n "jp.masatolab.kanon/.MainActivity" | Out-Null
        } else {
            Write-Host "ADB install failed. You can still copy the APK to the phone and install it manually." -ForegroundColor Yellow
        }
    } else {
        Write-Host "No USB-debugging device detected." -ForegroundColor Yellow
        Write-Host "Copy KANON-v0.1.0.apk to the phone and install it manually."
    }
}
finally {
    Pop-Location
}
