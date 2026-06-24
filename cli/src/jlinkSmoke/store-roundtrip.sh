#!/bin/sh
# Modular smoke test for the store first-run round-trip (plan-87 Task 1).
#
# Drives `store init --choice external` then `store info --json` through the REAL
# jlink/SCC modular runtime — the layer where the conf.ds JPMS-opens defect lives.
# Classpath unit tests cannot catch it because `opens` rules are not enforced on the
# classpath; only a genuine module run is faithful.
#
# Fully isolated: a private XDG_CONFIG_HOME and temp dirs, so it never reads or writes
# the developer's real ~/.config/shipsmooth. Exits non-zero (failing the build) unless
# `store info` reports status:"ready" after init.
#
# Usage: store-roundtrip.sh <path-to-scc-launcher>
set -eu

LAUNCHER="$1"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

REPO="$WORK/repo"          # stands in for the user's project repo (cwd at run time)
STATE="$WORK/state"        # external state dir store init should create
export XDG_CONFIG_HOME="$WORK/xdg-config"   # isolate shipsmooth.toml away from ~/.config
mkdir -p "$REPO"

fail() { echo "FAIL: $1" >&2; exit 1; }

# 1. Clean first run -> accept external state at an explicit path.
INIT_OUT="$(cd "$REPO" && "$LAUNCHER" store init --choice external --path "$STATE" --json 2>&1)" \
  || fail "store init exited non-zero:
$INIT_OUT"

case "$INIT_OUT" in
  *'"status":"ready"'*) : ;;
  *) fail "store init did not report ready:
$INIT_OUT" ;;
esac

# 2. Re-resolve: the recorded config must now resolve to the external dir as ready.
INFO_OUT="$(cd "$REPO" && "$LAUNCHER" store info --json 2>&1)" \
  || fail "store info exited non-zero:
$INFO_OUT"

case "$INFO_OUT" in
  *'"status":"ready"'*) : ;;
  *) fail "store info did not report ready (config round-trip broken):
$INFO_OUT" ;;
esac

# The config file must actually have content (a 0-byte file is the defect's signature).
CONFIG="$XDG_CONFIG_HOME/shipsmooth/shipsmooth.toml"
[ -s "$CONFIG" ] || fail "config file is empty or missing: $CONFIG"

# plan-90: the config must be multi-line array-of-tables, not a single inline line.
grep -q '^\[\[projects\]\]' "$CONFIG" \
  || fail "config is not multi-line [[projects]] form:
$(cat "$CONFIG")"
if grep -q 'projects = \[{' "$CONFIG"; then
  fail "config collapsed to a single inline-array line:
$(cat "$CONFIG")"
fi

echo "PASS: store init -> store info round-trip ready (multi-line [[projects]]) through modular runtime"
