#!/usr/bin/env bash
# experiment-startup-matrix.sh — Benchmark several JVM configurations against the doc's claims.
#
# Configurations:
#   A. Full Semeru JDK + SCC + -Xquickstart                  (doc claim: 15ms)
#   B. jlink + openj9.sharedclasses + zip-9 + SCC            (already measured ~353ms)
#   C. jlink + openj9.sharedclasses + NO compress + SCC      (test: is decompression the floor?)
#   D. Standalone Semeru JRE + SCC                           (already measured ~338ms)
#
# Each: clear isolated SCC, 5 warmup runs, 100 timed runs.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODULE_DIR="${REPO_ROOT}/app"
TARGET_DIR="${MODULE_DIR}/target"
EXP_DIR="${TARGET_DIR}/jlink-experiment"

JDK_HOME="/opt/installers/jdk-semeru/jdk-25.0.2+10"
JRE_HOME="/opt/installers/jre-semeru/jdk-25.0.2+10-jre"
JMODS="${JDK_HOME}/jmods"

cd "$REPO_ROOT"

VERSION=$(mvn -pl app -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)
APP_JAR="${TARGET_DIR}/app-${VERSION}.jar"
[[ -f "$APP_JAR" ]] || { echo "Error: $APP_JAR not found." >&2; exit 1; }

DEPS_FILE=$(mktemp); trap 'rm -f "$DEPS_FILE"' EXIT
mvn -pl app -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="$DEPS_FILE"
DEP_JARS=$(cat "$DEPS_FILE")
RT_MP="${APP_JAR}:${DEP_JARS}"

mkdir -p "$EXP_DIR"

# Build C if missing
IMAGE_C="${EXP_DIR}/image-nocompress"
if [[ ! -x "$IMAGE_C/bin/java" ]]; then
  echo "==> Building jlink image with NO compression..."
  rm -rf "$IMAGE_C"
  "${JDK_HOME}/bin/jlink" \
    --module-path "${JMODS}:${APP_JAR}:${DEP_JARS}" \
    --add-modules com.github.pramodbiligiri.shipsmooth.tasks,openj9.sharedclasses \
    --launcher "shipsmooth-tasks=com.github.pramodbiligiri.shipsmooth.tasks/com.github.pramodbiligiri.shipsmooth.tasks.TasksCli" \
    --no-header-files --no-man-pages \
    --output "$IMAGE_C"
  du -sh "$IMAGE_C"
fi

run_bench() {
  local label="$1" javabin="$2" sccname="$3"
  local sccdir="${EXP_DIR}/scc-${sccname}"
  rm -rf "$sccdir"; mkdir -p "$sccdir"
  local launcher="${EXP_DIR}/run-${sccname}.sh"
  cat > "$launcher" <<EOF
#!/bin/sh
exec "$javabin" \\
  -Xquickstart \\
  -Xshareclasses:name=${sccname},cacheDir="$sccdir",nonfatal \\
  --module-path "${RT_MP}" \\
  -m com.github.pramodbiligiri.shipsmooth.tasks/com.github.pramodbiligiri.shipsmooth.tasks.TasksCli "\$@"
EOF
  chmod 755 "$launcher"

  for i in 1 2 3 4 5; do "$launcher" --help > /dev/null; done
  local results="${EXP_DIR}/timings-${sccname}.txt"
  > "$results"
  for i in $(seq 1 100); do
    ms=$( { time "$launcher" --help > /dev/null; } 2>&1 | awk '/real/ {
      split($2, a, "m"); split(a[2], b, "s");
      printf "%.3f\n", a[1]*60000 + b[1]*1000
    }')
    echo "$ms" >> "$results"
  done
  echo ""
  echo "=== $label ==="
  python3 - "$results" <<'PY'
import sys, statistics
with open(sys.argv[1]) as f:
    data = sorted(float(x) for x in f if x.strip())
n = len(data)
def pct(p):
    k = (n - 1) * p / 100
    f_ = int(k); c = min(f_+1, n-1)
    return data[f_] + (data[c]-data[f_])*(k - f_)
print(f"  N={n}  Min={data[0]:6.1f}  Median={statistics.median(data):6.1f}  Mean={statistics.mean(data):6.1f}  P95={pct(95):6.1f}  Max={data[-1]:6.1f}  Stdev={statistics.stdev(data):5.1f}")
PY
}

run_bench "A. Full Semeru JDK"                        "${JDK_HOME}/bin/java"                "scc_jdk"
run_bench "B. jlink + sharedclasses + zip-9"          "${EXP_DIR}/image/bin/java"           "scc_jlinkzip"
run_bench "C. jlink + sharedclasses + NO compress"    "${IMAGE_C}/bin/java"                 "scc_jlinkraw"
run_bench "D. Standalone Semeru JRE"                  "${JRE_HOME}/bin/java"                "scc_jre"