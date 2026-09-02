#!/usr/bin/env bash
#
# End-to-end smoke test for the claude+shipsmooth sandbox image.
#
# Contract for "the build tooling works":
#   1. `./gradlew :docker:buildImage` produces a local image (no push).
#   2. The image carries the component-version OCI labels, non-empty.
#   3. Those labels are not lying: the claude-code version label matches
#      `claude --version` inside the container, and the shipsmooth plugin is
#      actually installed.
#   4. `./gradlew :docker:validateLabels` (local consistency mode) exits zero.
#
# Requires Docker (not available in the release container itself — run this on a
# Docker-capable host). This is deliberately NOT a unit test — it exercises a
# real `docker build` + `docker run`.
#
# Run from anywhere; it cd's to the repo root. Override the image ref with
# SMOKE_IMAGE=... (default below).

set -euo pipefail

# docker/smoke.sh -> repo root is one level up.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SMOKE_IMAGE="${SMOKE_IMAGE:-bitkentech/shipsmooth-claude:smoke-test}"

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
note() { echo "  smoke: $*"; }

command -v docker >/dev/null 2>&1 || fail "docker not found on PATH"

# ---------------------------------------------------------------------------
# 1. Build the image locally (no push).
# ---------------------------------------------------------------------------
note "building $SMOKE_IMAGE via ./gradlew :docker:buildImage"
./gradlew --quiet :docker:buildImage -Pimage="$SMOKE_IMAGE" \
  || fail "./gradlew :docker:buildImage failed"

# ---------------------------------------------------------------------------
# 2. Component-version labels present and non-empty.
# ---------------------------------------------------------------------------
required_labels=(
  "org.opencontainers.image.version"
  "org.opencontainers.image.revision"
  "io.bitken.ss.claude-code.version"
  "io.bitken.ss.shipsmooth.version"
)
for label in "${required_labels[@]}"; do
  value="$(docker inspect --format "{{index .Config.Labels \"$label\"}}" "$SMOKE_IMAGE" 2>/dev/null || true)"
  [ -n "$value" ] || fail "label '$label' missing or empty on $SMOKE_IMAGE"
  note "label $label = $value"
done

claude_label="$(docker inspect --format '{{index .Config.Labels "io.bitken.ss.claude-code.version"}}' "$SMOKE_IMAGE")"

# ---------------------------------------------------------------------------
# 3. Labels are not lying: image contents match what they claim.
# ---------------------------------------------------------------------------
note "checking claude --version inside the container matches the label"
runtime_version="$(docker run --rm "$SMOKE_IMAGE" claude --version 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
[ -n "$runtime_version" ] || fail "could not read 'claude --version' inside the container"
[ "$runtime_version" = "$claude_label" ] \
  || fail "claude-code version mismatch: label=$claude_label runtime=$runtime_version"

note "checking the shipsmooth plugin is installed and enabled"
# Read the on-disk plugin registry rather than `claude plugin list` — the latter
# wants runtime credentials the sandbox doesn't have until you log in.
docker run --rm "$SMOKE_IMAGE" \
  cat /root/.claude/plugins/installed_plugins.json 2>/dev/null | grep -q '"shipsmooth@bitkentech"' \
  || fail "shipsmooth not in /root/.claude/plugins/installed_plugins.json"
docker run --rm "$SMOKE_IMAGE" \
  cat /root/.claude/settings.json 2>/dev/null \
  | grep -qE '"shipsmooth@bitkentech"[[:space:]]*:[[:space:]]*true' \
  || fail "shipsmooth not enabled in /root/.claude/settings.json"

# ---------------------------------------------------------------------------
# 4. validateLabels (local consistency mode — no Docker Hub round trip).
# ---------------------------------------------------------------------------
note "running ./gradlew :docker:validateLabels --local"
./gradlew --quiet :docker:validateLabels -Pimage="$SMOKE_IMAGE" -Plocal=true \
  || fail "./gradlew :docker:validateLabels failed against $SMOKE_IMAGE"

echo "SMOKE PASS: $SMOKE_IMAGE"
