#!/usr/bin/env bash
# Parity harness (plan-102 Task 2 skeleton; store comparisons = plan-106 Task 8).
# Replays every plan-85 store-resolution branch (the Task 1 fixture scenarios)
# through the Java CLI and the Rust binary and diffs stdout, stderr, exit code,
# and the resulting shipsmooth.toml. Outputs embed absolute fixture paths, so
# each scenario is rebuilt AT THE SAME PATH for each implementation — captures
# must then be byte-identical.
set -euo pipefail

# The JVM transcodes non-ASCII output (the em dashes in the decision prompts)
# to '?' under a non-UTF-8 locale, while Rust always writes UTF-8 — a
# difference in the harness's environment, not in the implementations. Pin a
# UTF-8 locale so the comparison reflects the code.
export LANG="${LANG:-C.UTF-8}" LC_ALL="${LC_ALL:-C.UTF-8}"

SS_JAVA="${SS_JAVA:-$HOME/.cache/shipsmooth/0.3.36/bin/shipsmooth}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SS_RUST="${SS_RUST:-$HERE/../target/debug/shipsmooth}"

[ -x "$SS_JAVA" ] || { echo "Java CLI not found at $SS_JAVA (override via SS_JAVA=)" >&2; exit 1; }
[ -x "$SS_RUST" ] || { echo "Rust binary not built — run: cargo build" >&2; exit 1; }

FAILED=0
WORK="$(mktemp -d)"
# On failure the work dir is kept so the diffed captures can be inspected.
trap '[ "$FAILED" = 0 ] && rm -rf "$WORK" || echo "captures kept at $WORK" >&2' EXIT

# --- scenario machinery -------------------------------------------------------
# One fixed base dir per scenario, wiped and rebuilt identically before each
# implementation's run so absolute paths in the output match byte-for-byte.

SPROJ=""; SCFG=""
scenario_reset() { # scenario_reset <scenario>
    local base="$WORK/store/$1"
    SPROJ="$base/proj"
    SCFG="$base/confighome"
    rm -rf "$base"
    mkdir -p "$SPROJ" "$SCFG"
    git -C "$SPROJ" init -q .
    git -C "$SPROJ" -c user.email=fixture@example.com -c user.name=Fixture \
        commit -q --allow-empty -m "seed"
}

scenario_toml() { # hand-written config for the malformed branches; reads stdin
    mkdir -p "$SCFG/shipsmooth"
    cat >"$SCFG/shipsmooth/shipsmooth.toml"
}

run_one() { # run_one <bin> <capdir> <capture-name> <cli args...>
    local bin="$1" dir="$2" cap="$3"; shift 3
    mkdir -p "$dir"
    set +e
    (cd "$SPROJ" && XDG_CONFIG_HOME="$SCFG" "$bin" "$@" \
        >"$dir/$cap.out" 2>"$dir/$cap.err"; echo "exit=$?" >"$dir/$cap.exit")
    set -e
}

# capture_scenario <bin> <capdir> <scenario>: rebuild the scenario from scratch
# and run its command sequence, capturing every invocation.
capture_scenario() {
    local bin="$1" dir="$2" name="$3"
    scenario_reset "$name"
    case "$name" in
        clean-first-run) ;;
        empty-config)
            mkdir -p "$SCFG/shipsmooth"
            : >"$SCFG/shipsmooth/shipsmooth.toml" ;;
        settled-same-repo)
            run_one "$bin" "$dir" init store init --type same-repo --json ;;
        settled-separate-dir)
            run_one "$bin" "$dir" init store init --type separate-dir --json ;;
        in-repo-not-set-up)
            run_one "$bin" "$dir" init store init --type same-repo --json
            rm -rf "$SPROJ/.shipsmooth" ;;
        config-dir-missing)
            run_one "$bin" "$dir" init store init --type separate-dir --json
            rm -rf "$WORK/store/$name/proj-shipsmooth" ;;
        malformed-missing-type)
            scenario_toml <<EOF
[[projects]]
localPath = "$SPROJ"
EOF
            ;;
        malformed-bad-type)
            scenario_toml <<EOF
[[projects]]
localPath = "$SPROJ"
storageType = "cloud"
EOF
            ;;
        malformed-same-repo-with-root)
            scenario_toml <<EOF
