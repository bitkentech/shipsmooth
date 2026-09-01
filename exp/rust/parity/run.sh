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

HERE="$(cd "$(dirname "$0")" && pwd)"
# The Java side must be a build of the CURRENT tree, not just any release:
# plan-111 (PB-360) made `store init` write a manifest.toml that the installed
# 0.3.36 release does not. Prefer a local `:cli:installDist` launcher, then the
# pinned release; override with SS_JAVA=.
_JAVA_DIST="$HERE/../../../cli/build/install/cli/bin/cli"
if [ -z "${SS_JAVA:-}" ] && [ -x "$_JAVA_DIST" ]; then
    SS_JAVA="$_JAVA_DIST"
fi
SS_JAVA="${SS_JAVA:-$HOME/.cache/shipsmooth/0.3.36/bin/shipsmooth}"
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
        # plan-110 Task 4: the `store init` action branches the earlier
        # scenarios only ever set up state for — never drove end to end.
        init-off-menu)
            # `recreate` is a real type token, just not one CLEAN_FIRST_RUN
            # offers; Java names the situation (pins Task 1 — no `{:?}` leak).
            run_one "$bin" "$dir" init store init --type recreate ;;
        init-recreate)
            run_one "$bin" "$dir" pre store init --type separate-dir --json
            rm -rf "$WORK/store/$name/proj-shipsmooth"
            # The whole RECREATE_MISSING_DIR action: re-provision the dir the
            # config still points at, without rewriting the config.
            run_one "$bin" "$dir" init store init --type recreate --json ;;
        init-with-path)
            # The user-supplied `--path` branch of resolvePath (the proposed
            # path is never consulted).
            run_one "$bin" "$dir" init \
                store init --type separate-dir --path "$WORK/store/$name/custom-state" --json ;;
        init-unknown-type)
            run_one "$bin" "$dir" init store init --type bogus ;;
        init-already-settled)
            run_one "$bin" "$dir" pre  store init --type same-repo --json
            run_one "$bin" "$dir" init store init --type same-repo --json ;;
        init-missing-type)
            run_one "$bin" "$dir" init store init ;;
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
    # PB-360: `store init` also stamps a manifest.toml at the data root. Capture
    # it with only `cli-version` normalised — that field carries the writing
    # CLI's own version (Java 0.3.36 vs Rust 0.3.34, unsynced by design, exactly
    # like the [toml-schema] version above); every other byte stays checked.
    # The data root is <repo>/.shipsmooth in same-repo mode, or the dedicated
    # state dir itself in separate-dir/recreate mode.
    for _mroot in \
        "$SPROJ/.shipsmooth" \
        "$WORK/store/$name/proj-shipsmooth" \
        "$WORK/store/$name/custom-state"; do
        if [ -f "$_mroot/manifest.toml" ]; then
            sed -E "s|^(cli-version = ').*(')\$|\1<VERSION>\2|" \
                "$_mroot/manifest.toml" >"$dir/manifest.toml"
            break
        fi
    done
    # A missing required `--type` is a framework error: picocli writes
    # "Missing required option '--type=TYPE'" + its own usage, clap writes
    # "error: the following required arguments were not provided". Same exit
    # (2), same stream (stderr) — the wording is not a contract, so reduce it
    # to presence. Every OTHER new init scenario is a path Java handles
    # explicitly with a clean one-liner, so those stay byte-checked.
    if [ "$name" = init-missing-type ] && [ -f "$dir/init.err" ]; then
        normalise_usage "$dir/init.err"
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
legacy-agents-tree
init-off-menu init-recreate init-with-path init-unknown-type
init-already-settled init-missing-type"

# --- task scenarios (plan-108 Task 7; plan-110 Task 4) ----------------------
# The first parity coverage of a STATE-DEPENDENT command family: the store
# scenarios only ever exercised the gate's independent-command side.
#
# Task commands need a settled store and an existing plan. plan-108 seeded
# both sides with the JAVA binary because `plan init` was not ported yet;
# plan-109 ported it and plan-110 Task 4 switches the seed to the
# implementation UNDER TEST (`$bin`). Verified safe first: `plan init` on the
# same input produces byte-identical XML (timestamps normalised), so the
# switch only closes a blind spot — a Rust `plan init` regression now shows
# up in the seeded file instead of hiding behind an identical Java-written
# start. The seed guards stay: an unnoticed bad seed would compare two
# identically-broken runs.
#
# Task 4 also runs `plan init` / `plan show` / `task comment` under BOTH
# storage types (the `-sepdir` scenarios): every earlier seed hardcoded
# same-repo, so the ShipsmoothDataLocator separate-dir layout branch was
# never parity-checked for any state-dependent command.

