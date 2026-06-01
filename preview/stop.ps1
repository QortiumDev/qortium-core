$ErrorActionPreference = "Stop"

$ApiPort = 24891
$StopTimeout = 120
$WindowCloseTimeout = 15

function Show-Usage {
    Write-Host "Usage: preview\stop.bat [--api-port=PORT] [--timeout=SECONDS]"
}

foreach ($Arg in $args) {
    if ($Arg -like "--api-port=*") {
        $ApiPort = [int]$Arg.Substring("--api-port=".Length)
    } elseif ($Arg -like "--timeout=*") {
        $StopTimeout = [int]$Arg.Substring("--timeout=".Length)
    } elseif ($Arg -eq "-h" -or $Arg -eq "--help") {
        Show-Usage
        exit 0
    } else {
        Write-Host "Unknown option: $Arg"
        Show-Usage
        exit 1
    }
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RunPid = Join-Path $ScriptDir "run.pid"
$ApiKeyFile = Join-Path $ScriptDir "apikey.txt"
$NodePid = $null
$StalePid = $false

if (Test-Path -LiteralPath $RunPid -PathType Leaf) {
    $NodePidText = (Get-Content -LiteralPath $RunPid -Raw).Trim()
    if ($NodePidText -match '^\d+$') {
        $NodePid = [int]$NodePidText
        if ($null -eq (Get-Process -Id $NodePid -ErrorAction SilentlyContinue)) {
            $NodePid = $null
            $StalePid = $true
        }
    }
}

if ($null -eq $NodePid) {
    $Process = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match "qortium" -and $_.CommandLine -match "settings-preview-.*local\.json" } |
        Select-Object -First 1
    if ($null -ne $Process) {
        $NodePid = [int]$Process.ProcessId
    }
}

$ApiKey = ""
if (Test-Path -LiteralPath $ApiKeyFile -PathType Leaf) {
    $ApiKey = (Get-Content -LiteralPath $ApiKeyFile -Raw).Trim()
}

$Success = $false
if (-not [string]::IsNullOrWhiteSpace($ApiKey)) {
    Write-Host "Stopping Qortium preview node via API..."
    try {
        Invoke-WebRequest -UseBasicParsing `
            -Method Post `
            -Uri "http://localhost:$ApiPort/admin/stop" `
            -Headers @{ "X-API-KEY" = $ApiKey } `
            -TimeoutSec 10 | Out-Null
        $Success = $true
    } catch {
        $Success = $false
    }
}

if (-not $Success -and $null -ne $NodePid) {
    Write-Host "Stopping Qortium preview process $NodePid..."
    try {
        Stop-Process -Id $NodePid -ErrorAction Stop
        $Success = $true
    } catch {
        $Success = $false
    }
}

if (-not $Success) {
    if ($StalePid) {
        Remove-Item -LiteralPath $RunPid -Force -ErrorAction SilentlyContinue
        Write-Host "Qortium preview node is not running; removed stale pid file"
        exit 0
    }

    Write-Host "Stop command failed - preview node is not running."
    exit 1
}

if ($null -ne $NodePid) {
    Write-Host -NoNewline "Waiting for Qortium preview node to stop"
    $Deadline = (Get-Date).AddSeconds($StopTimeout)
    while ($null -ne (Get-Process -Id $NodePid -ErrorAction SilentlyContinue)) {
        if ((Get-Date) -ge $Deadline) {
            Write-Host ""
            Write-Host "Preview node did not stop within ${StopTimeout}s; asking process $NodePid to close."
            try {
                $Process = Get-Process -Id $NodePid -ErrorAction Stop
                if ($Process.CloseMainWindow()) {
                    $Process.WaitForExit($WindowCloseTimeout * 1000) | Out-Null
                }
            } catch {
            }

            if ($null -ne (Get-Process -Id $NodePid -ErrorAction SilentlyContinue)) {
                Write-Host "Preview node still running after ${WindowCloseTimeout}s; forcing process $NodePid to exit."
                Stop-Process -Id $NodePid -Force -ErrorAction SilentlyContinue
            }
            break
        }

        Write-Host -NoNewline "."
        Start-Sleep -Seconds 1
    }
    Write-Host ""
}

Remove-Item -LiteralPath $RunPid -Force -ErrorAction SilentlyContinue
Write-Host "Qortium preview node stopped"