[[projects]]
localPath = "$SPROJ"
storageType = "same-repo"
storageRoot = "$WORK/store/$name/proj-shipsmooth"
EOF
            ;;
        legacy-agents-tree)
            mkdir -p "$SPROJ/.agents/plans" ;;
        *) echo "unknown scenario: $name" >&2; exit 1 ;;
    esac
    run_one "$bin" "$dir" info      store info
    run_one "$bin" "$dir" info-json store info --json
    if [ -f "$SCFG/shipsmooth/shipsmooth.toml" ]; then
        # The schema location embeds the writing CLI's own version, which is
        # allowed to differ between the two implementations; normalise it so
        # the rest of the file stays byte-checked.
        sed -E 's|(shipsmooth/)v[0-9]+\.[0-9]+\.[0-9]+(/dist)|\1v<VERSION>\2|' \
            "$SCFG/shipsmooth/shipsmooth.toml" >"$dir/shipsmooth.toml"
    fi
}

compare_store_scenario() { # run Java then Rust at the same paths; diff captures
    local name="$1"
    local jdir="$WORK/cap/java/$name" rdir="$WORK/cap/rust/$name"
    capture_scenario "$SS_JAVA" "$jdir" "$name"
    capture_scenario "$SS_RUST" "$rdir" "$name"
    if diff -r "$jdir" "$rdir" >"$WORK/cap/$name.diff" 2>&1; then
        echo "parity ok: store/$name"
    else
        echo "PARITY FAIL: store/$name"
        cat "$WORK/cap/$name.diff"
        FAILED=1
    fi
}

STORE_SCENARIOS="clean-first-run empty-config settled-same-repo
settled-separate-dir in-repo-not-set-up config-dir-missing
malformed-missing-type malformed-bad-type malformed-same-repo-with-root
legacy-agents-tree"

# --- task scenarios (plan-108 Task 7) -----------------------------------------
# The first parity coverage of a STATE-DEPENDENT command family: the store
# scenarios only ever exercised the gate's independent-command side.
#
# Task commands need a settled store and an existing plan. `plan init` is not
# ported yet (next slice), so the seed is always run with the JAVA binary for
# both implementations — the starting XML is therefore identical by
# construction, and only the command under test varies.

TASK_PLAN=901

# ACCEPTED DIVERGENCE (plan-108 Task 7). On the error paths Java does not
# handle explicitly, the exception escapes to picocli's default execution
# handler, which dumps a full JVM stack trace to stderr; Rust prints the
# CLI's one-line `shipsmooth: <message>`. A JVM stack trace cannot be
# reproduced (nor is it a contract — it names Java classes and line
# numbers), so for these scenarios stderr is reduced to "was a diagnostic
# emitted at all". Exit code, stdout, and the resulting XML stay byte-checked,
# and `status-bad` is deliberately NOT on this list: Java validates the
# status itself and prints a clean message, so that one matches byte-for-byte.
STDERR_DIVERGES="deviation-bad unknown-task unknown-plan"

# Seeding runs under `set -e`, so each step is guarded explicitly: a bare
# failing command would abort the whole harness with no diagnostic, and an
# unnoticed bad seed would otherwise compare two identically-broken runs.
seed_step() { # seed_step <scenario> <description> <cli args...>
    local name="$1" what="$2"; shift 2
    (cd "$SPROJ" && XDG_CONFIG_HOME="$SCFG" "$SS_JAVA" "$@") >/dev/null 2>&1 || {
        echo "seed failed for task/$name: $what (\`$SS_JAVA $*\`)" >&2
        exit 1
    }
}

task_scenario_seed() { # task_scenario_seed <scenario>
    local name="$1"
    scenario_reset "$name"
    seed_step "$name" "store init" store init --type same-repo --json
    mkdir -p "$SPROJ/.shipsmooth/plans"
    cat >"$SPROJ/.shipsmooth/plans/plan-$TASK_PLAN.md" <<EOF
### Task 1: Seed task [High]

### Task 2: Second task [Low]
*Depends-on: 1*
EOF
    seed_step "$name" "plan init" \
        plan init --plan "$TASK_PLAN" --tasks-from ".shipsmooth/plans/plan-$TASK_PLAN.md"
    # Belt and braces: a zero exit that wrote nothing would be just as bad.
    local seeded="$SPROJ/.shipsmooth/plans/plan-$TASK_PLAN-tasks.xml"
    [ -s "$seeded" ] || {
        echo "seed failed for task/$name: $seeded is missing or empty" >&2
        exit 1
    }
}

