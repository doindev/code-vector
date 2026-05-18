# cvector — installers

One-step build + install scripts for each supported OS. They build the cvector distributable from source (`mvn -Pdist install`), copy it to a user-scoped location, add the binary to your PATH, and seed `~/.cvector/` with a default `settings.json` (`backend: "embedded"` so cvector runs without Neo4j out of the box). A legacy `project.json` from older installs is left in place — both filenames are read.

Pick your platform:

| OS | Script | Default install dir |
| --- | --- | --- |
| Windows 11 | [`windows/install.ps1`](windows/README.md) | `%LOCALAPPDATA%\Programs\cvector\` |
| macOS | [`macos/install.sh`](macos/README.md) | `~/.local/share/cvector/` |
| Linux | [`linux/install.sh`](linux/README.md) | `~/.local/share/cvector/` |

Re-run the script any time to refresh the install — it overwrites the existing copy and leaves PATH and `~/.cvector/` untouched if already set up.

See the OS-specific README for prerequisites, override variables, and uninstall steps.
