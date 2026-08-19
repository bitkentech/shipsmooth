#!/usr/bin/env bash
# Regenerates the golden fixture corpus by driving the JAVA shipsmooth CLI in a
# throwaway project. The committed corpus is the reference the Rust port is
# tested against (plan-102 Task 1); rerunning produces equivalent files with
# different timestamps/commit hashes/absolute paths — diff structurally, not
# byte-wise, after a regeneration.
#
# Usage: SS=/path/to/shipsmooth ./generate.sh   (SS defaults to the 0.3.36 runtime)
set -euo pipefail

SS="${SS:-$HOME/.cache/shipsmooth/0.3.36/bin/shipsmooth}"
OUT="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Redirect the per-user config for the WHOLE run: fixture generation must never
# read the user's real ~/.config/shipsmooth/shipsmooth.toml (its entries could
# change classification) nor write fixture /tmp entries into it (store init
# records the chosen storage in config).
export XDG_CONFIG_HOME="$WORK/confighome"
mkdir -p "$XDG_CONFIG_HOME"

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
# Repo root is three levels up from fixtures/ (exp/rust/fixtures since plan-104).
REPO_PLANS="$OUT/../../../.shipsmooth/plans"
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

# --- plan-106: store-resolution branch table (plan-85) ------------------------
# One scenario directory per branch, each with its own throwaway project repo
# AND its own config home, so scenarios cannot contaminate each other. For each:
# `store info` and `store info --json` with stdout/stderr/exit captured
# separately (stderr discipline is part of the contract), plus the
# shipsmooth.toml that produced the classification.

SPROJ=""; SCFG=""
store_project() { # store_project <scenario>  -> sets SPROJ (repo) and SCFG (config home)
    local base="$WORK/store/$1"
    SPROJ="$base/proj"
    SCFG="$base/confighome"
    mkdir -p "$SPROJ" "$SCFG"
    git -C "$SPROJ" init -q .
    git -C "$SPROJ" -c user.email=fixture@example.com -c user.name=Fixture \
        commit -q --allow-empty -m "seed"
}

store_run() { # store_run <scenario> <capture-name> <cli args...>
    local name="$1" cap="$2"; shift 2
    local dir="$OUT/transcripts/store/$name"
    mkdir -p "$dir"
    set +e
    (cd "$SPROJ" && XDG_CONFIG_HOME="$SCFG" "$SS" "$@" \
        >"$dir/$cap.out" 2>"$dir/$cap.err"; echo "exit=$?" >"$dir/$cap.exit")
    set -e
}

store_capture() { # store_capture <scenario> — both info renderings + the config file
    local name="$1"
    store_run "$name" info      store info
    store_run "$name" info-json store info --json
    if [ -f "$SCFG/shipsmooth/shipsmooth.toml" ]; then
        cp "$SCFG/shipsmooth/shipsmooth.toml" "$OUT/transcripts/store/$name/shipsmooth.toml"
    fi
}

store_toml() { # store_toml — hand-written config for the malformed branches; reads stdin
    mkdir -p "$SCFG/shipsmooth"
    cat >"$SCFG/shipsmooth/shipsmooth.toml"
}

# No config, no state anywhere -> needs-decision / clean-first-run.
store_project clean-first-run
store_capture clean-first-run

# plan-87 leniency: a 0-byte config (e.g. left by a failed init write) is "no
# usable config" and falls through to clean-first-run — never Unresolvable.
store_project empty-config
mkdir -p "$SCFG/shipsmooth"
: >"$SCFG/shipsmooth/shipsmooth.toml"
store_capture empty-config

# Settled branches, driven through the real init leaf; the init transcript is
# captured too — it is the spec for the init leaf's own output.
store_project settled-same-repo
store_run settled-same-repo init store init --type same-repo --json
store_capture settled-same-repo

store_project settled-separate-dir
store_run settled-separate-dir init store init --type separate-dir --json
store_capture settled-separate-dir

# Valid same-repo entry but the in-repo folder is gone -> in-repo-not-set-up.
store_project in-repo-not-set-up
store_run in-repo-not-set-up init store init --type same-repo --json
rm -rf "$SPROJ/.shipsmooth"
store_capture in-repo-not-set-up

