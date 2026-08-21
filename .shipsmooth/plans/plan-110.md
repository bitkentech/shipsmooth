# Plan 110 — Rust↔Java parity: close the gaps the harness never looked at

## Context

**Backlog feature:** Rust migration of the shipsmooth CLI — the ongoing
`exp/rust` port tracked through plans 102 (explore), 106 (store), 107 (gw),
108 (task), 109 (plan). This plan continues that line of work: it hardens the
parity *verification* rather than porting new surface.

`exp/rust` reached CLI feature-completeness in plan-109 — every Java command
group now has a Rust equivalent. Parity is enforced by
`exp/rust/parity/run.sh`, which rebuilds each scenario **at the same absolute
path** for both implementations and byte-diffs stdout, stderr, exit code and
resulting files.

**Verified baseline (2026-08-21, at main `e9245cf`, freshly built):**

- `cargo test --workspace` → 271 tests, 0 failures
- `parity/run.sh` vs Java 0.3.36 → **45/45 byte-identical**

Parity is genuinely good *within* the harness. The problem is what the harness
never invokes. Probing both binaries by hand outside it surfaced real
divergences — the 45 scenarios exercise happy paths and a few error paths, but
never touch help/version, bare invocations, several `store init` branches, or
separate-dir mode for the `plan`/`task` families.

Everything below was **reproduced by running both binaries**, not inferred
from source.

### Confirmed divergences

| # | Case | Java | Rust |
|---|---|---|---|
| 1 | `store init --type recreate` (off-menu) | `…current situation (CLEAN_FIRST_RUN)` | `…(CleanFirstRun)` |
| 2 | bare `shipsmooth` | usage → stderr, **exit 2** | prints **nothing**, **exit 0** |
| 3 | bare `shipsmooth store\|plan\|task` | usage → stderr, **exit 0** | clap error, **exit 2** |
| 4 | `--version` | `0.3.36` | `shipsmooth 0.3.34` |

(1) is a real content bug: `exp/rust/crates/ss-cli/src/store/init.rs:47`
formats the enum with `{:?}` (Rust Debug) where Java renders the
SCREAMING_SNAKE_CASE enum name. `UndecidableSituation` already has a `token()`
for the kebab-case wire contract, but no Java-`toString()` twin. The JSON gate
contract is unaffected — verified.

(2) is the worst of these: a silent success where Java tells the user what to do.

### Claims tested and found FALSE — do not act on them

- **Malformed `shipsmooth.toml` does *not* diverge.** Both treat a
  syntactically-invalid config as absent (the plan-87 leniency). Verified by
  hand; an earlier analysis claimed Rust silently ignores it while Java errors.
- Usage-error *exit codes* already match (both 2) for unknown subcommand and
  unknown option, even though the wording differs.

This repeats plan-109's lesson about the non-existent `plan tag` defect:
**check a recorded claim against a running binary before acting on it.**

### Accepted divergences — document, do not chase

- **Help layout** is framework-shaped (picocli vs clap): different ordering,
  trailing periods, clap's extra `help` pseudo-subcommand. Skills invoke
  commands, not help, so this is not a contract. Decided with the user.
- **`probe`** is Rust-only by design (plan-102 footprint spike, hidden). No
  Java counterpart exists, so it can never be parity-tested.
- **Java stack traces** on paths picocli's default handler swallows — already
  handled by the harness's `STDERR_DIVERGES` allowlists.

### Scope note

`exp/rust` is an experiment. The Java CLI stays the shipped artifact. This plan
does **not** start packaging, installer or release work for the Rust tree —
`00-overview.md` is explicit that feature parity is not a cutover trigger.

## Coverage threshold

