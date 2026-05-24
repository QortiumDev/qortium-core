$ErrorActionPreference = "Stop"

$ApiUrl = "http://localhost:62391"
$Wait = $false
$TimeoutSeconds = 120
$SleepSeconds = 2

function Show-Usage {
    Write-Host "Usage: preview\status.bat [--wait] [--api-url=URL]"
    Write-Host ""
    Write-Host "Checks the local preview-node API and prints the current block height."
}

foreach ($Arg in $args) {
    if ($Arg -eq "--wait") {
        $Wait = $true
    } elseif ($Arg -like "--api-url=*") {
        $ApiUrl = $Arg.Substring("--api-url=".Length)
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
$HeightUrl = "$ApiUrl/blocks/height"

function Get-PreviewHeight {
    try {
        $Response = Invoke-WebRequest -UseBasicParsing -Uri $HeightUrl -TimeoutSec 5
        return $Response.Content.Trim()
    } catch {
        return ""
    }
}

function Show-UnreachableHelp {
    Write-Host "The preview API is not reachable yet."
    Write-Host "Start it with:"
    Write-Host "  preview\start.bat"
    Write-Host ""
    Write-Host "If it is already starting, check:"
    Write-Host "  $(Join-Path $ScriptDir 'run.log')"
}

function Show-Height {
    param([int]$Height)

    Write-Host "API is reachable. Current block height: $Height"
    if ($Height -le 1) {
        Write-Host "The preview chain is at genesis. This is expected until preview minting keys are added."
    }
}

if ($Wait) {
    $Deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    Write-Host -NoNewline "Waiting for preview block height"

    while ((Get-Date) -le $Deadline) {
        $Height = Get-PreviewHeight
        if ($Height -match '^\d+$') {
            Write-Host ""
            Show-Height ([int]$Height)
            exit 0
        }

        Write-Host -NoNewline "."
        Start-Sleep -Seconds $SleepSeconds
    }

    Write-Host ""
    Write-Host "Timed out waiting for the preview API."
    Show-UnreachableHelp
    exit 1
}

$Height = Get-PreviewHeight
if ($Height -match '^\d+$') {
    Show-Height ([int]$Height)
    exit 0
}

Show-UnreachableHelp
exit 1
