# cvector — Windows install

Build and install cvector on Windows 11 in one step.

## Prerequisites

- **JDK 17+** with `jpackage` on PATH (any modern Temurin / Oracle / Microsoft build works — JDK 25 is fine)
- **Maven 3.9+**
- **PowerShell 5.1+** (ships with Windows 11)

You can check both with:

```powershell
java -version
mvn -version
```

## Install

From the repository root:

```powershell
.\install\windows\install.ps1
```

The script will:

1. Run `mvn -Pdist install -DskipTests` to build the bundled `cvector.exe` + trimmed JRE.
2. Copy the dist to `%LOCALAPPDATA%\Programs\cvector\` (overwriting any prior install).
3. Add that directory to your **user** PATH (no admin rights needed).
4. Create `%USERPROFILE%\.cvector\settings.json` with sensible defaults — `backend: "embedded"`, a placeholder `default` project rooted at `%USERPROFILE%` (only if neither `settings.json` nor a legacy `project.json` already exists).

When it finishes, open a **new** terminal so PATH changes take effect, then:

```powershell
cvector --help
cvector scan --help
cvector embedded query --help
```

## Override install location

```powershell
$env:CVECTOR_INSTALL_DIR = "D:\tools\cvector"
.\install\windows\install.ps1
```

Or pass it explicitly:

```powershell
.\install\windows\install.ps1 -InstallDir "D:\tools\cvector"
```

## Execution policy errors

If PowerShell refuses to run the script:

```
.\install.ps1 : File ... cannot be loaded because running scripts is disabled on this system.
```

Run it through PowerShell with a one-shot bypass:

```powershell
powershell -ExecutionPolicy Bypass -File .\install\windows\install.ps1
```

## Uninstall

```powershell
Remove-Item -Recurse -Force "$env:LOCALAPPDATA\Programs\cvector"
# Then drop the install dir entry from %Path% in `sysdm.cpl` → Advanced → Environment Variables → User.
# Or: [Environment]::SetEnvironmentVariable("Path",
#       (([Environment]::GetEnvironmentVariable("Path","User") -split ';' |
#         Where-Object { $_ -notlike "*\cvector*" }) -join ';'),
#       "User")
```

`%USERPROFILE%\.cvector\` is left in place — remove it manually if you also want to discard your config.
