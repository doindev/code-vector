# cvector — Linux install

Build and install cvector on Linux (any glibc-based distro: Ubuntu, Debian, Fedora, Arch, …).

## Prerequisites

- **JDK 17+** with `jpackage` on PATH
  - Debian/Ubuntu: `sudo apt install temurin-21-jdk` (after adding the Adoptium repo) or `openjdk-21-jdk-headless` (note: some distros split `jpackage` into a separate `*-jdk` package — pick the **full JDK**, not headless)
  - Fedora: `sudo dnf install java-21-openjdk-devel`
  - Arch: `sudo pacman -S jdk-openjdk`
- **Maven 3.9+**
  - Debian/Ubuntu: `sudo apt install maven`
  - Fedora: `sudo dnf install maven`
- **fakeroot** — required by jpackage to build distributables on Linux:
  - Debian/Ubuntu: `sudo apt install fakeroot`
  - Fedora: `sudo dnf install fakeroot`

Verify with:

```bash
java -version
mvn -version
jpackage --version
```

## Install

From the repository root:

```bash
chmod +x install/linux/install.sh
./install/linux/install.sh
```

The script will:

1. Run `mvn -Pdist install -DskipTests` to produce `cvector-app/target/dist/cvector/` (launcher at `bin/cvector` + bundled JRE under `lib/runtime/`).
2. Copy the tree to `~/.local/share/cvector` (overwriting any prior install).
3. Symlink `~/.local/bin/cvector` to the bundled launcher.
4. Append `export PATH="$HOME/.local/bin:$PATH"` to your shell profile (`~/.bashrc`, `~/.zshrc`, or `~/.profile`) **only if not already present**.
5. Create `~/.cvector/settings.json` with sensible defaults — `backend: "embedded"`, a placeholder `default` project rooted at `$HOME` (only if neither `settings.json` nor a legacy `project.json` already exists).

When it finishes, open a **new** terminal and try:

```bash
cvector --help
cvector scan --help
cvector embedded query --help
```

## Override install location

```bash
CVECTOR_INSTALL_DIR=/opt/cvector sudo ./install/linux/install.sh
CVECTOR_BIN_LINK=/usr/local/bin/cvector sudo ./install/linux/install.sh
```

Note: writing to `/opt` or `/usr/local/bin` requires `sudo`, and the profile-PATH step will edit `root`'s profile in that case — you may want to keep the default (no-sudo) install location and just symlink yourself.

## Uninstall

```bash
rm -rf ~/.local/share/cvector
rm -f ~/.local/bin/cvector
```

Then delete the `# added by cvector installer` block from your shell profile. `~/.cvector/` is left in place — remove it manually if you also want to discard your config.
