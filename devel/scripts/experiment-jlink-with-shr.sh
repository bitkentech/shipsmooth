#!/usr/bin/env bash
# experiment-jlink-with-shr.sh — Build a jlink image that includes openj9.sharedclasses
# and benchmark its startup.
#
# Hypothesis: adding openj9.sharedclasses to --add-modules pulls libj9shr29.so into the
# image, allowing -Xshareclasses to work directly on the jlink-built bin/java. If true,
# we can ship that image (~84MB) instead of the standalone Semeru JRE (~175MB) and
# possibly hit the ~2ms median startup the decision doc reports.
#
# Outputs:
#   app/target/jlink-experiment/image/    — the jlink image
#   app/target/jlink-experiment/scc/      — isolated SCC cache
#   app/target/jlink-experiment/timings.txt
#
# This script does NOT modify pom.xml or scripts/package-tasks-java.sh.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MODULE_DIR="${REPO_ROOT}/app"
TARGET_DIR="${MODULE_DIR}/target"
EXP_DIR="${TARGET_DIR}/jlink-experiment"

JDK_HOME="/opt/installers/jdk-semeru/jdk-25.0.2+10"
JMODS="${JDK_HOME}/jmods"

cd "$REPO_ROOT"

VERSION=$(mvn -pl app -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)
APP_JAR="${TARGET_DIR}/app-${VERSION}.jar"
[[ -f "$APP_JAR" ]] || { echo "Error: $APP_JAR not found. Run 'mvn -pl app -am -Pjlink package' first." >&2; exit 1; }

echo "==> Resolving runtime dependencies..."
DEPS_FILE=$(mktemp)
trap 'rm -f "$DEPS_FILE"' EXIT
mvn -pl app -q dependency:build-classpath \
  -DincludeScope=runtime \
  -Dmdep.outputFile="$DEPS_FILE"
DEP_JARS=$(cat "$DEPS_FILE")

MODULE_PATH="${JMODS}:${APP_JAR}:${DEP_JARS}"
IMAGE="${EXP_DIR}/image"

echo "==> Cleaning ${EXP_DIR}..."
rm -rf "$EXP_DIR"
mkdir -p "$EXP_DIR"

echo "==> Building jlink image WITH openj9.sharedclasses..."
"${JDK_HOME}/bin/jlink" \
  --module-path "$MODULE_PATH" \
  --add-modules com.github.pramodbiligiri.shipsmooth.tasks,openj9.sharedclasses \
  --launcher "shipsmooth=com.github.pramodbiligiri.shipsmooth.tasks/com.github.pramodbiligiri.shipsmooth.tasks.TasksCli" \
  --no-header-files --no-man-pages \
  --compress zip-9 \
  --output "$IMAGE"

echo "==> Image size:"
du -sh "$IMAGE"

echo "==> Checking for libj9shr*.so..."
find "$IMAGE/lib" -name 'libj9shr*' 2>/dev/null || true
find "$IMAGE/lib" -name 'libj9shr*' 2>/dev/null | grep -q . || echo "  WARNING: no libj9shr*.so found"

echo "==> Smoke test: -Xshareclasses on jlink bin/java..."
SCC_DIR="${EXP_DIR}/scc"
rm -rf "$SCC_DIR"
mkdir -p "$SCC_DIR"
"$IMAGE/bin/java" \
  -Xquickstart \
  -Xshareclasses:name=exp_shr,cacheDir="$SCC_DIR",nonfatal \
  --module-path "${APP_JAR}:${DEP_JARS}" \
  -m com.github.pramodbiligiri.shipsmooth.tasks/com.github.pramodbiligiri.shipsmooth.tasks.TasksCli --help > /dev/null
echo "  smoke test PASSED"

echo "==> Writing benchmark launcher..."
LAUNCHER="${EXP_DIR}/run.sh"
cat > "$LAUNCHER" <<EOF
#!/bin/sh
exec "$IMAGE/bin/java" \\
  -Xquickstart \\
  -Xshareclasses:name=exp_shr,cacheDir="$SCC_DIR",nonfatal \\
  --module-path "${APP_JAR}:${DEP_JARS}" \\
  -m com.github.pramodbiligiri.shipsmooth.tasks/com.github.pramodbiligiri.shipsmooth.tasks.TasksCli "\$@"
EOF
chmod 755 "$LAUNCHER"

echo "==> Warmup (5 runs)..."
for i in 1 2 3 4 5; do "$LAUNCHER" --help > /dev/null; done

echo "==> 100 timed runs..."
RESULTS="${EXP_DIR}/timings.txt"
> "$RESULTS"
for i in $(seq 1 100); do
  ms=$( { time "$LAUNCHER" --help > /dev/null; } 2>&1 | awk '/real/ {
    split($2, a, "m"); split(a[2], b, "s");
    printf "%.3f\n", a[1]*60000 + b[1]*1000
  }')
  echo "$ms" >> "$RESULTS"
done

echo ""
echo "==> Results:"
python3 - "$RESULTS" <<'PY'
import sys, statistics
with open(sys.argv[1]) as f:
    data = sorted(float(x) for x in f if x.strip())
n = len(data)
def pct(p):
    k = (n - 1) * p / 100
    f_ = int(k); c = min(f_+1, n-1)
    return data[f_] + (data[c]-data[f_])*(k - f_)
print(f"  N      = {n}")
print(f"  Min    = {data[0]:7.2f} ms")
print(f"  Median = {statistics.median(data):7.2f} ms")
print(f"  Mean   = {statistics.mean(data):7.2f} ms")
print(f"  P90    = {pct(90):7.2f} ms")
print(f"  P95    = {pct(95):7.2f} ms")
print(f"  P99    = {pct(99):7.2f} ms")
print(f"  Max    = {data[-1]:7.2f} ms")
print(f"  Stdev  = {statistics.stdev(data):7.2f} ms")
PY