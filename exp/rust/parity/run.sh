#!/usr/bin/env bash
# Parity harness (plan-102 Task 2 skeleton; store comparisons = plan-106 Task 8).
# Replays every plan-85 store-resolution branch (the Task 1 fixture scenarios)
# through the Java CLI and the Rust binary and diffs stdout, stderr, exit code,
# and the resulting shipsmooth.toml. Outputs embed absolute fixture paths, so
# each scenario is rebuilt AT THE SAME PATH for each implementation — captures
# must then be byte-identical.
set -euo pipefail

SS_JAVA="${SS_JAVA:-$HOME/.cache/shipsmooth/0.3.36/bin/shipsmooth}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SS_RUST="${SS_RUST:-$HERE/../target/debug/shipsmooth}"

[ -x "$SS_JAVA" ] || { echo "Java CLI not found at $SS_JAVA (override via SS_JAVA=)" >&2; exit 1; }
[ -x "$SS_RUST" ] || { echo "Rust binary not built — run: cargo build" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

FAILED=0

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

mkdir -p "$WORK/cap"
for s in $STORE_SCENARIOS; do
    compare_store_scenario "$s"
done

if [ "$FAILED" != 0 ]; then
    echo "parity: FAILURES above" >&2
    exit 1
fi
echo "parity: all store scenarios byte-identical (java vs rust)"
