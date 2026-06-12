#!/usr/bin/env bash
# Smoke test for the Gemini CLI extension build.
#
# What this tests:
#   1. ./gradlew assembleGeminiProd produces a valid build-gemini/ tree
#   2. gemini extensions link build-gemini/ succeeds
#   3. The linked extension appears in ~/.gemini/extensions/
#
# What this does NOT test (requires interactive gemini session):
#   - SessionStart hook actually firing in a live gemini session
#   - Skill activation and slash command registration
#   Use the manual verification steps in DEVELOPMENT.md for those.
#
# Usage: ./scripts/smoke-gemini.sh
# Prerequisites: gemini CLI installed via NVM v22 (see DEVELOPMENT.md)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GEMINI_BIN="/opt/nvm/versions/node/v22.22.2/bin/gemini"
BUILD_DIR="$REPO_ROOT/build-gemini"

echo "=== shipsmooth Gemini smoke test ==="

# --- 1. Check prerequisites ---
if [[ ! -x "$GEMINI_BIN" ]]; then
  echo "SKIP: gemini CLI not found at $GEMINI_BIN"
  echo "      Install via: nvm install 22 && npm install -g @google/gemini-cli"
  exit 0
fi

echo "gemini: $($GEMINI_BIN --version 2>&1 | head -1)"

# --- 2. Build ---
echo ""
echo "--- Step 1: ./gradlew assembleGeminiProd ---"
cd "$REPO_ROOT"
./gradlew assembleGeminiProd -Pbuild.outputDir="$BUILD_DIR" -q
echo "Build output: $BUILD_DIR"

# Assert expected files exist
assert_file() {
  if [[ -f "$1" ]]; then
    echo "  PASS: $(basename $1)"
  else
    echo "  FAIL: $1 missing"
    exit 1
  fi
}

echo "--- Asserting build-gemini/ layout ---"
assert_file "$BUILD_DIR/gemini-extension.json"
assert_file "$BUILD_DIR/hooks/hooks.json"
assert_file "$BUILD_DIR/commands/start.toml"
assert_file "$BUILD_DIR/skills/start/SKILL.md"
assert_file "$BUILD_DIR/dist/session-start.js"
assert_file "$BUILD_DIR/dist/session-start-config.json"

# Assert SKILL.md has frontmatter
if head -1 "$BUILD_DIR/skills/start/SKILL.md" | grep -q "^---"; then
  echo "  PASS: SKILL.md has frontmatter"
else
  echo "  FAIL: SKILL.md missing frontmatter"
  exit 1
fi

# Assert SKILL.md's cliBin resolves to the native runtime binary (jlink model)
if grep -Eq "shipsmooth(-dev)?/[0-9][^/]*/bin/shipsmooth" "$BUILD_DIR/skills/start/SKILL.md"; then
  echo "  PASS: SKILL.md uses <version>/bin/shipsmooth cliBin"
else
  echo "  FAIL: SKILL.md missing <version>/bin/shipsmooth cliBin"
  exit 1
fi

# Assert the SessionStart hook runs session-start.js via ${extensionPath} (not ${CLAUDE_PLUGIN_ROOT})
if grep -q 'extensionPath' "$BUILD_DIR/hooks/hooks.json" \
   && grep -q 'session-start.js' "$BUILD_DIR/hooks/hooks.json" \
   && ! grep -q 'CLAUDE_PLUGIN_ROOT' "$BUILD_DIR/hooks/hooks.json"; then
  echo "  PASS: hooks.json runs session-start.js via \${extensionPath}"
else
  echo "  FAIL: hooks.json does not run session-start.js via \${extensionPath}"
  exit 1
fi

# Assert no leftover npm-install machinery (package.json must not be shipped)
if [[ ! -f "$BUILD_DIR/package.json" ]] && ! grep -q 'npm install' "$BUILD_DIR/hooks/hooks.json"; then
  echo "  PASS: no package.json shipped, no npm install in hook"
else
  echo "  FAIL: fossil npm-install machinery still present"
  exit 1
fi

# --- 3. Link extension ---
echo ""
echo "--- Step 2: gemini extensions link ---"
# Remove any existing shipsmooth extension dir first (idempotent re-link)
rm -rf "$HOME/.gemini/extensions/shipsmooth"
npm_config_cache="/opt/nvm/cache" npm_config_prefix="" \
  "$GEMINI_BIN" extensions link --consent "$BUILD_DIR" 2>&1 | tail -3

INSTALL_META="$HOME/.gemini/extensions/shipsmooth/.gemini-extension-install.json"
if [[ -f "$INSTALL_META" ]]; then
  echo "  PASS: extension linked at ~/.gemini/extensions/shipsmooth/"
  echo "  source: $(node -e "console.log(require('$INSTALL_META').source)")"
else
  echo "  FAIL: extension not linked — $INSTALL_META missing"
  exit 1
fi

echo ""
echo "=== ALL SMOKE TESTS PASSED ==="
echo ""
echo "Next: start 'gemini' in a repo and verify:"
echo "  - SessionStart hook fires (session-start.js installs <version>/bin/shipsmooth)"
echo "  - /skills shows start"
echo "  - /start command is available"
