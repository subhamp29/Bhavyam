# Verify Java is working
& java -version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "Java error detected. Manual steps:"
    Write-Host "1. Uninstall current JDK via Apps & Features"
    Write-Host "2. Download fresh JDK 17 from https://adoptium.net/temurin/releases/"
    Write-Host "3. Install it"
    exit 1
}

# Build release
.\gradlew.bat :app:assembleRelease --no-daemon
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed"
    exit 1
}

# Verify APK exists
$apkPath = "app\build\outputs\apk\release\app-release.apk"
if (Test-Path $apkPath) {
    Write-Host "APK built successfully at:"
    Write-Host (Resolve-Path $apkPath)
} else {
    Write-Host "APK not found - check build output"
    exit 1
}