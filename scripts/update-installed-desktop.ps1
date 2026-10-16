param(
    [string]$InstallDirectory = 'D:\dev_program\LLMPoroxyUI-v1.0.0-Windows-amd64',
    [switch]$BackendOnly
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$installPath = (Resolve-Path -LiteralPath $InstallDirectory).Path
$installedExe = Join-Path $installPath 'LLMPoroxyUI.exe'
$builtExe = Join-Path $projectDirectory 'frontend\src-tauri\target\release\cpa-gui.exe'
$installedJar = Join-Path $installPath 'cpa-core\java-llm-proxy.jar'
$builtJar = Join-Path $projectDirectory 'target\java-llm-proxy-1.0.0.jar'

if (-not (Test-Path -LiteralPath $installedExe -PathType Leaf)) {
    throw "Installed desktop executable not found: $installedExe"
}
if (-not (Test-Path -LiteralPath $installedJar -PathType Leaf)) {
    throw "Installed Java kernel not found: $installedJar"
}

function Assert-DesktopClosed {
    $running = Get-Process -Name LLMPoroxyUI -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $installedExe }
    if ($running) {
        throw 'Exit LLM Poroxy UI from its tray menu, then run this script again.'
    }
    $kernel = Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($installedJar, [StringComparison]::OrdinalIgnoreCase) -ge 0 }
    if ($kernel) {
        throw 'The installed Java kernel is still running. Stop it before updating.'
    }
}

Assert-DesktopClosed
Push-Location $projectDirectory
try {
    & mvn.cmd package '-DskipTests' '-Dgpg.skip' -Pproduction
    if ($LASTEXITCODE -ne 0) {
        throw 'Java build failed. The installed application has not been changed.'
    }
    if (-not $BackendOnly) {
        Set-Location (Join-Path $projectDirectory 'frontend')
        & npm.cmd run tauri -- build --no-bundle
        if ($LASTEXITCODE -ne 0) {
            throw 'Desktop build failed. The installed application has not been changed.'
        }
    }
} finally {
    Pop-Location
}

Assert-DesktopClosed
$updates = @(@{ Source = $builtJar; Destination = $installedJar })
if (-not $BackendOnly) {
    $updates += @{ Source = $builtExe; Destination = $installedExe }
}
# Keep the existing offline recovery archive in sync with the installed kernel.
$installedArchive = Join-Path $installPath 'cpa-core\java-llm-proxy_1.0.0_windows_amd64.zip'
if (Test-Path -LiteralPath $installedArchive -PathType Leaf) {
    $stage = Join-Path $projectDirectory ('target\installed-update-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $stage | Out-Null
    $stagedJar = Join-Path $stage 'java-llm-proxy.jar'
    $stagedArchive = Join-Path $stage 'kernel.zip'
    Copy-Item -LiteralPath $builtJar -Destination $stagedJar
    Compress-Archive -LiteralPath $stagedJar -DestinationPath $stagedArchive
    $updates += @{ Source = $stagedArchive; Destination = $installedArchive }
}
$backupSuffix = [Guid]::NewGuid().ToString('N') + '.bak'
foreach ($update in $updates) {
    $update.Hash = (Get-FileHash -LiteralPath $update.Source -Algorithm SHA256).Hash
    $update.Backup = "$($update.Destination).$backupSuffix"
    Copy-Item -LiteralPath $update.Destination -Destination $update.Backup
}
try {
    foreach ($update in $updates) {
        Copy-Item -LiteralPath $update.Source -Destination $update.Destination -Force
        if ((Get-FileHash -LiteralPath $update.Destination -Algorithm SHA256).Hash -ne $update.Hash) {
            throw "Installed file checksum does not match: $($update.Destination)"
        }
    }
} catch {
    foreach ($update in $updates) {
        Copy-Item -LiteralPath $update.Backup -Destination $update.Destination -Force
    }
    throw
}
foreach ($update in $updates) {
    Write-Output "Updated: $($update.Destination)"
    Write-Output "Backup: $($update.Backup)"
}
Write-Output 'Credentials, configuration and usage records were preserved.'