# Valid separate-dir entry whose storageRoot vanished -> config-dir-missing.
store_project config-dir-missing
store_run config-dir-missing init store init --type separate-dir --json
rm -rf "$WORK/store/config-dir-missing/proj-shipsmooth"
store_capture config-dir-missing

# Malformed entries -> unresolvable / MALFORMED_CONFIG_ENTRY.
store_project malformed-missing-type
store_toml <<EOF
[[projects]]
localPath = "$SPROJ"
EOF
store_capture malformed-missing-type

store_project malformed-bad-type
store_toml <<EOF
[[projects]]
localPath = "$SPROJ"
storageType = "cloud"
EOF
store_capture malformed-bad-type

store_project malformed-same-repo-with-root
store_toml <<EOF
[[projects]]
localPath = "$SPROJ"
storageType = "same-repo"
storageRoot = "$WORK/store/malformed-same-repo-with-root/proj-shipsmooth"
EOF
store_capture malformed-same-repo-with-root

# Legacy .agents/plans tree, no config -> unresolvable / LEGACY_AGENTS_TREE.
store_project legacy-agents-tree
mkdir -p "$SPROJ/.agents/plans"
store_capture legacy-agents-tree

# --- plan-107: gw mutation sequence, one capture per TaskStore write ----------
# The Rust gw golden-replay harness re-applies this exact operation sequence
# through the Rust TaskStore and byte-diffs every intermediate file (timestamps
# normalised); the step filename encodes the operation. Keep the harness and
# this list in sync.
# Note: depends-on replace/remove has no CLI command (task add only SETS it),
# so those setDependsOn paths are pinned by the ported TaskStoreTest instead.
cd "$PROJ"
mkdir -p "$OUT/xml/gw"

cat >"$PLANS/plan-42.md" <<'EOF'
# Plan 42 — gw mutation fixture

## Tasks

### Task 1: Parse the input [High]

Core slice.

### Task 2: Write the output [Medium]

*Depends-on: 1*

Serialisation slice.

### Task 3: Wire the flow

*Depends-on: 1, 2*

No risk tag (empty risk value), multi-id depends-on.
EOF

gw_step() { # gw_step <capture-name> <cli args...>
    local cap="$1"; shift
    "$SS" "$@" >/dev/null
    cp "$PLANS/plan-42-tasks.xml" "$OUT/xml/gw/$cap.xml"
}

"$SS" plan init --plan 42 --tasks-from "$PLANS/plan-42.md" >/dev/null
cp "$PLANS/plan-42-tasks.xml" "$OUT/xml/gw/step-00-init.xml"
gw_step step-01-status-in-progress  task status     --plan 42 --task 1 --status in-progress
gw_step step-02-comment-escapables  task comment    --plan 42 --task 1 --message "Special chars: & < > \" ' and unicode: héllo 🚀"
gw_step step-03-set-commit          task set-commit --plan 42 --task 1 --commit 0123456789abcdef0123456789abcdef01234567
gw_step step-04-status-de-risked    task status     --plan 42 --task 1 --status de-risked
gw_step step-05-deviation-minor     task deviation  --plan 42 --task 1 --type minor --message "Split parsing into two passes"
gw_step step-06-status-agent-coded  task status     --plan 42 --task 1 --status agent-coded
gw_step step-07-deviation-major     task deviation  --plan 42 --task 2 --type major --message "Format spec contradicts implementation"
gw_step step-08-status-needs-triage task status     --plan 42 --task 2 --status needs-triage
gw_step step-09-add-task-with-deps  task add        --plan 42 --name "Added after init" --risk low --depends-on 1,3
gw_step step-10-add-task-minimal    task add        --plan 42 --name "Bare addition"
gw_step step-11-status-abandoned    task status     --plan 42 --task 3 --status abandoned
gw_step step-12-comment-appends     task comment    --plan 42 --task 1 --message "Second comment appends"
gw_step step-13-update-message      plan update     --plan 42 --message "Mid-plan checkpoint"
gw_step step-14-update-blocked      plan update     --plan 42 --blocked --message "Blocked on format decision"
gw_step step-15-update-in-review    plan update     --plan 42 --status in-review --message "Ready for review"
gw_step step-16-status-closed       task status     --plan 42 --task 1 --status closed
gw_step step-17-update-complete     plan update     --plan 42 --status complete --message "Done"

echo "Fixture corpus written to $OUT/xml and $OUT/transcripts"
