$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RuntimeDirOption = ""

function Show-Usage {
    Write-Host "Usage: preview\reset.bat [--runtime-dir=PATH]"
}

foreach ($Arg in $args) {
    if ($Arg -like "--runtime-dir=*") {
        $RuntimeDirOption = $Arg.Substring("--runtime-dir=".Length)
    } elseif ($Arg -eq "-h" -or $Arg -eq "--help") {
        Show-Usage
        exit 0
    } else {
        Write-Host "Unknown option: $Arg"
        Show-Usage
        exit 1
    }
}

$RuntimeDirInput = $RuntimeDirOption
if ([string]::IsNullOrWhiteSpace($RuntimeDirInput)) {
    $RuntimeDirInput = $env:QORTIUM_PREVIEW_RUNTIME_DIR
}
if ([string]::IsNullOrWhiteSpace($RuntimeDirInput)) {
    $RuntimeDir = $ScriptDir
} elseif (Test-Path -LiteralPath $RuntimeDirInput -PathType Container) {
    $RuntimeDir = (Resolve-Path -LiteralPath $RuntimeDirInput).Path
} else {
    $RuntimeDir = $RuntimeDirInput
}

Write-Host "Stopping preview node if it is running..."
try {
    $StopOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ScriptDir "stop.ps1") "--runtime-dir=$RuntimeDir" 2>&1
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
    "lists",
    "qortium-backup",
    "qortium-backup-preview",
    "settings-preview-local.json",
    "settings-preview-local.template.json",
    "settings-preview-seed-local.json",
    "settings-preview-seed-local.template.json",
    "settings-preview-seed-netcup-local.json",
    "settings-preview-seed-netcup-local.template.json",
    "run.log",
    "run-error.log",
    "run.pid",
    "qortium.log",
    "QortiumKeyStore.jks",
    "apikey.txt"
) | ForEach-Object { Join-Path $RuntimeDir $_ }

# Also clear any legacy lists left in the install dir by older preview builds.
$Paths += (Join-Path $ScriptDir "lists")

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
Write-Host "  preview\start.bat --runtime-dir=$RuntimeDir"