95% for net-new Rust code (per the repo's standing bar; ported code matches
Java's coverage instead). Clippy-clean is the gate, not `cargo fmt`.

---

### Task 1: Fix the `{:?}` situation-enum leak [High]

`exp/rust/crates/ss-cli/src/store/init.rs:47` renders `UndecidableSituation`
with `{:?}`, leaking Rust CamelCase into a user-facing message where Java
prints the SCREAMING_SNAKE_CASE enum name.

Add a `Display` impl (or a `java_name()` method mirroring the existing
`token()`) on `UndecidableSituation` in
`exp/rust/crates/ss-cli/src/ds/resolution.rs` returning `CLEAN_FIRST_RUN`,
`CONFIG_DIR_MISSING`, `IN_REPO_NOT_SET_UP`. Use it at the call site.

Reuse the existing `UndecidableSituation::ALL` const for an exhaustiveness
test so a future variant cannot silently regress. Leave `token()` untouched —
it serves the JSON wire contract and must stay kebab-case.

Rated High because it is a user-visible content divergence on a path no test
covers, and the fix must not disturb the adjacent wire contract.

### Task 2: Fix bare-invocation behaviour [High]

*Depends-on: 1*

Two separate defects in `exp/rust/crates/ss-cli/src/main.rs`:

- **Bare root** must print usage to **stderr** and exit **2**. Today `run()`
  matches `Ok(_cli)` and returns 0 silently — a silent no-op where Java tells
  the user what to do.
- **Bare groups** (`store`/`plan`/`task`): Java prints usage to stderr and
  exits **0** (pinned Java-side by `GroupedCommandTreeTest`). Rust's
  non-optional `#[command(subcommand)]` yields exit 2. Make each group's
  subcommand optional and render its help to stderr with exit 0.

Note the deliberate asymmetry: root exits 2, groups exit 0. That is Java's
actual behaviour, verified by running it — port it as-is rather than
normalising it into consistency.

### Task 3: Align `--version` output format [Medium]

*Depends-on: 1*

Emit a bare version number (`0.3.36`-style) with no `shipsmooth ` prefix, via
a custom clap `version` string or by intercepting the flag.

The stale *value* is a separate matter: the Cargo workspace version is pinned
at `0.3.34` while `gradle.properties` is `0.3.36`. Per the plan-106 decision
the Cargo version is deliberately **not** synced to Java releases — so fix
only the format, leave the number, and add a comment recording why the two
differ so the next reader does not "fix" it.

### Task 4: Extend the parity harness [High]

*Depends-on: 2,3*

Add scenarios to `exp/rust/parity/run.sh`, following its existing shape
(`scenario_reset`, `run_one`, same-path rebuild, then `diff -r`).

**New `store` scenarios** — the biggest untested block:
`init-recreate` (`--type recreate` end to end — the whole action is never
run), `init-with-path` (`--type separate-dir --path <dir>`, the user-supplied
branch of `resolvePath`), `init-off-menu` (pins Task 1), `init-unknown-type`,
`init-already-settled`, `init-missing-type`.

**New `cli` scenario group** — bare invocations and `--version`, pinning
Tasks 2–3. Compare exit code and stream, **not** help body text (accepted
divergence). Add a normaliser reducing captured usage output to "was usage
emitted, on which stream", mirroring the existing `normalise_diagnostic`.

**Separate-dir coverage for `plan` and `task`** — every
`task_scenario_seed`/`plan_scenario_seed` hardcodes `--type same-repo`, so the
`ShipsmoothDataLocator` separate-dir layout branch is never parity-checked for
any state-dependent command. Parameterise the seed by storage type and run a
representative subset (`plan init`, `plan show`, `task comment`) under both. I
hand-verified this path works, so expect it to pass — the value is locking it
down.

**Seed the task scenarios with the implementation under test.** `seed_step`
(run.sh:160) still hardcodes `$SS_JAVA`, so both sides start from a file
*Java* wrote and a Rust `plan init` divergence would be invisible in all 13
task scenarios. The plan scenarios already fixed this (run.sh:276 uses
`$bin`); task never got revisited. Change it to `$bin` to match.

**This was verified safe before planning:** running both implementations'
`plan init` on the same input produces **byte-identical** XML (timestamps
normalised). So the switch closes a blind spot without turning any scenario
red — it is a strictly-stronger test with no expected fallout.

Keep the existing seed guards — an unnoticed bad seed compares two
identically-broken runs, the worst outcome for a harness.

Also remove or schedule the dead `PLAN_STDERR_DIVERGES="show-missing
update-missing"` entries: neither name appears in `PLAN_SCENARIOS`, so that
normalisation list currently does nothing.

### Task 5: Add the real-XML corpus round-trip test [Medium]

*Depends-on: 1*

The repo's own `.shipsmooth/plans/*.xml` (89 files) is a far larger corpus
than the 8 committed fixtures, and it is free. All 89 were run through
`shipsmooth probe --dir`: **88 round-trip clean**.

The one failure, `plan-26-tasks.xml`, is a legacy hand-written file (2-space
indent, `plan="26"` as an **attribute**, no `<plan>` element). **Java rejects
it too** — with a `NullPointerException` stack trace, while still exiting 0.
Both reject it; they differ only in how. Do **not** "fix" the Rust parser to
accept it.

Add a test round-tripping the corpus and asserting 88/89, with `plan-26` named
as a known-legacy exclusion and the reason recorded inline. Guard on the
directory existing so the test skips cleanly in a checkout without it.

### Task 6: Record findings in the migration notes [Low]

*Depends-on: 4,5*

Append a "parity gap audit" section to `docs/rust-migration/00-overview.md`,
matching the existing per-slice findings style: the divergences fixed, the
accepted ones and why, and — importantly — the **disproved** claims
(malformed-TOML), reinforcing the check-against-a-running-binary lesson.
