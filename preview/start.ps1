$ErrorActionPreference = "Stop"

$MinJavaVersion = 17
$Mode = "participant"
$HeadlessMode = "auto"
$RuntimeDirOption = ""

function Show-Usage {
    Write-Host "Usage: preview\start.bat [--seed|--seed-regxa|--seed-netcup|--participant] [--headless|--gui] [--runtime-dir=PATH]"
    Write-Host ""
    Write-Host "Starts a Qortium preview-network node."
    Write-Host "  --participant  connect to the preview seeds at 146.103.42.59 and 185.207.104.78 (default)"
    Write-Host "  --seed         use the Regxa seed settings"
    Write-Host "  --seed-regxa   advertise the Regxa seed IP 146.103.42.59"
    Write-Host "  --seed-netcup  advertise the Netcup seed IP 185.207.104.78"
    Write-Host "  --headless     force Java headless mode"
    Write-Host "  --gui          force Java GUI mode"
    Write-Host "  --runtime-dir  store generated settings, DB, QDN data, logs, pid, and API key under PATH"
    Write-Host ""
    Write-Host "QORTIUM_PREVIEW_RUNTIME_DIR can also set the runtime directory."
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
            if ($Arg -like "--runtime-dir=*") {
                $RuntimeDirOption = $Arg.Substring("--runtime-dir=".Length)
                continue
            }

            Write-Host "Unknown option: $Arg"
            Show-Usage
            exit 1
        }
    }
}

