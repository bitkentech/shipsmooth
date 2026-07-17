#!/usr/bin/env bash
# Regenerates the golden fixture corpus by driving the JAVA shipsmooth CLI in a
# throwaway project. The committed corpus is the reference the Rust port is
# tested against (plan-102 Task 1); rerunning produces equivalent files with
# different timestamps/commit hashes/absolute paths — diff structurally, not
# byte-wise, after a regeneration.
#
# Usage: SS=/path/to/shipsmooth ./generate.sh   (SS defaults to the 0.3.34 runtime)
set -euo pipefail

SS="${SS:-$HOME/.cache/shipsmooth/0.3.34/bin/shipsmooth}"
OUT="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

rm -rf "$OUT/xml" "$OUT/transcripts"
mkdir -p "$OUT/xml" "$OUT/transcripts"

PROJ="$WORK/fixture-proj"
mkdir -p "$PROJ"
cd "$PROJ"
git init -q .
git -c user.email=fixture@example.com -c user.name=Fixture commit -q --allow-empty -m "seed"

PLANS=".shipsmooth/plans"

# --- transcripts: the unsettled resolve gate (exit 10 + JSON contract) -------
set +e
"$SS" plan resume --plan 103 >"$OUT/transcripts/gate-clean-first-run.json" 2>/dev/null
echo "exit=$?" >"$OUT/transcripts/gate-clean-first-run.exit"
"$SS" store info --json >"$OUT/transcripts/store-info-unsettled.json" 2>/dev/null
echo "exit=$?" >"$OUT/transcripts/store-info-unsettled.exit"
set -e

# --- settle same-repo, capture the ready contract ----------------------------
"$SS" store init --type same-repo --json >"$OUT/transcripts/store-init-same-repo.json"
"$SS" store info --json >"$OUT/transcripts/store-info-ready.json"

# --- authentic fixtures: real recent plans from this repo --------------------
# Genuine files written by the current Java CLI during real work (post-plan-90
# format). The synthetic plans below add full enum/feature coverage on top.
REPO_PLANS="$OUT/../../.shipsmooth/plans"
cp "$REPO_PLANS/plan-96-tasks.xml" "$OUT/xml/00-real-plan-96.xml"
cp "$REPO_PLANS/plan-97-tasks.xml" "$OUT/xml/00-real-plan-97.xml"

# --- plan 103: every task feature ---------------------------------------------
# Risks: High/Medium/Low/absent; Depends-on both single and multi-id.
cat >"$PLANS/plan-103.md" <<'EOF'
# Plan 103 — Fixture plan

## Tasks

### Task 1: Parse the input file [High]

Core parsing slice.

### Task 2: Write the output file [Medium]

*Depends-on: 1*

Serialisation slice.

### Task 3: Wire the end-to-end flow [Low]

*Depends-on: 1, 2*

Integration slice.

### Task 4: Document the format

No risk tag on this one (empty risk enum value).

### Task 5: Abandoned experiment [Low]

Exercises the abandoned status.

### Task 6: Untouched task [Low]

Stays pending with empty containers.
EOF
"$SS" plan init --plan 103 --tasks-from "$PLANS/plan-103.md" >/dev/null
cp "$PLANS/plan-103-tasks.xml" "$OUT/xml/01-fresh-init.xml"

# Cover every TaskStatusType value; comments with XML-escapable + unicode text;
# both deviation types; set-commit with and without --branch; task add.
"$SS" task status  --plan 103 --task 1 --status in-progress
"$SS" task comment --plan 103 --task 1 --message "Special chars: & < > \" ' and unicode: héllo 🚀"
"$SS" task set-commit --plan 103 --task 1 --commit 0123456789abcdef0123456789abcdef01234567 --branch t/1-fixture
"$SS" task status  --plan 103 --task 1 --status de-risked
"$SS" task deviation --plan 103 --task 1 --type minor --message "Split parsing into two passes"
"$SS" task status  --plan 103 --task 2 --status agent-coded
"$SS" task set-commit --plan 103 --task 2 --commit fedcba9876543210fedcba9876543210fedcba98
"$SS" task status  --plan 103 --task 3 --status closed
"$SS" task status  --plan 103 --task 4 --status needs-triage
"$SS" task deviation --plan 103 --task 4 --type major --message "Format spec contradicts implementation"
"$SS" task status  --plan 103 --task 5 --status abandoned
"$SS" task add     --plan 103 --name "Added after init" --risk low --depends-on 2,3
"$SS" plan update  --plan 103 --message "Mid-plan checkpoint"
"$SS" plan update  --plan 103 --blocked --message "Blocked on format decision"
cp "$PLANS/plan-103-tasks.xml" "$OUT/xml/02-rich.xml"

"$SS" plan resume --plan 103 >"$OUT/transcripts/plan-resume-rich.txt"

"$SS" plan update --plan 103 --status in-review --message "Ready for review"
cp "$PLANS/plan-103-tasks.xml" "$OUT/xml/03-status-in-review.xml"
"$SS" plan update --plan 103 --status complete --message "Done"
cp "$PLANS/plan-103-tasks.xml" "$OUT/xml/04-status-complete.xml"

# --- plan 104: minimal + abandoned plan status --------------------------------
cat >"$PLANS/plan-104.md" <<'EOF'
# Plan 104 — Minimal fixture

## Tasks

### Task 1: Single task [Low]

Only task.
EOF
"$SS" plan init --plan 104 --tasks-from "$PLANS/plan-104.md" >/dev/null
"$SS" plan update --plan 104 --status abandoned --message "Superseded"
cp "$PLANS/plan-104-tasks.xml" "$OUT/xml/05-minimal-abandoned.xml"

# --- unknown xs:any extension preservation -----------------------------------
# Hand-insert elements NO current version knows about (beyond depends-on) into
# plan 103's file, then have the Java CLI rewrite the file (task comment). The
# pair 06/07 pins how JAXB treats unknown lax elements on read-modify-write —
# the exact behaviour the Rust port must reproduce.
python3 - "$PLANS/plan-103-tasks.xml" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
s = s.replace("</metadata>",
    '    <meta-ext scope="fixture"><nested>deep value</nested></meta-ext>\n    </metadata>', 1)
i = s.rfind("</task>")
s = s[:i] + '    <future-field attr="x">unknown extension text</future-field>\n        ' + s[i:]
open(p, "w", encoding="utf-8").write(s)
PYEOF
cp "$PLANS/plan-103-tasks.xml" "$OUT/xml/06-unknown-ext-input.xml"
"$SS" task comment --plan 103 --task 6 --message "Rewrite after unknown-element insertion"
cp "$PLANS/plan-103-tasks.xml" "$OUT/xml/07-unknown-ext-after-java-rewrite.xml"

# --- error-path transcript ----------------------------------------------------
set +e
"$SS" task status --plan 103 --task 1 --status bogus >"$OUT/transcripts/error-invalid-status.out" 2>"$OUT/transcripts/error-invalid-status.err"
echo "exit=$?" >"$OUT/transcripts/error-invalid-status.exit"
set -e

echo "Fixture corpus written to $OUT/xml and $OUT/transcripts"
