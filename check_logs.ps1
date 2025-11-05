# PowerShell script to check logs with ADB
# Usage: .\check_logs.ps1

# Find ADB in common locations
$adbPaths = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    "C:\Android\Sdk\platform-tools\adb.exe"
)

$adbPath = $null
foreach ($path in $adbPaths) {
    if (Test-Path $path) {
        $adbPath = $path
        Write-Host "Found ADB at: $adbPath" -ForegroundColor Green
        break
    }
}

if (-not $adbPath) {
    Write-Host "ADB not found. Please install Android Studio or download ADB." -ForegroundColor Red
    Write-Host "Common locations:" -ForegroundColor Yellow
    foreach ($path in $adbPaths) {
        Write-Host "  - $path" -ForegroundColor Gray
    }
    exit 1
}

# Check if device is connected
Write-Host "`nChecking for connected devices..." -ForegroundColor Cyan
& $adbPath devices

# Start logcat with filters
Write-Host "`nStarting logcat with filters: NfcManager MainActivity" -ForegroundColor Cyan
Write-Host "Press Ctrl+C to stop`n" -ForegroundColor Yellow

& $adbPath logcat -v time -s NfcManager MainActivity Keycard PIN VC UI

