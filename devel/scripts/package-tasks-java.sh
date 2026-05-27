#!/usr/bin/env bash
# package-tasks-java.sh — Build a self-contained shippable zip of app.
#
# Output: app/target/dist/shipsmooth-tasks-<version>-linux-x64.zip
#
# The zip contains:
#   - runtime/    jlink image with openj9.sharedclasses (~85MB)
#   - bin/        install-relative launcher with -Xquickstart and -Xshareclasses
#
# Recipient extracts the zip and runs bin/shipsmooth-tasks. SCC cache is written
# under ${XDG_CACHE_HOME:-$HOME/.cache}/shipsmooth/scc/ on first invocation.
#
# Prerequisites:
#   mvn -pl app -am -Pjlink package
#
# Usage: ./scripts/package-tasks-java.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MODULE_DIR="${REPO_ROOT}/app"
TARGET_DIR="${MODULE_DIR}/target"
DIST_DIR="${TARGET_DIR}/dist"

JLINK_IMAGE="${TARGET_DIR}/jlink-image"

cd "$REPO_ROOT"

VERSION=$(mvn -pl app -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)
STAGE_NAME="shipsmooth-tasks-${VERSION}"
STAGE_DIR="${DIST_DIR}/${STAGE_NAME}"
ZIP_PATH="${DIST_DIR}/${STAGE_NAME}-linux-x64.zip"

echo "==> Verifying prerequisites..."
[[ -x "${JLINK_IMAGE}/bin/java" ]] || {
  echo "Error: jlink image not found at ${JLINK_IMAGE}. Run 'mvn -pl app -am -Pjlink package' first." >&2
  exit 1
}
find "${JLINK_IMAGE}/lib" -name 'libj9shr*.so' | grep -q . 2>/dev/null || {
  echo "Error: libj9shr*.so absent from jlink image — rebuild with openj9.sharedclasses in --add-modules." >&2
  exit 1
}

echo "==> Cleaning ${DIST_DIR}..."
rm -rf "$DIST_DIR"
mkdir -p "$STAGE_DIR/bin"

echo "==> Copying jlink image as runtime/..."
cp -r "$JLINK_IMAGE" "$STAGE_DIR/runtime"

echo "==> Writing install-relative launcher..."
cat > "$STAGE_DIR/bin/shipsmooth-tasks" <<'LAUNCHER'
#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL="$(cd "$DIR/.." && pwd)"
SCC_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/shipsmooth/scc"
mkdir -p "$SCC_DIR"

exec "$INSTALL/runtime/bin/java" \
  -Xquickstart \
  -Xshareclasses:name=shipsmooth_v__VERSION__,cacheDir="$SCC_DIR",nonfatal \
  -m com.github.pramodbiligiri.shipsmooth.tasks/com.github.pramodbiligiri.shipsmooth.tasks.TasksCli "$@"
LAUNCHER

sed -i "s/__VERSION__/${VERSION}/" "$STAGE_DIR/bin/shipsmooth-tasks"
chmod 755 "$STAGE_DIR/bin/shipsmooth-tasks"

echo "==> Smoke-testing the staged launcher..."
"$STAGE_DIR/bin/shipsmooth-tasks" --help > /dev/null

echo "==> Creating ${ZIP_PATH}..."
( cd "$DIST_DIR" && zip -qr "$(basename "$ZIP_PATH")" "$STAGE_NAME" )

SIZE=$(du -h "$ZIP_PATH" | cut -f1)
echo ""
echo "Built: ${ZIP_PATH} (${SIZE})"
echo "Stage: ${STAGE_DIR}"