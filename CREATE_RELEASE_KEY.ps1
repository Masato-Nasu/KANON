$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "=== KANON release signing key setup ===" -ForegroundColor Cyan
Write-Host ""

$javaHome = $null
$javaCandidates = @(
    (Join-Path $env:ProgramFiles "Android\Android Studio\jbr"),
    (Join-Path $env:ProgramFiles "Android\Android Studio\jre"),
    $env:JAVA_HOME
)
foreach ($candidate in $javaCandidates) {
    if ($candidate -and (Test-Path (Join-Path $candidate "bin\keytool.exe"))) {
        $javaHome = (Resolve-Path $candidate).Path
        break
    }
}

$keytool = $null
if ($javaHome) {
    $keytool = Join-Path $javaHome "bin\keytool.exe"
} else {
    $keytoolCommand = Get-Command keytool.exe -ErrorAction SilentlyContinue
    if ($keytoolCommand) { $keytool = $keytoolCommand.Source }
}

if (-not $keytool -or -not (Test-Path $keytool)) {
    throw "keytool.exe was not found. Install Android Studio / Java 17 or set JAVA_HOME."
}

$keyDir = Join-Path $PSScriptRoot "release-key"
$keystore = Join-Path $keyDir "kanon-release.jks"
$propertiesFile = Join-Path $PSScriptRoot "keystore.properties"

if ((Test-Path $keystore) -or (Test-Path $propertiesFile)) {
    Write-Host "Release signing files already exist." -ForegroundColor Yellow
    Write-Host "Keystore: $keystore"
    Write-Host "Properties: $propertiesFile"
    Write-Host ""
    Write-Host "Nothing was overwritten. Keep these files safe and use BUILD_AND_INSTALL.cmd." -ForegroundColor Green
    exit 0
}

New-Item -ItemType Directory -Force -Path $keyDir | Out-Null

# Generate a strong local password using a restricted alphabet that is safe in Java .properties files.
$alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
$bytes = New-Object byte[] 48
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($bytes)
} finally {
    $rng.Dispose()
}
$password = -join ($bytes | ForEach-Object { $alphabet[$_ % $alphabet.Length] })
$alias = "kanon-release"

Write-Host "Creating KANON release keystore..." -ForegroundColor Yellow
& $keytool -genkeypair -v `
    -keystore $keystore `
    -storetype PKCS12 `
    -alias $alias `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -storepass $password `
    -keypass $password `
    -dname "CN=Masato Nasu, O=MASATO LAB, C=JP"

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystore)) {
    throw "Release keystore creation failed."
}

$properties = @(
    "storeFile=release-key/kanon-release.jks",
    "storePassword=$password",
    "keyAlias=$alias",
    "keyPassword=$password"
)
Set-Content -Path $propertiesFile -Value $properties -Encoding ASCII

Write-Host ""
Write-Host "RELEASE KEY CREATED" -ForegroundColor Green
Write-Host "Keystore: $keystore"
Write-Host "Properties: $propertiesFile"
Write-Host ""
Write-Host "IMPORTANT:" -ForegroundColor Yellow
Write-Host "Back up BOTH files somewhere safe."
Write-Host "If this signing key is lost, future versions cannot update the installed release app." -ForegroundColor Yellow
Write-Host ""
Write-Host "Next: double-click BUILD_AND_INSTALL.cmd"
