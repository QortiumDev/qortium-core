$ErrorActionPreference = "Stop"

$MinJavaVersion = 17
$Mode = "participant"
$HeadlessMode = "auto"

function Show-Usage {
    Write-Host "Usage: preview\start.bat [--seed|--seed-regxa|--seed-netcup|--participant] [--headless|--gui]"
    Write-Host ""
    Write-Host "Starts a Qortium preview-network node."
    Write-Host "  --participant  connect to the preview seeds at 146.103.42.59 and 185.207.104.78 (default)"
    Write-Host "  --seed         use the Regxa seed settings"
    Write-Host "  --seed-regxa   advertise the Regxa seed IP 146.103.42.59"
    Write-Host "  --seed-netcup  advertise the Netcup seed IP 185.207.104.78"
    Write-Host "  --headless     force Java headless mode"
    Write-Host "  --gui          force Java GUI mode"
}

foreach ($Arg in $args) {
    switch ($Arg) {
        "--seed" { $Mode = "seed-regxa"; continue }
        "--seed-regxa" { $Mode = "seed-regxa"; continue }
        "--seed-netcup" { $Mode = "seed-netcup"; continue }
        "--participant" { $Mode = "participant"; continue }
        "--headless" { $HeadlessMode = "true"; continue }
        "--gui" { $HeadlessMode = "false"; continue }
        "-h" { Show-Usage; exit 0 }
        "--help" { Show-Usage; exit 0 }
        default {
            Write-Host "Unknown option: $Arg"
            Show-Usage
            exit 1
        }
    }
}

function Get-JavaMajorVersion {
    $VersionOutput = (& java -version) 2>&1 | ForEach-Object { $_.ToString() }
    $VersionLine = $VersionOutput | Where-Object { $_ -match "version" } | Select-Object -First 1
    if ($VersionLine -notmatch '"([^"]+)"') {
        return $null
    }

    $Version = $Matches[1]
    $FirstPart = ($Version -split "\.")[0]
    if ($FirstPart -eq "1") {
        return [int](($Version -split "\.")[1])
    }

    return [int]$FirstPart
}

function Find-QortiumJar {
    $Candidates = @(
        (Join-Path $ScriptDir "qortium.jar"),
        (Join-Path $RepoDir "qortium.jar")
    )

    foreach ($Candidate in $Candidates) {
        if (Test-Path -LiteralPath $Candidate -PathType Leaf) {
            return $Candidate
        }
    }

    $TargetDir = Join-Path $RepoDir "target"
    $TargetJar = Get-ChildItem -LiteralPath $TargetDir -Filter "qortium*.jar" -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -ne $TargetJar) {
        return $TargetJar.FullName
    }

    return $null
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoDir = Split-Path -Parent $ScriptDir
$RunLog = Join-Path $ScriptDir "run.log"
$RunErrorLog = Join-Path $ScriptDir "run-error.log"
$RunPid = Join-Path $ScriptDir "run.pid"
$AppLog = Join-Path $ScriptDir "qortium.log"
$Log4jConfig = Join-Path $ScriptDir "log4j2.properties"

if ($Mode -eq "seed-regxa") {
    $SettingsTemplate = Join-Path $ScriptDir "settings-preview-seed.json"
    $SettingsLocal = Join-Path $ScriptDir "settings-preview-seed-local.json"
} elseif ($Mode -eq "seed-netcup") {
    $SettingsTemplate = Join-Path $ScriptDir "settings-preview-seed-netcup.json"
    $SettingsLocal = Join-Path $ScriptDir "settings-preview-seed-netcup-local.json"
} else {
    $SettingsTemplate = Join-Path $ScriptDir "settings-preview.json"
    $SettingsLocal = Join-Path $ScriptDir "settings-preview-local.json"
}

if (Test-Path -LiteralPath $RunPid -PathType Leaf) {
    $ExistingPid = (Get-Content -LiteralPath $RunPid -Raw).Trim()
    if ($ExistingPid -match '^\d+$') {
        $ExistingProcess = Get-Process -Id ([int]$ExistingPid) -ErrorAction SilentlyContinue
        if ($null -ne $ExistingProcess) {
            Write-Host "Qortium preview node is already running as pid $ExistingPid"
            Write-Host "Console log: $RunLog"
            exit 0
        }
    }

    Remove-Item -LiteralPath $RunPid -Force
}

if ($null -eq (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host "Java is not available. Please install Java $MinJavaVersion or greater."
    exit 1
}

$JavaMajorVersion = Get-JavaMajorVersion
if ($null -eq $JavaMajorVersion -or $JavaMajorVersion -lt $MinJavaVersion) {
    Write-Host "Please upgrade Java to version $MinJavaVersion or greater."
    exit 1
}

$JarPath = Find-QortiumJar
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    Write-Host "Could not find qortium.jar."
    Write-Host "For the release zip, make sure qortium.jar is in the extracted folder or preview folder."
    Write-Host "For a source checkout, build it first with: .\build.sh --yes"
    exit 1
}

Copy-Item -LiteralPath $SettingsTemplate -Destination $SettingsLocal -Force

$JvmMemoryArgString = $env:QORTIUM_PREVIEW_JVM_MEMORY_ARGS
if ([string]::IsNullOrWhiteSpace($JvmMemoryArgString)) {
    $JvmMemoryArgString = "-XX:MaxRAMPercentage=50 -XX:+UseG1GC -Xss1024k"
}
$JvmMemoryArgs = $JvmMemoryArgString -split "\s+" | Where-Object { $_ -ne "" }

$JavaDisplayArgs = @()
$DisplayModeDescription = "GUI auto-detected"
switch ($HeadlessMode) {
    "true" {
        $JavaDisplayArgs = @("-Djava.awt.headless=true")
        $DisplayModeDescription = "headless forced"
    }
    "false" {
        $JavaDisplayArgs = @("-Djava.awt.headless=false")
        $DisplayModeDescription = "GUI forced"
    }
}

$JavaArgs = @(
    "-Djava.net.preferIPv4Stack=false",
    "-Dlog4j.configurationFile=$Log4jConfig",
    "-Dqortium.log.dir=$ScriptDir"
) + $JavaDisplayArgs + $JvmMemoryArgs + @("-jar", $JarPath, $SettingsLocal)
$Process = Start-Process -FilePath "java" `
    -ArgumentList $JavaArgs `
    -WorkingDirectory $ScriptDir `
    -RedirectStandardOutput $RunLog `
    -RedirectStandardError $RunErrorLog `
    -PassThru

Set-Content -LiteralPath $RunPid -Value $Process.Id

Write-Host "Qortium preview $Mode node running as pid $($Process.Id)"
Write-Host "Settings file: $SettingsLocal"
Write-Host "Jar file: $JarPath"
Write-Host "Display mode: $DisplayModeDescription"
Write-Host "Console log: $RunLog"
Write-Host "Error log: $RunErrorLog"
Write-Host "Log4j config: $Log4jConfig"
Write-Host "Application log: $AppLog"
Write-Host ""
Write-Host "Preview genesis and settings are fixed. No minting key was added automatically."
Write-Host "Next commands:"
Write-Host "  preview\status.bat --wait"
Write-Host "  preview\stop.bat"
Write-Host "  preview\reset.bat"
Write-Host "Tester guide: $(Join-Path $ScriptDir 'TESTER-GUIDE.md')"
