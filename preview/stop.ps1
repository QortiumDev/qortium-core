$ErrorActionPreference = "Stop"

$ApiPort = 24891
$StopTimeout = 45

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
$Pid = $null
$StalePid = $false

if (Test-Path -LiteralPath $RunPid -PathType Leaf) {
    $PidText = (Get-Content -LiteralPath $RunPid -Raw).Trim()
    if ($PidText -match '^\d+$') {
        $Pid = [int]$PidText
        if ($null -eq (Get-Process -Id $Pid -ErrorAction SilentlyContinue)) {
            $Pid = $null
            $StalePid = $true
        }
    }
}

if ($null -eq $Pid) {
    $Process = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match "qortium" -and $_.CommandLine -match "settings-preview-.*local\.json" } |
        Select-Object -First 1
    if ($null -ne $Process) {
        $Pid = [int]$Process.ProcessId
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

if (-not $Success -and $null -ne $Pid) {
    Write-Host "Stopping Qortium preview process $Pid..."
    try {
        Stop-Process -Id $Pid -ErrorAction Stop
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

if ($null -ne $Pid) {
    Write-Host -NoNewline "Waiting for Qortium preview node to stop"
    $Deadline = (Get-Date).AddSeconds($StopTimeout)
    while ($null -ne (Get-Process -Id $Pid -ErrorAction SilentlyContinue)) {
        if ((Get-Date) -ge $Deadline) {
            Write-Host ""
            Write-Host "Preview node did not stop within ${StopTimeout}s; forcing process $Pid to exit."
            Stop-Process -Id $Pid -Force -ErrorAction SilentlyContinue
            break
        }

        Write-Host -NoNewline "."
        Start-Sleep -Seconds 1
    }
    Write-Host ""
}

Remove-Item -LiteralPath $RunPid -Force -ErrorAction SilentlyContinue
Write-Host "Qortium preview node stopped"