function Get-JavaMajorVersion {
    $JavaVersionStartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $JavaVersionStartInfo.FileName = "java"
    $JavaVersionStartInfo.Arguments = "-version"
    $JavaVersionStartInfo.UseShellExecute = $false
    $JavaVersionStartInfo.RedirectStandardOutput = $true
    $JavaVersionStartInfo.RedirectStandardError = $true

    $JavaVersionProcess = [System.Diagnostics.Process]::new()
    $JavaVersionProcess.StartInfo = $JavaVersionStartInfo

    try {
        $null = $JavaVersionProcess.Start()
        $VersionOutput = @(
            $JavaVersionProcess.StandardOutput.ReadToEnd()
            $JavaVersionProcess.StandardError.ReadToEnd()
        ) -split "`r?`n" | Where-Object { $_ -ne "" }
        $JavaVersionProcess.WaitForExit()
    } finally {
        $JavaVersionProcess.Dispose()
    }

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

function Get-AutoUpdateMode {
    param([string]$SettingsPath)

    if (-not (Test-Path -LiteralPath $SettingsPath -PathType Leaf)) {
        return $null
    }

    try {
        $Settings = Get-Content -LiteralPath $SettingsPath -Raw | ConvertFrom-Json
        return $Settings.autoUpdateMode
    } catch {
        return $null
    }
}

function Set-AutoUpdateMode {
    param(
        [string]$SettingsPath,
        [string]$Mode
    )

    if ([string]::IsNullOrWhiteSpace($Mode)) {
        return
    }

    $Mode = $Mode.ToUpperInvariant()
    if (@("OFF", "CHECK_ONLY", "NOTIFY", "INSTALL") -notcontains $Mode) {
        Write-Host "Ignoring invalid local autoUpdateMode: $Mode"
        return
    }

    $Settings = Get-Content -LiteralPath $SettingsPath -Raw | ConvertFrom-Json
    $Settings.autoUpdateMode = $Mode
    $Settings | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $SettingsPath
}

function Resolve-RuntimeDir {
    param([string]$RuntimeDir)

    if ([string]::IsNullOrWhiteSpace($RuntimeDir)) {
        $RuntimeDir = $ScriptDir
    }

    New-Item -ItemType Directory -Force -Path $RuntimeDir | Out-Null
    return (Resolve-Path -LiteralPath $RuntimeDir).Path
}

function Set-RuntimeSettingPaths {
    param([string]$SettingsPath)

    $Settings = Get-Content -LiteralPath $SettingsPath -Raw | ConvertFrom-Json
    $Settings.repositoryPath = Join-Path $RuntimeDir "db-preview"
    $Settings.exportPath = Join-Path $RuntimeDir "qortium-backup-preview"
    $Settings.dataPath = Join-Path $RuntimeDir "data-preview"

    if ($Settings.PSObject.Properties.Name -contains "apiKeyPath") {
        $Settings.apiKeyPath = $RuntimeDir
    } else {
        $Settings | Add-Member -NotePropertyName "apiKeyPath" -NotePropertyValue $RuntimeDir
    }

    $Settings | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $SettingsPath
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoDir = Split-Path -Parent $ScriptDir
$RuntimeDirInput = $RuntimeDirOption
if ([string]::IsNullOrWhiteSpace($RuntimeDirInput)) {
    $RuntimeDirInput = $env:QORTIUM_PREVIEW_RUNTIME_DIR
}
$RuntimeDir = Resolve-RuntimeDir -RuntimeDir $RuntimeDirInput
$RunLog = Join-Path $RuntimeDir "run.log"
$RunErrorLog = Join-Path $RuntimeDir "run-error.log"
$RunPid = Join-Path $RuntimeDir "run.pid"
$AppLog = Join-Path $RuntimeDir "qortium.log"
$Log4jConfig = Join-Path $ScriptDir "log4j2.properties"

if ($Mode -eq "seed-regxa") {
    $SettingsTemplate = Join-Path $ScriptDir "settings-preview-seed.json"
    $SettingsLocal = Join-Path $RuntimeDir "settings-preview-seed-local.json"
} elseif ($Mode -eq "seed-netcup") {
    $SettingsTemplate = Join-Path $ScriptDir "settings-preview-seed-netcup.json"
    $SettingsLocal = Join-Path $RuntimeDir "settings-preview-seed-netcup-local.json"
} else {
    $SettingsTemplate = Join-Path $ScriptDir "settings-preview.json"
    $SettingsLocal = Join-Path $RuntimeDir "settings-preview-local.json"
}
$SettingsTemplateSnapshot = $SettingsLocal -replace '\.json$', '.template.json'

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

& java -cp $JarPath org.qortium.MergeSettings $SettingsTemplate $SettingsTemplateSnapshot $SettingsLocal
if ($LASTEXITCODE -ne 0) {
    Write-Host "Could not merge $SettingsLocal with the release settings template."
    Write-Host "Fix the JSON in that file, or delete it to start again from the template."
    exit 1
}
Set-RuntimeSettingPaths -SettingsPath $SettingsLocal
Set-AutoUpdateMode -SettingsPath $SettingsLocal -Mode $env:QORTIUM_PREVIEW_AUTO_UPDATE_MODE
$AutoUpdateModeEffective = Get-AutoUpdateMode -SettingsPath $SettingsLocal

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
    "-Dqortium.log.dir=$RuntimeDir",
    "-Dqortium.pid.file=$RunPid"
) + $JavaDisplayArgs + $JvmMemoryArgs + @("-jar", $JarPath, $SettingsLocal)
$StartProcessArgs = @{
    FilePath = "java"
    ArgumentList = $JavaArgs
    WorkingDirectory = $ScriptDir
    RedirectStandardOutput = $RunLog
    RedirectStandardError = $RunErrorLog
    PassThru = $true
}

if ($IsWindows -or $PSVersionTable.PSEdition -eq "Desktop") {
    $StartProcessArgs.WindowStyle = "Hidden"
}

$Process = Start-Process @StartProcessArgs

Set-Content -LiteralPath $RunPid -Value $Process.Id

Write-Host "Qortium preview $Mode node running as pid $($Process.Id)"
Write-Host "Runtime directory: $RuntimeDir"
Write-Host "Settings file: $SettingsLocal"
Write-Host "Jar file: $JarPath"
Write-Host "Display mode: $DisplayModeDescription"
Write-Host "Auto-update mode: $AutoUpdateModeEffective"
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
