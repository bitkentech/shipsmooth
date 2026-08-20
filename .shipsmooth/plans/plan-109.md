# plan-109 — Rust port: `plan` command leaves + the remaining `svc::plan` core

## Context

Feature (in the user's words): *port the next Java CLI module to Rust* — the
`plan` noun group, the last command family, per plan-108's closeout
(00-overview.md §task-slice findings): **"the `plan` leaves, but note it is
not pure wiring: `NewPlan`, `PlanService`, `ScaffoldResult` and
`ScaffoldException` are still unported."**

This is the slice that makes the Rust CLI **feature-complete against the Java
CLI**. Every other package is already ported and parity-verified.

### What already exists (merged, `exp/rust/`)

- `ss-core::model`, `ss-core::gw` (TaskStore, GitState, GitTags, xml_time),
  `ss-core::conf` — all byte-verified (plans 102/106/107).
- `ss-core::plan` — Slugs, PlanNumbers, Stub, PlanSummaryFormatter,
  `markdown::parse_tasks` and `slice_task_section`.
- `ss-cli` — `ds/` resolution chain, `store` group, `task` group, and the
  **generic resolve gate** in `main.rs` (plan-108). The `plan` leaves are
  state-dependent and reuse that gate unchanged — no new wiring needed.
- `parity/run.sh` — 23 scenarios (10 store, 13 task), all byte-identical.

### Scope

**Core, `ss-core::plan` (the gap that makes this more than wiring):**

| Java | Lines | Rust destination |
|---|---|---|
| `PlanMarkdownParser.parseWithDiagnostics` + `Diagnostic`/`ParseResult` | ~70 | `plan::markdown` (extend) |
| `NewPlan` | 61 | `plan::new_plan` |
| `ScaffoldResult` | record | `plan::new_plan` |
| `ScaffoldException` | 13 | `Error::Scaffold` variant |

`parseWithDiagnostics` is **not** ported — today's `parse_tasks` returns tasks
only. `plan init`'s near-miss reporting depends on it, and its heuristics are
a set of deliberately calibrated regexes (the em-dash rule carries a
`calibrated 2026-07-19` comment), so it is the riskiest thing in this slice.

**CLI, `io.bitken.ss.cli.plan` (9 files, 571 lines) → `ss-cli::plan/`:**
`Plan` (group parent), `Init` (99), `Tag` (74), `Preflight` (70), `Branch`
(69), `QuickStart` (58), `Resume` (58), `ProjectUpdate` (47), `Show` (40).

**Java tests in scope (~819 lines):** `NewPlanTest` (121),
`PlanInitDiagnosticsIntegrationTest` (131), `PlanBranchTest` (109),
`PlanTagTest` (108), `PlanQuickStartTest` (98), `PlanPreflightTest` (94),
`PlanResumeTest` (81), `PlanCommandsIntegrationTest` (77).

### Out of scope

- Any shipping path: no release, no installer, no SKILL.md `cliBin` change.
  The Java CLI stays the daily driver. Cutting over is its own follow-up
  (00-overview.md §risks flags the whole packaging/Windows story).
- `ExperimentalModeParser` / experimental gating — currently no leaf is
  experimental (plan-97 removed the guards), so it stays the hidden flag
  `main.rs` already declares.

### Contracts that must stay byte-identical

1. **Near-miss diagnostic text**, verbatim — including the grammar suffix, the
   10-item cap and its `… and N more` line, and the split across streams:
   `plan init` reports diagnostics on **stderr** when it fails (no tasks) and
   on **stdout** when it succeeds.
2. **`plan quick`'s handoff output** (`Created branch: …` / `Wrote stub: …`)
   and its no-commit guarantee — the skill's own thin path depends on both.
3. **`plan preflight`'s FAIL-fast vs WARN-accumulate ordering**: a dirty tree
   or a missing local version tag returns 1 immediately; unpushed-branch and
   tag-not-on-remote are warnings printed *before* the final `PASS`.
4. **Error lines go to stdout, not stderr, for most plan leaves** (`ERROR: …`
   via `System.out`) — the opposite of the `store`/`task` convention. Port as
   observed, not as expected; `plan init` is the exception that uses stderr.
5. **Stub plan-file text** — already ported (`plan::stub`), must stay the
   bytes `plan quick` writes.

### Design decisions

- **Still no `PlanService` struct**, extending plan-108's decision. Its four
  remaining methods are one-liners over `NewPlan` and `TaskStore`
  (`initPlan` = generate + save; `quickStart` = `NewPlan::scaffold`;
  `projectUpdate` / `loadPlan` = existing `TaskStore` calls). This *deviates
  from 01-core.md*, which said "keep, cli leaves call it" — that guidance
  predates the `TaskStore::mutate` seam, which absorbed the reason it existed.
  Recorded here so the deviation is deliberate, not drift.
- **`ScaffoldException` → an `Error::Scaffold(String)` variant**, per
  01-core.md, so `NewPlan::scaffold` returns `Result<ScaffoldResult>` and the
  leaf renders `ERROR: {msg}`.
- **Diagnostics are data, not printing.** `parse_with_diagnostics` returns
  `(Vec<ParsedTask>, Vec<Diagnostic>)`; the leaf decides the stream. This is
  what makes the diagnostic text unit-testable rather than only observable
  through the binary.
- **`--blocked` is tri-state.** picocli's `type(Boolean.class)` with no
  paramLabel is an arity-0 flag: present → `true`, absent → `null`. Rust maps
  it to `Option<bool>`, which `TaskStore::project_update` already accepts.
- **`plan branch` takes exactly one of `--issue` / `--plan`** (Java's XOR via
  `hasIssue == hasPlan` → error). Enforce at dispatch, matching Java's message,
  rather than with a clap arg group whose wording would differ.

### Correction to the migration docs

02-cli.md records a defect to "port as-is": *`plan tag --kind version` derives
the version from the XML field, not git tags.* **This is stale** — `Tag.java`
never touches the XML (verified: zero references), and `GitTags.nextPlanVersion`
derives from `git tag -l 'plan-N-v*' --sort=-version:refname`. There is no
defect to preserve. Task 10 corrects the doc.

### Verification

1. **Ported Java tests green** — ~819 lines across the eight test files above.
2. **Parity harness extension** — `plan` scenarios. This slice reaches a
   milestone the earlier ones could not: with `plan init` ported, the harness
   can seed **both** sides natively instead of always seeding with Java.
   Task 9 keeps the Java-seeded form for the existing `task` scenarios and adds
   Rust-seeded plan scenarios, so a seeding divergence would itself be caught.

Coverage target: **95%** (standing convention; the last three slices landed
96–100%).

## Tasks

### Task 1: parse_with_diagnostics and the near-miss heuristics [High]

*Depends-on: none*

Extend `ss-core::plan::markdown` with `Diagnostic { line, text, reason }` and
`parse_with_diagnostics -> (Vec<ParsedTask>, Vec<Diagnostic>)`. Port every
branch: unrecognised risk tag, non-h3 heading level, non-numeric task id,
missing `:` after the number, bold-text heading, and malformed `depends-on`
(with its three tolerated forms — valid, empty, and `none`). Port
`PlanInitDiagnosticsIntegrationTest`'s expectations as unit tests over the
returned data.

High risk: a pile of interacting regexes tuned against real plan files, with
calibration comments recording past corrections. Wrong heuristics produce
confidently wrong advice on someone's malformed plan, and every `plan init`
message depends on this.

### Task 2: NewPlan, ScaffoldResult and the Scaffold error [High]

*Depends-on: none*

Port `NewPlan::scaffold` over the already-ported `PlanNumbers`, `GitState`,
`Slugs` and `Stub`, plus `ScaffoldResult` and the `Error::Scaffold` variant.
Preserve the ordering guarantee its class comment makes load-bearing: branch
availability is checked **before** the filesystem is touched, so a collision
leaves no stray stub. Port `NewPlanTest` (121 lines) against real temp repos.

High risk: it creates branches and writes files through real git, it is the
only new *core* class in the slice, and `plan quick` — the path this very
skill takes on a thin kickoff — sits directly on it.

### Task 3: `plan init` leaf [Medium]

*Depends-on: 1*

Wire `init`: missing-file check, parse, the no-tasks failure (message +
grammar lines + diagnostics, all to **stderr**, exit 1), otherwise generate and
save via `TaskStore`, print `Written N tasks to <path>` using the resolved
store's path, then diagnostics to **stdout**. Include the 10-item cap and the
`… and N more` line.

Medium: the logic is simple but the stream split, the cap, and the exact
wording are all contract, and this is the leaf the skill itself calls most.

### Task 4: `plan quick` leaf [Medium]

*Depends-on: 2*

Wire `quick` over `NewPlan::scaffold`: handoff lines on success, `ERROR: {msg}`
and exit 1 on a scaffold failure. Port `PlanQuickStartTest` (98 lines).

Medium: thin, but it is the skill's thin-context entry point and must not
acquire the ability to commit — the absence of a git-write collaborator is the
design, and the port must keep it absent.

### Task 5: `plan tag` leaf [Medium]

*Depends-on: none*

Wire `tag`: `--kind version` computes the next vK and refuses when it already
exists; `complete`/`abandoned` create the fixed tag; anything else is an
error. All output on stdout, including errors. Port `PlanTagTest` (108 lines).

Medium: three branches with distinct messages, and the version-derivation path
is the one with real logic behind it (`nextPlanVersion`).

### Task 6: `plan preflight` leaf [Medium]

*Depends-on: none*

Wire `preflight`: FAIL-and-return on a dirty tree, then on a missing local
version tag; accumulate the unpushed-branch and tag-not-on-remote warnings;
print warnings then `PASS`. Port `PlanPreflightTest` (94 lines).

Medium: the fail-fast/warn-accumulate ordering is easy to flatten by accident,
and `GitState::is_branch_pushed_and_not_ahead` is the one read query that emits
a diagnostic (plan-107 finding) — it will surface here.

### Task 7: `plan branch` leaf [Medium]

*Depends-on: none*

Wire `branch`: exactly-one-of `--issue`/`--plan` (lowercasing the issue,
stringifying the plan number), branch-exists and create-failure errors, then
the created/push-line handoff. Port `PlanBranchTest` (109 lines).

Medium: the XOR argument handling is the only place in the CLI with this
shape, and its error wording is asserted.

### Task 8: `plan show`, `resume`, `project-update` and the group parent [Low]

*Depends-on: 2*

Wire the three thin leaves over already-ported pieces (`format_plan_summary`,
`load_plan`, `plan_tasks_file_exists`, `project_update`) plus the `plan`
subcommand enum. Port `PlanResumeTest` (81) and `PlanCommandsIntegrationTest`
(77). Note `--blocked`'s tri-state mapping (see Design decisions).

Low: pure delegation to code already verified byte-identical.

### Task 9: Parity harness: plan scenarios [Medium]

*Depends-on: 3, 4, 5, 6, 7, 8*

Add `plan` scenarios to `parity/run.sh`: init (valid / no-tasks / near-misses /
missing file), quick, tag (each kind + the refusal), preflight (pass / each
fail / warns), branch (both selectors + errors), show, resume (present /
absent), project-update. Switch the seed to the **Rust** binary for the new
scenarios so a `plan init` divergence cannot hide behind a Java-seeded start.

Medium: the first scenarios that create git branches and tags inside the
fixture repos, so the harness needs to keep them isolated; and this is the run
that decides whether the CLI is genuinely feature-complete.

### Task 10: Migration notes write-back [Low]

*Depends-on: 9*

Update `00-overview.md`, `01-core.md` §2 and `02-cli.md`: mark `svc::plan` and
the `plan` group ported, record cost/divergences/decisions (including the
deliberate no-`PlanService` deviation from 01-core.md), **correct the stale
`plan tag` defect claim**, and state plainly that the Rust CLI is now
feature-complete — naming the cutover work (packaging, installers, Windows,
SKILL.md `cliBin`) as the next thing to plan.

Low: documentation only, once everything above is settled.
