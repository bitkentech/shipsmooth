#!/usr/bin/env bash
# Test for Task 4: Gemini SessionStart hook
#
# Simulates what Gemini CLI does when it fires the SessionStart hook:
# substitutes ${extensionPath} with the actual extension dir and runs the command.
#
# Usage: ./scripts/test-gemini-hook.sh
# Exit 0 = pass, non-zero = fail

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# --- Setup: fake extension dir with package.json and dist/*.js ---
FAKE_EXT="$(mktemp -d)"
trap 'rm -rf "$FAKE_EXT" "$CACHE_DIR"' EXIT

cp "$REPO_ROOT/hooks/package.json" "$FAKE_EXT/"
mkdir -p "$FAKE_EXT/dist"
cp "$REPO_ROOT/hooks/dist"/*.js "$FAKE_EXT/dist/"

# Use an isolated cache dir so we don't clobber ~/.cache/shipsmooth
CACHE_DIR="$(mktemp -d)"
export HOME_ORIG="$HOME"
# Override HOME so ${HOME}/.cache/shipsmooth resolves to our temp dir
export HOME="$CACHE_DIR"

echo "=== Gemini hook test ==="
echo "Fake extensionPath: $FAKE_EXT"
echo "Fake HOME: $CACHE_DIR"

# --- Extract hook command from hooks.json ---
HOOKS_JSON="$REPO_ROOT/plugin-resources/src/main/resources/gemini-extension/hooks/hooks.json"
if [[ ! -f "$HOOKS_JSON" ]]; then
  echo "FAIL: $HOOKS_JSON not found"
  exit 1
fi
HOOK_CMD="$(node -e "const h=require('$HOOKS_JSON'); console.log(h.hooks.SessionStart[0].hooks[0].command)")"

# Substitute ${extensionPath} (Gemini does this before exec)
RESOLVED="${HOOK_CMD//\$\{extensionPath\}/$FAKE_EXT}"

echo "--- Running hook ---"
eval "$RESOLVED"

# --- Assertions ---
PASS=true

assert_file() {
  if [[ -f "$1" ]]; then
    echo "  PASS: $1 exists"
  else
    echo "  FAIL: $1 missing"
    PASS=false
  fi
}

echo "--- Assertions ---"
DIST="$CACHE_DIR/.cache/shipsmooth/dist"
assert_file "$CACHE_DIR/.cache/shipsmooth/package.json"
assert_file "$CACHE_DIR/.cache/shipsmooth/node_modules/fast-xml-parser/package.json"
for js in add-comment add-deviation hello init project-update set-commit show types update-status; do
  assert_file "$DIST/${js}.js"
done

# Assert node can load hello.js (smoke-tests fast-xml-parser)
echo "--- Node smoke test ---"
if node "$DIST/hello.js" >/dev/null 2>&1; then
  echo "  PASS: node hello.js succeeded"
else
  echo "  FAIL: node hello.js failed"
  PASS=false
fi

if $PASS; then
  echo "=== ALL ASSERTIONS PASSED ==="
  exit 0
else
  echo "=== SOME ASSERTIONS FAILED ==="
  exit 1
fi
