#!/usr/bin/env bash
# install.sh -- build + install cvector on Linux.
#
# Usage:  ./install/linux/install.sh
# Override install location: CVECTOR_INSTALL_DIR=/opt/cvector ./install.sh
#
# Builds the cvector dist via `mvn -Pdist install`, copies it under
# ~/.local/share/cvector by default, optionally symlinks the launcher into
# ~/.local/bin and adds that directory to your shell PATH, and stamps out a
# default ~/.cvector/project.json.
#
# Re-running this script:
#   - asks to stop any running cvector first
#   - overwrites the installed files in place
#   - only adds to PATH if you confirm (and the entry is not already there)
#   - only overwrites project.json if you confirm

set -euo pipefail

INSTALL_DIR="${CVECTOR_INSTALL_DIR:-${HOME}/.local/share/cvector}"
BIN_LINK="${CVECTOR_BIN_LINK:-${HOME}/.local/bin/cvector}"

SCRIPT_DIR="$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )"
REPO_ROOT="$( cd -- "${SCRIPT_DIR}/../.." &> /dev/null && pwd )"

echo "repo root:   ${REPO_ROOT}"
echo "install dir: ${INSTALL_DIR}"
echo "bin link:    ${BIN_LINK}"

# Helper: read a y/N answer with a default. Empty input returns the default,
# which keeps non-interactive runs safe (no surprise mutations).
ask_yes_no() {
    local prompt="$1" default="${2:-n}" hint ans
    if [[ "${default}" == "y" ]]; then hint="[Y/n]"; else hint="[y/N]"; fi
    read -r -p "${prompt} ${hint} " ans || ans=""
    if [[ -z "${ans}" ]]; then
        [[ "${default}" == "y" ]]
        return $?
    fi
    [[ "${ans}" =~ ^[Yy]([Ee][Ss])?$ ]]
}

# 1. Pre-flight: stop any running cvector so we can overwrite the binary.
# Match the bundled launcher, the fat jar, or the AOT main class. Exclude this script.
CVECTOR_PATTERN='cvector/bin/cvector|cvector\.jar|io\.doindev\.cvector\.CvectorApplication'
PIDS="$(pgrep -f "${CVECTOR_PATTERN}" 2>/dev/null | grep -v "^$$\$" || true)"
if [[ -n "${PIDS}" ]]; then
    echo ""
    echo "cvector is running (PIDs: $(echo ${PIDS} | tr '\n' ' '))"
    if ask_yes_no "Stop them before installing?" y; then
        for pid in ${PIDS}; do kill -TERM "${pid}" 2>/dev/null || true; done
        for _ in 1 2 3 4 5; do
            sleep 1
            still="$(pgrep -f "${CVECTOR_PATTERN}" 2>/dev/null | grep -v "^$$\$" || true)"
            [[ -z "${still}" ]] && break
        done
        if [[ -n "${still:-}" ]]; then
            echo "graceful exit timed out -- forcing"
            for pid in ${still}; do kill -KILL "${pid}" 2>/dev/null || true; done
        fi
        echo "stopped running cvector processes"
    else
        echo "aborting install -- cannot overwrite a running binary"
        exit 1
    fi
fi

# 2. Build the distributable.
echo ""
echo ">> mvn -Pdist install -DskipTests"
( cd "${REPO_ROOT}" && mvn -Pdist install -DskipTests )

# jpackage on Linux emits a directory tree with bin/<name>, lib/, and runtime/.
DIST="${REPO_ROOT}/cvector-app/target/dist/cvector"
if [[ ! -d "${DIST}" ]]; then
    echo "expected dist folder missing: ${DIST}" >&2
    echo "(if you're on macOS, run install/macos/install.sh instead)" >&2
    exit 1
fi
if [[ ! -x "${DIST}/bin/cvector" ]]; then
    echo "expected launcher missing: ${DIST}/bin/cvector" >&2
    exit 1
fi

# 3. Wipe any prior install and copy the fresh tree.
if [[ -e "${INSTALL_DIR}" ]]; then
    echo ""
    echo "removing existing install at ${INSTALL_DIR}"
    rm -rf "${INSTALL_DIR}"
fi
mkdir -p "$(dirname "${INSTALL_DIR}")"
cp -R "${DIST}" "${INSTALL_DIR}"
echo "installed -> ${INSTALL_DIR}"

# 4. Symlink the launcher into a directory we'll add to PATH.
mkdir -p "$(dirname "${BIN_LINK}")"
ln -sf "${INSTALL_DIR}/bin/cvector" "${BIN_LINK}"
echo "linked ${BIN_LINK} -> ${INSTALL_DIR}/bin/cvector"

# 5. Offer to add the bin link's parent to PATH (only when not already there).
BIN_PARENT="$(dirname "${BIN_LINK}")"
PROFILE=""
case "${SHELL:-}" in
    */zsh) PROFILE="${HOME}/.zshrc" ;;
    */bash) PROFILE="${HOME}/.bashrc" ;;
    *) PROFILE="${HOME}/.profile" ;;
esac
LINE="export PATH=\"${BIN_PARENT}:\$PATH\""
if ! grep -Fqs "${BIN_PARENT}" "${PROFILE}" 2>/dev/null; then
    echo ""
    echo "${BIN_PARENT} is not on your PATH (per ${PROFILE})."
    if ask_yes_no "Add it now so you can run 'cvector' from any shell?" n; then
        {
            echo ""
            echo "# added by cvector installer"
            echo "${LINE}"
        } >> "${PROFILE}"
        echo "added ${BIN_PARENT} to PATH in ${PROFILE} (open a new shell to pick up)"
    else
        echo "skipped PATH update -- run cvector via its full path:"
        echo "  ${BIN_LINK} --help"
    fi
else
    echo "${BIN_PARENT} already on PATH in ${PROFILE}"
fi

# 6. Create ~/.cvector (only if absent) and optionally seed project.json.
USER_CVECTOR="${HOME}/.cvector"
PROJECT_JSON="${USER_CVECTOR}/project.json"
if [[ -d "${USER_CVECTOR}" ]]; then
    echo "${USER_CVECTOR} already exists -- not modifying the directory itself"
else
    mkdir -p "${USER_CVECTOR}"
    echo "created ${USER_CVECTOR}"
fi

write_default_project_json() {
    local target="$1" uuid
    if command -v uuidgen >/dev/null 2>&1; then
        uuid="$(uuidgen)"
    elif [[ -r /proc/sys/kernel/random/uuid ]]; then
        uuid="$(cat /proc/sys/kernel/random/uuid)"
    else
        uuid="00000000-0000-0000-0000-000000000000"
    fi
    cat > "${target}" <<EOF
{
  "activeProject": "default",
  "projects": {
    "default": {
      "projectId": "${uuid}",
      "name": "default",
      "rootPath": "${HOME}"
    }
  },
  "neo4j": {
    "uri": "bolt://localhost:7687",
    "user": "neo4j",
    "password": "neo4j"
  }
}
EOF
}

if [[ ! -f "${PROJECT_JSON}" ]]; then
    write_default_project_json "${PROJECT_JSON}"
    echo "wrote default ${PROJECT_JSON}"
else
    echo ""
    echo "${PROJECT_JSON} already exists."
    if ask_yes_no "Overwrite with the installer defaults?" n; then
        write_default_project_json "${PROJECT_JSON}"
        echo "overwrote ${PROJECT_JSON} with defaults"
    else
        echo "kept existing ${PROJECT_JSON}"
    fi
fi

echo ""
echo "done. Open a NEW terminal and run:  cvector --help"
