#!/bin/bash
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PLUGIN_NAME="shipsmooth"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Derive the version from the build's single source of truth rather than a
# hand-maintained literal (which silently drifts on every release). This dev hook
# installs the runtime matching the checked-out tree.
PLUGIN_VERSION="$(sed -n 's/^plugin\.version=//p' "$REPO_ROOT/gradle.properties")"
if [ -z "$PLUGIN_VERSION" ]; then
  echo "session-start: could not read plugin.version from $REPO_ROOT/gradle.properties" >&2
  exit 1
fi

sh "$REPO_ROOT/harness/shared/src/main/resources/install-shipsmooth.sh" "$PLUGIN_NAME" "$PLUGIN_VERSION"
