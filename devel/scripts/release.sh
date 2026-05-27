#!/usr/bin/env bash
# release.sh — Build and publish a shipsmooth plugin release to the orphan `releases` branch.
#
# Usage: ./scripts/release.sh <version>
# Example: ./scripts/release.sh 0.2.0
#
# Prerequisites: jq, gh (GitHub CLI, authenticated), maven, git

set -euo pipefail

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version>  (e.g. $0 0.2.0)" >&2
  exit 1
fi

TAG="v${VERSION}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

cd "$REPO_ROOT"

# Ensure working tree is clean
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Error: working tree has uncommitted changes. Commit or stash them first." >&2
  exit 1
fi

# Ensure the tag doesn't already exist
if git rev-parse "$TAG" &>/dev/null; then
  echo "Error: tag $TAG already exists." >&2
  exit 1
fi

ORIGINAL_BRANCH=$(git rev-parse --abbrev-ref HEAD)

echo "==> Bumping Maven project version to ${VERSION}..."
mvn versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false -q
git add $(git ls-files '**/pom.xml' pom.xml)
git commit -m "chore: bump version to ${VERSION}"

MAIN_SHA=$(git rev-parse --short HEAD)

echo "==> Cleaning build/ directory..."
rm -rf build/

echo "==> Building production plugin (mvn compile -Pprod -P\!dev)..."
mvn compile -Pprod -P'!dev' -q

echo "==> Stamping version ${VERSION} into build/.claude-plugin/plugin.json..."
tmp=$(mktemp)
jq --arg v "$VERSION" '. + {"version": $v}' build/.claude-plugin/plugin.json > "$tmp"
mv "$tmp" build/.claude-plugin/plugin.json

echo "==> Switching to orphan releases branch..."
git checkout releases

echo "==> Replacing dist/ contents with new build..."
rm -rf dist
mkdir -p dist
cp -r build/.claude-plugin dist/
cp -r build/hooks dist/
cp -r build/dist dist/
cp -r build/scripts dist/
cp -r build/skills dist/
cp build/package.json dist/

echo "==> Committing release ${TAG}..."
git add dist/
git commit -m "release: ${TAG} — Built from ${ORIGINAL_BRANCH}@${MAIN_SHA}"

echo "==> Tagging ${TAG}..."
git tag "$TAG"

echo "==> Pushing releases branch and tag..."
git push origin releases "$TAG"

echo "==> Creating GitHub Release ${TAG}..."
gh release create "$TAG" \
  --target releases \
  --title "${TAG}" \
  --notes "shipsmooth plugin release ${TAG}. Built from \`${ORIGINAL_BRANCH}@${MAIN_SHA}\`."

echo "==> Returning to ${ORIGINAL_BRANCH}..."
git checkout "$ORIGINAL_BRANCH"

echo ""
echo "Release ${TAG} published successfully."
echo "Users will receive this update on their next: /plugin marketplace update"