TASK_PLAN=901

# The plans directory for the current scenario's storage type — same-repo
# keeps it under the repo's .shipsmooth/, separate-dir puts it directly under
# the external state root (ShipsmoothDataLocator.dataRoot()).
SPLANS=""
set_splans() { # set_splans <scenario> <storage>
    case "$2" in
        same-repo)    SPLANS="$SPROJ/.shipsmooth/plans" ;;
        separate-dir) SPLANS="$WORK/store/$1/proj-shipsmooth/plans" ;;
        *) echo "unknown storage '$2' for scenario $1" >&2; exit 1 ;;
    esac
}

# storage_for <scenario>: a `-sepdir` suffix selects separate-dir.
storage_for() { case "$1" in *-sepdir) echo separate-dir ;; *) echo same-repo ;; esac; }

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
seed_step() { # seed_step <bin> <scenario> <description> <cli args...>
    local bin="$1" name="$2" what="$3"; shift 3
    (cd "$SPROJ" && XDG_CONFIG_HOME="$SCFG" "$bin" "$@") >/dev/null 2>&1 || {
        echo "seed failed for task/$name: $what (\`$bin $*\`)" >&2
        exit 1
    }
}

task_scenario_seed() { # task_scenario_seed <bin> <scenario> [storage]
    local bin="$1" name="$2" storage="${3:-same-repo}"
    scenario_reset "$name"
    set_splans "$name" "$storage"
    seed_step "$bin" "$name" "store init" store init --type "$storage" --json
    # The plan markdown is just an input to `plan init`; keep it at a fixed
    # in-repo path so the command line is identical across storage types. The
    # resulting -tasks.xml lands in the resolved plans dir (SPLANS), which is
    # what we read back.
    mkdir -p "$SPROJ/.shipsmooth/plans" "$SPLANS"
    cat >"$SPROJ/.shipsmooth/plans/plan-$TASK_PLAN.md" <<EOF
### Task 1: Seed task [High]

### Task 2: Second task [Low]
*Depends-on: 1*
EOF
    seed_step "$bin" "$name" "plan init" \
        plan init --plan "$TASK_PLAN" --tasks-from ".shipsmooth/plans/plan-$TASK_PLAN.md"
    # Belt and braces: a zero exit that wrote nothing would be just as bad.
    local seeded="$SPLANS/plan-$TASK_PLAN-tasks.xml"
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
    task_scenario_seed "$bin" "$name" "$(storage_for "$name")"
    case "$name" in
        add)          run_one "$bin" "$dir" cmd task add --plan "$TASK_PLAN" --name "Added task" --risk medium ;;
        add-depends)  run_one "$bin" "$dir" cmd task add --plan "$TASK_PLAN" --name "Dependent task" --risk low --depends-on 1,2 ;;
        add-no-risk)  run_one "$bin" "$dir" cmd task add --plan "$TASK_PLAN" --name "Bare task" ;;
        status)       run_one "$bin" "$dir" cmd task status --plan "$TASK_PLAN" --task 1 --status agent-coded ;;
        status-bad)   run_one "$bin" "$dir" cmd task status --plan "$TASK_PLAN" --task 1 --status bogus ;;
        comment|comment-sepdir)
                      run_one "$bin" "$dir" cmd task comment --plan "$TASK_PLAN" --task 1 --message "looks good" ;;
        comment-markup) run_one "$bin" "$dir" cmd task comment --plan "$TASK_PLAN" --task 2 --message '<b>&"quoted"</b>' ;;
        deviation)    run_one "$bin" "$dir" cmd task deviation --plan "$TASK_PLAN" --task 1 --type minor --message "split in two" ;;
        deviation-bad) run_one "$bin" "$dir" cmd task deviation --plan "$TASK_PLAN" --task 1 --type bogus --message x ;;
        set-commit)   run_one "$bin" "$dir" cmd task set-commit --plan "$TASK_PLAN" --task 1 --commit abc1234 ;;
        set-commit-branch) run_one "$bin" "$dir" cmd task set-commit --plan "$TASK_PLAN" --task 1 --commit abc1234 --branch t/some-branch ;;
        unknown-task) run_one "$bin" "$dir" cmd task comment --plan "$TASK_PLAN" --task 99 --message x ;;
        unknown-plan) run_one "$bin" "$dir" cmd task comment --plan 999 --task 1 --message x ;;
        *) echo "unknown task scenario: $name" >&2; exit 1 ;;
    esac
    normalise_task_xml "$SPLANS/plan-$TASK_PLAN-tasks.xml" "$dir/plan-tasks.xml"
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

