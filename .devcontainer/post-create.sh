#!/usr/bin/env bash
# Provisioning run once after the dev container is created (see devcontainer.json).
set -euo pipefail

echo "==> Fixing ownership of the persisted Claude Code config volume"
# The named volume mounted at ~/.claude is root-owned on first creation; hand it to the
# container user so `claude` can write config/credentials there. (vscode has passwordless sudo.)
sudo chown -R "$(id -u):$(id -g)" "$HOME/.claude" || true

echo "==> Ensuring the Gradle wrapper is executable"
# git can drop the executable bit on some checkouts (CI does the same chmod).
chmod +x ./gradlew

echo "==> Ensuring JAVA_HOME resolves to a real JDK"
# devcontainer.json pins JAVA_HOME=/usr/local/sdkman/candidates/java/current so the
# non-login IDEA backend inherits a JDK. Some image builds install the JDK but leave that
# SDKMAN "current" symlink missing/broken, which makes Gradle warn "Directory ... used for
# java installations does not exist". Point the pinned path at a real JDK so it resolves.
JAVA_LINK="/usr/local/sdkman/candidates/java/current"
if [ ! -x "$JAVA_LINK/bin/javac" ]; then
  target=""
  # Prefer a real JDK already installed under SDKMAN's candidates dir.
  for d in /usr/local/sdkman/candidates/java/*/; do
    [ -x "${d}bin/javac" ] || continue
    case "$d" in */current/) continue ;; esac
    target="${d%/}"
    break
  done
  # Fall back to whatever JDK provides `javac` on PATH (Gradle already runs off this).
  if [ -z "$target" ] && command -v javac >/dev/null 2>&1; then
    target="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
  fi
  if [ -n "$target" ] && [ -x "$target/bin/javac" ]; then
    echo "    Linking $JAVA_LINK -> $target"
    sudo mkdir -p "$(dirname "$JAVA_LINK")"
    sudo ln -sfn "$target" "$JAVA_LINK"
  else
    echo "    WARNING: could not locate a JDK to link at $JAVA_LINK" >&2
  fi
fi

# Note: Claude Code itself is installed by the anthropics/claude-code dev container
# feature (see devcontainer.json), not here.

echo "==> Pre-installing the web bundle's npm dependencies"
# Matches the buildWebBundle Gradle task; makes the first ./gradlew buildPlugin faster.
( cd excalidraw-bundle && npm ci )

echo ""
echo "==> Dev container ready."
echo "    Build:  ./gradlew buildPlugin --no-configuration-cache"
echo "    Test:   ./gradlew test --no-configuration-cache"
echo "    Claude: run 'claude' (authenticate once; config persists in the volume)"
