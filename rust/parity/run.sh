#!/usr/bin/env bash
# Parity harness skeleton (plan-102 Task 2; real checks land with the command
# ports). Runs the SAME subcommand through the Java CLI and the Rust binary in
# clones of the same fixture project, then diffs stdout, exit code, and the
# resulting plans tree. Machine contracts must be byte-identical; help text is
# allowlisted to drift.
set -euo pipefail

SS_JAVA="${SS_JAVA:-$HOME/.cache/shipsmooth/0.3.34/bin/shipsmooth}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SS_RUST="${SS_RUST:-$HERE/../target/debug/shipsmooth}"

[ -x "$SS_RUST" ] || { echo "Rust binary not built — run: cargo build" >&2; exit 1; }

compare() { # compare <name> <cli args...>
    local name="$1"; shift
    local out_j out_r rc_j=0 rc_r=0
    out_j="$("$SS_JAVA" "$@" 2>&1)" || rc_j=$?
    out_r="$("$SS_RUST" "$@" 2>&1)" || rc_r=$?
    if [ "$rc_j" != "$rc_r" ] || [ "$out_j" != "$out_r" ]; then
        echo "PARITY FAIL: $name (java rc=$rc_j, rust rc=$rc_r)"
        diff <(printf '%s' "$out_j") <(printf '%s' "$out_r") || true
        return 1
    fi
    echo "parity ok: $name"
}

# Skeleton check: only --version exists on the Rust side so far, and its output
# format intentionally differs until the version-rendering port lands — so this
# is a smoke invocation, not a comparison, for now.
"$SS_RUST" --version >/dev/null
echo "parity harness skeleton ok (real comparisons land with the command ports)"