# --- plan scenarios (plan-109 Task 9) -----------------------------------------
# The last command family, and the first that can seed with the implementation
# UNDER TEST: `plan init` is ported now, so each side builds its own starting
# XML. That is strictly stronger than the task scenarios' Java-only seed — a
# `plan init` divergence shows up in the seeded file rather than hiding behind
# an identical Java-written start.
#
# Several of these create branches and tags; scenario_reset rebuilds the repo
# from scratch per scenario, so they cannot leak into one another.

PLAN_NUM=801

plan_scenario_seed() { # plan_scenario_seed <bin> <scenario>
    local bin="$1" name="$2" storage
    storage="$(storage_for "$name")"
    scenario_reset "$name"
    set_splans "$name" "$storage"
    (cd "$SPROJ" && XDG_CONFIG_HOME="$SCFG" "$bin" store init --type "$storage" --json) >/dev/null 2>&1 || {
        echo "seed failed for plan/$name: store init ($storage)" >&2
        exit 1
    }
    # The plan markdown is an input file kept at a fixed in-repo path; the
    # -tasks.xml `plan init` writes lands in the resolved plans dir (SPLANS).
    mkdir -p "$SPROJ/.shipsmooth/plans" "$SPLANS"
    cat >"$SPROJ/.shipsmooth/plans/plan-$PLAN_NUM.md" <<EOF
### Task 1: Seed task [High]

### Task 2: Second task [Low]
*Depends-on: 1*
EOF
}

# Seed AND initialise, for the scenarios that need an existing plan XML.
plan_scenario_seed_initialised() { # <bin> <scenario>
    local bin="$1" name="$2"
    plan_scenario_seed "$bin" "$name"
    (cd "$SPROJ" && XDG_CONFIG_HOME="$SCFG" "$bin" \
        plan init --plan "$PLAN_NUM" --tasks-from ".shipsmooth/plans/plan-$PLAN_NUM.md") >/dev/null 2>&1 || {
        echo "seed failed for plan/$name: plan init (with $bin)" >&2
        exit 1
    }
}

# Commit everything so `preflight`'s clean-tree condition can be satisfied.
plan_commit_all() {
    git -C "$SPROJ" add -A
    git -C "$SPROJ" -c user.email=fixture@example.com -c user.name=Fixture \
        commit -q -m "state" >/dev/null 2>&1 || true
}