# Mutation timestamps come from the wall clock, so the two runs legitimately
# differ there (the same call the gw golden-replay pins with an injected
# clock — unavailable through the CLI). Normalise only the timestamp and
# created-date text; everything else in the file stays byte-checked.
normalise_task_xml() { # normalise_task_xml <src> <dest>
    sed -E -e 's|<timestamp>[^<]*</timestamp>|<timestamp>NORMALISED</timestamp>|g' \
           -e 's|<created>[^<]*</created>|<created>NORMALISED</created>|g' \
           "$1" >"$2"
}

capture_task_scenario() { # capture_task_scenario <bin> <capdir> <scenario>
    local bin="$1" dir="$2" name="$3"
    task_scenario_seed "$name"
    case "$name" in
        add)          run_one "$bin" "$dir" cmd task add --plan "$TASK_PLAN" --name "Added task" --risk medium ;;
        add-depends)  run_one "$bin" "$dir" cmd task add --plan "$TASK_PLAN" --name "Dependent task" --risk low --depends-on 1,2 ;;
        add-no-risk)  run_one "$bin" "$dir" cmd task add --plan "$TASK_PLAN" --name "Bare task" ;;
        status)       run_one "$bin" "$dir" cmd task status --plan "$TASK_PLAN" --task 1 --status agent-coded ;;
        status-bad)   run_one "$bin" "$dir" cmd task status --plan "$TASK_PLAN" --task 1 --status bogus ;;
        comment)      run_one "$bin" "$dir" cmd task comment --plan "$TASK_PLAN" --task 1 --message "looks good" ;;
        comment-markup) run_one "$bin" "$dir" cmd task comment --plan "$TASK_PLAN" --task 2 --message '<b>&"quoted"</b>' ;;
        deviation)    run_one "$bin" "$dir" cmd task deviation --plan "$TASK_PLAN" --task 1 --type minor --message "split in two" ;;
        deviation-bad) run_one "$bin" "$dir" cmd task deviation --plan "$TASK_PLAN" --task 1 --type bogus --message x ;;
        set-commit)   run_one "$bin" "$dir" cmd task set-commit --plan "$TASK_PLAN" --task 1 --commit abc1234 ;;
        set-commit-branch) run_one "$bin" "$dir" cmd task set-commit --plan "$TASK_PLAN" --task 1 --commit abc1234 --branch t/some-branch ;;
        unknown-task) run_one "$bin" "$dir" cmd task comment --plan "$TASK_PLAN" --task 99 --message x ;;
        unknown-plan) run_one "$bin" "$dir" cmd task comment --plan 999 --task 1 --message x ;;
        *) echo "unknown task scenario: $name" >&2; exit 1 ;;
    esac
    normalise_task_xml "$SPROJ/.shipsmooth/plans/plan-$TASK_PLAN-tasks.xml" "$dir/plan-tasks.xml"
    case " $STDERR_DIVERGES " in
        *" $name "*) normalise_diagnostic "$dir/cmd.err" ;;
    esac
}

normalise_diagnostic() { # normalise_diagnostic <capture>
    if [ -s "$1" ]; then
        echo "<non-empty diagnostic>" >"$1"
    else
        echo "<empty>" >"$1"
    fi
}

compare_task_scenario() {
    local name="$1"
    local jdir="$WORK/cap/java/task-$name" rdir="$WORK/cap/rust/task-$name"
    capture_task_scenario "$SS_JAVA" "$jdir" "$name"
    capture_task_scenario "$SS_RUST" "$rdir" "$name"
    if diff -r "$jdir" "$rdir" >"$WORK/cap/task-$name.diff" 2>&1; then
        echo "parity ok: task/$name"
    else
        echo "PARITY FAIL: task/$name"
        cat "$WORK/cap/task-$name.diff"
        FAILED=1
    fi
}

TASK_SCENARIOS="add add-depends add-no-risk status status-bad comment
comment-markup deviation deviation-bad set-commit set-commit-branch
unknown-task unknown-plan"

mkdir -p "$WORK/cap"
COUNT=0
for s in $STORE_SCENARIOS; do
    compare_store_scenario "$s"
    COUNT=$((COUNT + 1))
done
for s in $TASK_SCENARIOS; do
    compare_task_scenario "$s"
    COUNT=$((COUNT + 1))
done

if [ "$FAILED" != 0 ]; then
    echo "parity: FAILURES above" >&2
    exit 1
fi
echo "parity: all $COUNT scenarios byte-identical (java vs rust)"
