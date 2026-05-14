# install.ps1 -- build + install cvector on Windows.
#
# Usage:  .\install\windows\install.ps1
# Override install location: $env:CVECTOR_INSTALL_DIR = "..."; .\install.ps1
# Skip the Angular dashboard SPA: $env:CVECTOR_SKIP_DASHBOARD = "1"; .\install.ps1
#
# Builds the cvector .exe distributable via
#   `mvn -Pdist,dashboard-ui install -DskipTests`
# and copies it under %LOCALAPPDATA%\Programs\cvector by default. The dashboard-ui
# profile bundles the Angular SPA inside the .exe so /dashboard/ serves the full UI;
# set CVECTOR_SKIP_DASHBOARD=1 to skip it (REST endpoints still work, just no SPA).
#
# After build the script:
#   - copies the dist tree to $InstallDir,
#   - optionally adds that directory to the user PATH (per-user, no admin needed),
#   - seeds a default %USERPROFILE%\.cvector\settings.json (with backend = embedded
#     so the .exe works out of the box, no Neo4j or Docker required).
#
# Re-running this script:
#   - asks to stop any running cvector.exe first
#   - overwrites the installed files in place
#   - only adds to PATH if you confirm (and the entry is not already there)
#   - only overwrites settings.json if you confirm
#
# Windows Defender note: on rebuilds, Defender's real-time scan can hold a transient
# handle on the previously-built cvector.exe and cause "Unable to delete" failures
# during the Maven clean step. The cleanest fix is a one-time exclusion: Settings ->
# Windows Security -> Virus & threat protection -> Manage settings -> Exclusions ->
# Add an exclusion -> Folder -> pick this repo's cvector-app\target. Without that,
# the first run usually works (nothing pre-existing to lock); only iterative re-runs
# hit the issue.

[CmdletBinding()]
param(
    [string]$InstallDir = $(if ($env:CVECTOR_INSTALL_DIR) { $env:CVECTOR_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA "Programs\cvector" })
)

$ErrorActionPreference = "Stop"

# Repo root is two levels up from this script (install/windows/install.ps1 -> repo).
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Write-Host "repo root:   $repoRoot"
Write-Host "install dir: $InstallDir"

# Helper: read a y/N answer with a default. Empty input returns the default,
# which keeps non-interactive runs safe (no surprise mutations).
function Read-YesNo {
    param(
        [Parameter(Mandatory)] [string]$Prompt,
        [ValidateSet('y','n')] [string]$Default = 'n'
    )
    $hint = if ($Default -eq 'y') { '[Y/n]' } else { '[y/N]' }
    $ans = Read-Host "$Prompt $hint"
    if ([string]::IsNullOrWhiteSpace($ans)) { return ($Default -eq 'y') }
    return ($ans -match '^[Yy]')
}

# 1. Pre-flight: stop any running cvector.exe so we can overwrite the binary.
$running = Get-Process -Name cvector -ErrorAction SilentlyContinue
if ($running) {
    Write-Host ""
    $pids = ($running.Id -join ', ')
    Write-Host "cvector.exe is running (PIDs: $pids)"
    if (Read-YesNo -Prompt "Stop them before installing?" -Default 'y') {
        foreach ($p in $running) {
            # Try graceful close first, then escalate. CloseMainWindow is a no-op for
            # console apps, so we wait briefly then force.
            try { [void]$p.CloseMainWindow() } catch { }
            if (-not $p.WaitForExit(3000)) {
                try { Stop-Process -Id $p.Id -Force -ErrorAction Stop } catch {
                    Write-Host "  could not stop PID $($p.Id): $($_.Exception.Message)"
                }
            }
        }
        Write-Host "stopped running cvector processes"
    } else {
        Write-Host "aborting install -- cannot overwrite a running binary"
        exit 1
    }
}

# 2. Build the distributable. Activate the dashboard-ui profile unless explicitly
# disabled — without it the Angular SPA is dropped and /dashboard/ returns 404,
# which is rarely what a fresh installer wants.
$profiles = if ($env:CVECTOR_SKIP_DASHBOARD) { "dist" } else { "dist,dashboard-ui" }
Push-Location $repoRoot
try {
    Write-Host ""
    Write-Host ">> mvn -P$profiles install -DskipTests"
    & mvn "-P$profiles" install "-DskipTests"
    if ($LASTEXITCODE -ne 0) { throw "maven build failed (exit $LASTEXITCODE)" }
}
finally {
    Pop-Location
}

