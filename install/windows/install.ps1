# install.ps1 -- build + install cvector on Windows.
#
# Usage:  .\install\windows\install.ps1
# Override install location: $env:CVECTOR_INSTALL_DIR = "..."; .\install.ps1
#
# Builds the cvector .exe distributable via `mvn -Pdist install`, copies it under
# $env:LOCALAPPDATA\Programs\cvector by default, optionally adds that directory to
# the user PATH, and stamps out a default %USERPROFILE%\.cvector\project.json.
#
# Re-running this script:
#   - asks to stop any running cvector.exe first
#   - overwrites the installed files in place
#   - only adds to PATH if you confirm (and the entry is not already there)
#   - only overwrites project.json if you confirm

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

# 2. Build the distributable.
Push-Location $repoRoot
try {
    Write-Host ""
    Write-Host ">> mvn -Pdist install -DskipTests"
    & mvn "-Pdist" install "-DskipTests"
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

# 5. Create %USERPROFILE%\.cvector (only if absent) and optionally seed project.json.
$userCvector = Join-Path $env:USERPROFILE ".cvector"
$projectJson = Join-Path $userCvector "project.json"
if (Test-Path $userCvector) {
    Write-Host "$userCvector already exists -- not modifying the directory itself"
} else {
    New-Item -ItemType Directory -Path $userCvector | Out-Null
    Write-Host "created $userCvector"
}

function Write-DefaultProjectJson {
    param([string]$Path)
    $uuid = [guid]::NewGuid().ToString()
    $rootEscaped = $env:USERPROFILE -replace '\\','\\'
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
  "neo4j": {
    "uri": "bolt://localhost:7687",
    "user": "neo4j",
    "password": "neo4j"
  }
}
"@
    [System.IO.File]::WriteAllText($Path, $json)
}

if (-not (Test-Path $projectJson)) {
    Write-DefaultProjectJson -Path $projectJson
    Write-Host "wrote default $projectJson"
} else {
    Write-Host ""
    Write-Host "$projectJson already exists."
    if (Read-YesNo -Prompt "Overwrite with the installer defaults?" -Default 'n') {
        Write-DefaultProjectJson -Path $projectJson
        Write-Host "overwrote $projectJson with defaults"
    } else {
        Write-Host "kept existing $projectJson"
    }
}

Write-Host ""
Write-Host "done. Open a NEW terminal and run:  cvector --help"
