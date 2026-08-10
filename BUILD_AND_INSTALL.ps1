$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "=== KANON v0.1.0 SIGNED RELEASE ===" -ForegroundColor Cyan
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

$propertiesFile = Join-Path $PSScriptRoot "keystore.properties"
$keyFile = Join-Path $PSScriptRoot "release-key\kanon-release.jks"
if (-not (Test-Path $propertiesFile) -or -not (Test-Path $keyFile)) {
    Write-Host ""
    Write-Host "Release signing key is not configured yet." -ForegroundColor Yellow
    Write-Host "Double-click CREATE_RELEASE_KEY.cmd once, then run BUILD_AND_INSTALL.cmd again."
    throw "Release signing files are missing."
}

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

$apksigner = $null
$buildToolsDir = Join-Path $sdk "build-tools"
if (Test-Path $buildToolsDir) {
    $buildToolCandidates = @(Get-ChildItem -Path $buildToolsDir -Directory -ErrorAction SilentlyContinue |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending)
    foreach ($dir in $buildToolCandidates) {
        $candidate = Join-Path $dir.FullName "apksigner.bat"
        if (Test-Path $candidate) {
            $apksigner = $candidate
            break
        }
    }
}
if (-not $apksigner) {
    throw "apksigner.bat was not found in Android SDK build-tools. Install Android SDK Build-Tools."
}

Push-Location $PSScriptRoot
try {
    Write-Host ""
    Write-Host "Building SIGNED RELEASE APK..." -ForegroundColor Yellow
    & $gradleExe --no-daemon clean assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle release build failed with exit code $LASTEXITCODE."
    }

    $apk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $apk)) {
        throw "Signed release APK not found: $apk"
    }

    $out = Join-Path $PSScriptRoot "KANON-v0.1.0.apk"
    Copy-Item $apk $out -Force

    Write-Host ""
    Write-Host "Verifying APK signature..." -ForegroundColor Yellow
    & $apksigner verify --verbose --print-certs $out
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed. Do NOT publish this APK."
    }

    Write-Host ""
    Write-Host "SIGNED RELEASE BUILD OK" -ForegroundColor Green
    Write-Host "APK: $out" -ForegroundColor Green
    Write-Host "This is the file to upload to GitHub Releases." -ForegroundColor Green

    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        Write-Host "ADB was not found. The signed release APK is still ready for distribution." -ForegroundColor Yellow
        exit 0
    }

    $devices = & $adb devices
    $connected = @($devices | Select-String "`tdevice$")
    if ($connected.Count -gt 0) {
        Write-Host ""
        Write-Host "Android device detected. Installing signed release..." -ForegroundColor Yellow
        $installOutput = & $adb install -r $out 2>&1
        $installOutput | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -eq 0) {
            Write-Host "INSTALL OK" -ForegroundColor Green
            & $adb shell am start -n "jp.masatolab.kanon/.MainActivity" | Out-Null
        } else {
            Write-Host ""
            if (($installOutput -join "`n") -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
                Write-Host "The previously installed KANON was signed with a different key (for example the old debug APK)." -ForegroundColor Yellow
                Write-Host "Uninstall the old KANON from the phone, then run this script again." -ForegroundColor Yellow
                Write-Host "NOTE: uninstalling clears KANON app data, so the OpenAI API key must be entered again." -ForegroundColor Yellow
            } else {
                Write-Host "ADB install failed, but the signed release APK was built and verified successfully." -ForegroundColor Yellow
            }
        }
    } else {
        Write-Host "No USB-debugging device detected." -ForegroundColor Yellow
        Write-Host "The signed release APK is ready: KANON-v0.1.0.apk"
    }
}
finally {
    Pop-Location
}