$dist = Join-Path $repoRoot "cvector-app\target\dist\cvector"
if (-not (Test-Path $dist)) {
    throw "expected dist folder missing: $dist"
}

# 3. Wipe any prior install and copy the fresh dist.
if (Test-Path $InstallDir) {
    Write-Host ""
    Write-Host "removing existing install at $InstallDir"
    Remove-Item -Recurse -Force $InstallDir
}
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
Copy-Item -Recurse -Force -Path (Join-Path $dist '*') -Destination $InstallDir
Write-Host "copied dist -> $InstallDir"

# 4. Offer to add InstallDir to the user PATH (only when it is not already there).
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$onPath = $false
if ($userPath) {
    foreach ($entry in $userPath -split ';') {
        if ($entry.TrimEnd('\') -ieq $InstallDir.TrimEnd('\')) { $onPath = $true; break }
    }
}
if (-not $onPath) {
    Write-Host ""
    Write-Host "$InstallDir is not on your user PATH."
    if (Read-YesNo -Prompt "Add it now so you can run 'cvector' from any terminal?" -Default 'n') {
        $newPath = if ($userPath) { "$userPath;$InstallDir" } else { $InstallDir }
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        Write-Host "added $InstallDir to user PATH (open a new shell to pick up)"
    } else {
        Write-Host "skipped PATH update -- run cvector via its full path:"
        Write-Host "  $InstallDir\cvector.exe --help"
    }
} else {
    Write-Host "$InstallDir already on user PATH"
}

# 5. Create %USERPROFILE%\.cvector (only if absent) and optionally seed settings.json.
# This is the "home fallback" config the loader walks up to when cwd doesn't have a
# project-local .cvector/. Seeding it here means `cvector status`/`cvector dashboard`
# work from any drive or directory the user happens to be in after install.
$userCvector = Join-Path $env:USERPROFILE ".cvector"
$settingsJson = Join-Path $userCvector "settings.json"
# Legacy filename — still read by older binaries. We never write it; just check.
$legacyProjectJson = Join-Path $userCvector "project.json"

if (Test-Path $userCvector) {
    Write-Host "$userCvector already exists -- not modifying the directory itself"
} else {
    New-Item -ItemType Directory -Path $userCvector | Out-Null
    Write-Host "created $userCvector"
}

function Write-DefaultSettingsJson {
    param([string]$Path)
    $uuid = [guid]::NewGuid().ToString()
    $rootEscaped = $env:USERPROFILE -replace '\\','\\'
    # `backend = embedded` is the default but stating it makes the file self-documenting.
    # The neo4j / rest / mcp / docker sections are omitted entirely so they pick up the
    # config-record defaults (loopback bind, port 2969, MCP at /mcp, etc.).
    $json = @"
{
  "activeProject": "default",
  "projects": {
    "default": {
      "projectId": "$uuid",
      "name": "default",
      "rootPath": "$rootEscaped"
    }
  },
  "backend": "embedded"
}
"@
    [System.IO.File]::WriteAllText($Path, $json)
}

if (-not (Test-Path $settingsJson) -and -not (Test-Path $legacyProjectJson)) {
    Write-DefaultSettingsJson -Path $settingsJson
    Write-Host "wrote default $settingsJson"
} elseif (Test-Path $settingsJson) {
    Write-Host ""
    Write-Host "$settingsJson already exists."
    if (Read-YesNo -Prompt "Overwrite with the installer defaults (backend=embedded)?" -Default 'n') {
        Write-DefaultSettingsJson -Path $settingsJson
        Write-Host "overwrote $settingsJson with defaults"
    } else {
        Write-Host "kept existing $settingsJson"
    }
} else {
    # Only the legacy project.json exists. Leave it alone — the loader still reads it,
    # and the user can `cvector db` / REST PUT to migrate when ready.
    Write-Host "found legacy $legacyProjectJson (still readable; canonical name is settings.json)"
}

Write-Host ""
Write-Host "done. Open a NEW terminal and run:  cvector --help"
