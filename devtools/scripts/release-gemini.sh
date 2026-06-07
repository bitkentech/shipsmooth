#!/usr/bin/env bash
# release-gemini.sh — Build and publish a shipsmooth Gemini CLI extension release
#                     to the bitkentech/shipsmooth-gemini repo.
#
# Usage: ./scripts/release-gemini.sh <version> [--force]
# Example: ./scripts/release-gemini.sh 0.0.1
#          ./scripts/release-gemini.sh 0.0.1 --force   # skip clean-tree check
#
# Prerequisites: jq, gh (GitHub CLI, authenticated), git (build is Gradle)
#
# The shipsmooth-gemini repo (https://github.com/bitkentech/shipsmooth-gemini) is a
# pure publish artifact — its contents are fully replaced on each release.
# Users install via: gemini extensions install https://github.com/bitkentech/shipsmooth-gemini

set -euo pipefail

VERSION="${1:-}"
FORCE="${2:-}"
if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version> [--force]  (e.g. $0 0.0.1)" >&2
  exit 1
fi

TAG="v${VERSION}"
GEMINI_REPO="bitkentech/shipsmooth-gemini"
GEMINI_REPO_URL="git@github.com:${GEMINI_REPO}.git"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "$REPO_ROOT"

# Ensure working tree is clean (skip with --force)
if [[ "$FORCE" != "--force" ]]; then
  if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Error: working tree has uncommitted changes. Commit or stash them first." >&2
    echo "       Use --force to release anyway (build may not match any commit)." >&2
    exit 1
  fi
fi

# Ensure the tag doesn't already exist in shipsmooth-gemini
if gh release view "$TAG" --repo "$GEMINI_REPO" &>/dev/null; then
  echo "Error: release $TAG already exists in ${GEMINI_REPO}." >&2
  exit 1
fi

ORIGINAL_BRANCH=$(git rev-parse --abbrev-ref HEAD)
MAIN_SHA=$(git rev-parse --short HEAD)

echo "==> Cleaning build-gemini/ directory..."
rm -rf build-gemini/

echo "==> Building Gemini extension (./gradlew assembleGeminiProd)..."
# -Pplugin.version overrides the build version for this invocation only (no
# gradle.properties mutation/commit), mirroring the old script which set the
# Gemini release version without bumping the project version. The manifest task
# expands it into gemini-extension.json at build time, so no post-build jq stamp
# is needed.
./gradlew assembleGeminiProd -Pbuild.outputDir="${REPO_ROOT}/build-gemini" \
  -Pplugin.version="${VERSION}" -q

echo "==> Cloning ${GEMINI_REPO}..."
GEMINI_CLONE_DIR=$(mktemp -d)
git clone "$GEMINI_REPO_URL" "$GEMINI_CLONE_DIR" -q
git -C "$GEMINI_CLONE_DIR" config user.name "$(git config user.name)"
git -C "$GEMINI_CLONE_DIR" config user.email "$(git config user.email)"

echo "==> Replacing ${GEMINI_REPO} contents with new Gemini build..."
find "$GEMINI_CLONE_DIR" -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +
cp -r build-gemini/. "$GEMINI_CLONE_DIR/"

echo "==> Committing release ${TAG}..."
git -C "$GEMINI_CLONE_DIR" add -A
git -C "$GEMINI_CLONE_DIR" commit -m "release: ${TAG} — Built from ${ORIGINAL_BRANCH}@${MAIN_SHA}"

echo "==> Tagging ${TAG}..."
git -C "$GEMINI_CLONE_DIR" tag "$TAG"

echo "==> Pushing ${GEMINI_REPO} and tag..."
git -C "$GEMINI_CLONE_DIR" push origin main -q
git -C "$GEMINI_CLONE_DIR" push origin "$TAG" -q

echo "==> Creating GitHub Release ${TAG} in ${GEMINI_REPO}..."
gh release create "$TAG" \
  --repo "$GEMINI_REPO" \
  --title "${TAG}" \
  --notes "shipsmooth Gemini CLI extension ${TAG}. Built from \`${ORIGINAL_BRANCH}@${MAIN_SHA}\`.

Install: \`gemini extensions install https://github.com/${GEMINI_REPO}\`
Pin to this version: \`gemini extensions install https://github.com/${GEMINI_REPO} --ref ${TAG}\`"

echo "==> Cleaning up..."
rm -rf "$GEMINI_CLONE_DIR"

echo ""
echo "Gemini release ${TAG} published to ${GEMINI_REPO}."
echo "Install: gemini extensions install https://github.com/${GEMINI_REPO}"