capture_plan_scenario() { # capture_plan_scenario <bin> <capdir> <scenario>
    local bin="$1" dir="$2" name="$3"
    case "$name" in
        init|init-sepdir|init-no-tasks|init-near-miss|init-missing-file|quick|tag-version|tag-complete|tag-bad-kind|branch-plan|branch-issue|branch-neither|branch-both|branch-exists|preflight-dirty|preflight-no-tag|preflight-pass|resume-missing)
            plan_scenario_seed "$bin" "$name" ;;
        show|show-sepdir|resume|update|update-blocked|init-twice)
            plan_scenario_seed_initialised "$bin" "$name" ;;
        *) echo "unknown plan scenario: $name" >&2; exit 1 ;;
    esac

    case "$name" in
        init|init-sepdir)
                      run_one "$bin" "$dir" cmd plan init --plan "$PLAN_NUM" --tasks-from ".shipsmooth/plans/plan-$PLAN_NUM.md" ;;
        init-twice)   run_one "$bin" "$dir" cmd plan init --plan "$PLAN_NUM" --tasks-from ".shipsmooth/plans/plan-$PLAN_NUM.md" ;;
        init-no-tasks)
            printf 'no headings here at all\n' >"$SPROJ/.shipsmooth/plans/plan-$PLAN_NUM.md"
            run_one "$bin" "$dir" cmd plan init --plan "$PLAN_NUM" --tasks-from ".shipsmooth/plans/plan-$PLAN_NUM.md" ;;
        init-near-miss)
            printf '### Task 1: Good [Low]\n\n## Task 2: Wrong level [Low]\n\nDepends-on: 1\n' \
                >"$SPROJ/.shipsmooth/plans/plan-$PLAN_NUM.md"
            run_one "$bin" "$dir" cmd plan init --plan "$PLAN_NUM" --tasks-from ".shipsmooth/plans/plan-$PLAN_NUM.md" ;;
        init-missing-file)
            run_one "$bin" "$dir" cmd plan init --plan "$PLAN_NUM" --tasks-from "no-such-file.md" ;;
        quick)        run_one "$bin" "$dir" cmd plan quick --desc "Desktop UI" ;;
        tag-version)  run_one "$bin" "$dir" cmd plan tag --plan "$PLAN_NUM" --kind version ;;
        tag-complete) run_one "$bin" "$dir" cmd plan tag --plan "$PLAN_NUM" --kind complete ;;
        tag-bad-kind) run_one "$bin" "$dir" cmd plan tag --plan "$PLAN_NUM" --kind bogus ;;
        branch-plan)  run_one "$bin" "$dir" cmd plan branch --plan 5 --desc "Some Work" ;;
        branch-issue) run_one "$bin" "$dir" cmd plan branch --issue PB-42 --desc "Other Work" ;;
        branch-neither) run_one "$bin" "$dir" cmd plan branch --desc "Some Work" ;;
        branch-both)  run_one "$bin" "$dir" cmd plan branch --issue PB-1 --plan 2 --desc "Some Work" ;;
        branch-exists)
            git -C "$SPROJ" branch t/5-some-work >/dev/null 2>&1
            run_one "$bin" "$dir" cmd plan branch --plan 5 --desc "Some Work" ;;
        preflight-dirty)
            run_one "$bin" "$dir" cmd plan preflight --plan "$PLAN_NUM" ;;
        preflight-no-tag)
            plan_commit_all
            run_one "$bin" "$dir" cmd plan preflight --plan "$PLAN_NUM" ;;
        preflight-pass)
            plan_commit_all
            git -C "$SPROJ" tag "plan-$PLAN_NUM-v1" >/dev/null 2>&1
            run_one "$bin" "$dir" cmd plan preflight --plan "$PLAN_NUM" ;;
        show|show-sepdir)
                      run_one "$bin" "$dir" cmd plan show --plan "$PLAN_NUM" ;;
        resume)       run_one "$bin" "$dir" cmd plan resume --plan "$PLAN_NUM" ;;
        resume-missing) run_one "$bin" "$dir" cmd plan resume --plan "$PLAN_NUM" ;;
        update)       run_one "$bin" "$dir" cmd plan update --plan "$PLAN_NUM" --status in-review --message "ready" ;;
        update-blocked) run_one "$bin" "$dir" cmd plan update --plan "$PLAN_NUM" --blocked --message "stuck" ;;
    esac

    # show/resume RENDER the project-update timestamp, so the same wall-clock
    # divergence the XML normalises also reaches stdout. Rewrite only
    # timestamp-shaped text; everything else in the summary stays byte-checked.
    if [ -f "$dir/cmd.out" ]; then
        sed -E -i 's/[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}(Z|[+-][0-9]{2}:[0-9]{2})/NORMALISED/g' \
            "$dir/cmd.out"
    fi
    # The XML is only present for scenarios that initialised or wrote one.
    # SPLANS is the storage-type-resolved plans dir (same-repo or separate-dir).
    local xml="$SPLANS/plan-$PLAN_NUM-tasks.xml"
    if [ -f "$xml" ]; then
        normalise_task_xml "$xml" "$dir/plan-tasks.xml"
    fi
    # Branch and tag inventories: several leaves' whole effect is on git refs.
    git -C "$SPROJ" branch --format='%(refname:short)' >"$dir/branches" 2>/dev/null || true
    git -C "$SPROJ" tag --list >"$dir/tags" 2>/dev/null || true
    # `plan quick` writes a stub whose content is contract.
    if [ -f "$SPLANS/plan-1.md" ]; then
        cp "$SPLANS/plan-1.md" "$dir/stub.md"
    fi
    case " $PLAN_STDERR_DIVERGES " in
        *" $name "*) normalise_diagnostic "$dir/cmd.err" ;;
    esac
}

# Same accepted divergence as the task group: where Java lets the exception
# reach picocli's default handler it dumps a JVM stack trace. There are no
# such plan scenarios today — every plan error path Java handles explicitly —
# so this list is empty. (It carried "show-missing update-missing" for a while,
# but neither name was ever in PLAN_SCENARIOS, so the entry normalised nothing.)
PLAN_STDERR_DIVERGES=""

