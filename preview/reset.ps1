$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Stopping preview node if it is running..."
try {
    $StopOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ScriptDir "stop.ps1") 2>&1
    $StopExitCode = $LASTEXITCODE
    if ($StopOutput) {
        $StopOutput | ForEach-Object { Write-Host $_ }
    }

    if ($StopExitCode -ne 0) {
        throw "stop.ps1 exited with code $StopExitCode"
    }

    Write-Host "Preview node stopped."
} catch {
    if (($StopOutput -join "`n") -match "not running") {
        Write-Host "Preview node was not running."
    } else {
        Write-Host $_.Exception.Message
        Write-Host "No running preview node was stopped. Continuing with reset."
    }
}

$Paths = @(
    "db-preview",
    "data-preview",
    "qortium-backup-preview",
    "settings-preview-local.json",
    "settings-preview-seed-local.json",
    "run.log",
    "run-error.log",
    "run.pid",
    "qortium.log",
    "QortiumKeyStore.jks",
    "apikey.txt"
) | ForEach-Object { Join-Path $ScriptDir $_ }

$Removed = $false
Write-Host "Removing generated preview files:"
foreach ($Path in $Paths) {
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
        Write-Host "  removed $Path"
        $Removed = $true
    } else {
        Write-Host "  not present $Path"
    }
}

if (-not $Removed) {
    Write-Host "No generated preview files were present."
}

Write-Host ""
Write-Host "Reset complete. Start a fresh preview participant node with:"
Write-Host "  preview\start.bat"
