# cvector — macOS install

Build and install cvector on macOS (Apple Silicon or Intel).

## Prerequisites

- **JDK 17+** with `jpackage` on PATH (Temurin via Homebrew is easiest: `brew install --cask temurin`)
- **Maven 3.9+** (`brew install maven`)
- **bash 4+** or zsh — the script tolerates either

Verify with:

```bash
java -version
mvn -version
```

## Install

From the repository root:

```bash
chmod +x install/macos/install.sh
./install/macos/install.sh
```

The script will:

1. Run `mvn -Pdist install -DskipTests` to produce `cvector.app` (an app bundle with bundled JRE).
2. Copy the bundle to `~/.local/share/cvector` (overwriting any prior install).
3. Symlink `~/.local/bin/cvector` to the bundled launcher.
4. Append `export PATH="$HOME/.local/bin:$PATH"` to your shell profile (`~/.zshrc` or `~/.bash_profile`) **only if not already present**.
5. Create `~/.cvector/project.json` with sensible defaults (only if missing).

When it finishes, open a **new** terminal and try:

```bash
cvector --help
cvector scan --help
cvector embedded query --help
```

## Override install location

```bash
CVECTOR_INSTALL_DIR=/Applications/cvector.app ./install/macos/install.sh
```

You can also override the symlink path with `CVECTOR_BIN_LINK`:

```bash
CVECTOR_BIN_LINK=/usr/local/bin/cvector sudo ./install/macos/install.sh
```

## Gatekeeper / "cannot be opened because the developer cannot be verified"

The bundled `.app` is unsigned. The first time you run `cvector`, macOS may quarantine it. Either:

1. Right-click the bundle in Finder → **Open** → confirm, **or**
2. Strip the quarantine bit:

```bash
xattr -dr com.apple.quarantine ~/.local/share/cvector
```

## Uninstall

```bash
rm -rf ~/.local/share/cvector
rm -f ~/.local/bin/cvector
```

Then delete the `# added by cvector installer` block from `~/.zshrc` (or `~/.bash_profile`). `~/.cvector/` is left in place — remove it manually if you also want to discard your config.
