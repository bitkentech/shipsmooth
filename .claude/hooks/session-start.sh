#!/bin/bash
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PLUGIN_NAME="shipsmooth"
PLUGIN_VERSION="0.3.24"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

sh "$REPO_ROOT/harness/shared/src/main/resources/install-shipsmooth.sh" "$PLUGIN_NAME" "$PLUGIN_VERSION"