compare_plan_scenario() {
    local name="$1"
    local jdir="$WORK/cap/java/plan-$name" rdir="$WORK/cap/rust/plan-$name"
    capture_plan_scenario "$SS_JAVA" "$jdir" "$name"
    capture_plan_scenario "$SS_RUST" "$rdir" "$name"
    if diff -r "$jdir" "$rdir" >"$WORK/cap/plan-$name.diff" 2>&1; then
        echo "parity ok: plan/$name"
    else
        echo "PARITY FAIL: plan/$name"
        cat "$WORK/cap/plan-$name.diff"
        FAILED=1
    fi
}

PLAN_SCENARIOS="init init-twice init-no-tasks init-near-miss init-missing-file
quick tag-version tag-complete tag-bad-kind branch-plan branch-issue
branch-neither branch-both branch-exists preflight-dirty preflight-no-tag
preflight-pass show resume resume-missing update update-blocked
init-sepdir show-sepdir"

TASK_SCENARIOS="add add-depends add-no-risk status status-bad comment
comment-markup deviation deviation-bad set-commit set-commit-branch
unknown-task unknown-plan comment-sepdir"

# --- CLI-surface scenarios (plan-110 Task 4) --------------------------------
# Bare invocations and `--version` — paths no earlier scenario ever ran, and
# where plan-110 found Rust diverging (silent exit 0 on a bare root; clap's
# missing-subcommand exit 2 on a bare group; a "shipsmooth "-prefixed version).
#
# Help *body* text is a deliberate non-contract (picocli and clap lay out
# usage differently, and skills invoke commands, not help). So these pin only
# the exit code, WHICH STREAM carried output, and — for --version — the shape
# of the printed line (bare digits, no bin-name prefix). The version *value*
# differs by design (Cargo 0.3.34 vs Java 0.3.36), so it is normalised away.

# Reduce a capture to "was output emitted here, and did it look like usage".
# The exact wording stays out of the comparison; presence + shape do not.
normalise_usage() { # normalise_usage <capture>
    if [ ! -s "$1" ]; then
        echo "<empty>" >"$1"
    elif grep -q '^Usage:' "$1" || grep -qi 'usage' "$1"; then
        echo "<usage emitted>" >"$1"
    else
        echo "<non-empty, not usage>" >"$1"
    fi
}

normalise_version() { # normalise_version <capture>  (bare digits -> token)
    local tmp="$1.norm"
    sed -E 's/^[0-9]+\.[0-9]+\.[0-9]+$/<VERSION>/' "$1" >"$tmp"
    mv "$tmp" "$1"
}

capture_cli_scenario() { # capture_cli_scenario <bin> <capdir> <scenario>
    local bin="$1" dir="$2" name="$3"
    # A throwaway repo just to give run_one a cwd; these commands are all
    # state-independent, so the repo contents never matter.
    scenario_reset "cli-$name"
    case "$name" in
        bare-root)    run_one "$bin" "$dir" cmd ;;
        bare-store)   run_one "$bin" "$dir" cmd store ;;
        bare-plan)    run_one "$bin" "$dir" cmd plan ;;
        bare-task)    run_one "$bin" "$dir" cmd task ;;
        version-long) run_one "$bin" "$dir" cmd --version ;;
        version-short) run_one "$bin" "$dir" cmd -V ;;
        *) echo "unknown cli scenario: $name" >&2; exit 1 ;;
    esac
    case "$name" in
        version-long|version-short) normalise_version "$dir/cmd.out"; normalise_usage "$dir/cmd.err" ;;
        *) normalise_usage "$dir/cmd.out"; normalise_usage "$dir/cmd.err" ;;
    esac
}

compare_cli_scenario() {
    local name="$1"
    local jdir="$WORK/cap/java/cli-$name" rdir="$WORK/cap/rust/cli-$name"
    capture_cli_scenario "$SS_JAVA" "$jdir" "$name"
    capture_cli_scenario "$SS_RUST" "$rdir" "$name"
    if diff -r "$jdir" "$rdir" >"$WORK/cap/cli-$name.diff" 2>&1; then
        echo "parity ok: cli/$name"
    else
        echo "PARITY FAIL: cli/$name"
        cat "$WORK/cap/cli-$name.diff"
        FAILED=1
    fi
}

CLI_SCENARIOS="bare-root bare-store bare-plan bare-task version-long version-short"

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
for s in $PLAN_SCENARIOS; do
    compare_plan_scenario "$s"
    COUNT=$((COUNT + 1))
done
for s in $CLI_SCENARIOS; do
    compare_cli_scenario "$s"
    COUNT=$((COUNT + 1))
done

if [ "$FAILED" != 0 ]; then
    echo "parity: FAILURES above" >&2
    exit 1
fi
echo "parity: all $COUNT scenarios byte-identical (java vs rust)"
