# plan-108 — Rust port: `task` command leaves

## Context

Feature (in the user's words): *port the next Java CLI module to Rust* — the
`task` noun group, chosen as the next slice per plan-107's closeout
(00-overview.md §gw-slice findings): **"the `task` command leaves first (6
thin files straight over the `TaskStore` just ported), then `plan`."**

plan-107 ported `ss-core::gw::TaskStore` and verified it byte-identical
against the Java CLI. Every method the `task` CLI leaves call already exists
there. This slice is therefore mostly CLI wiring — with one piece of genuinely
new work: today `ss-cli::main` only dispatches `probe` and `store`, both
state-independent, so the generic resolution gate described in 02-cli.md
(classify a command state-dependent vs. not, resolve once, emit gate JSON +
exit 10/11 when unsettled) has never been built. `task` commands are
state-dependent — this slice has to build that gate before any leaf can
dispatch against a real store.

### What already exists (merged, `exp/rust/`)

- `ss-core::gw::TaskStore` — full mutation surface (`load_plan`, `save_plan`,
  `add_task`, `update_task_status`, `add_comment`, `add_deviation`,
  `set_commit`), byte-verified via golden replay (plan-107).
- `ss-core::gw::GitTags::get_plan_version` — used by `task add` to stamp
  `created-from`.
- `ss-core::conf::ShipsmoothDataLocator` / `ResolvedStateRoot` — resolves
  `plans_dir` / `plan_tasks_file` paths.
- `ss-cli::ds` — the full resolver chain (`DataStoreResolution`,
  `ProjectDataStoreResolver`), `resolution_json` (ready / needs_decision /
  unresolvable), `project::ProjectContext` (plan-106).
- `ss-cli::store` — the only command group wired into `main.rs` so far. Both
  its leaves are state-independent, so they resolve for themselves inline and
  never needed a generic gate.

### Scope

Java main source in scope (~290 lines,
`cli/src/main/java/io/bitken/ss/cli/task/`): `Task` (group parent),
`AddTask`, `AddComment`, `AddDeviation`, `UpdateStatus`, `SetCommit`.

Plus the slice of `Shipsmooth.execute()` (root `cli` package) needed to make
*any* state-dependent command dispatchable: repo-root detection is already
ported (`project::ProjectContext`); what's missing is the classify-then-gate
step.

Java tests in scope: `AddTaskIntegrationTest` (89 lines, both cases), plus
the four task-mutation cases from `PlanServiceTest`
(`updateTaskStatusMutatesXml`, `addCommentMutatesXml`,
`addTaskAppendsToXmlAndReturnsNewId`, `setTaskCommitMutatesXml`) — ported as
CLI-level integration tests, since this slice does not port `PlanService`
itself (see Design decisions).

### Out of scope

- `plan` command leaves (`Plan`, `Init`, `Show`, `Tag`, `Branch`,
  `Preflight`, `Resume`, `QuickStart`, `ProjectUpdate`) and the core
  `NewPlan` / `ScaffoldResult` / `ScaffoldException` classes they sit on —
  next slice, per 00-overview.md.
- A full `PlanService` port. Reading `PlanService.java` confirms every method
  the `task` leaves call is a one-line `load_plan → TaskStore mutation →
  save_plan` wrapper; `quickStart`/`NewPlan` is the only non-trivial part of
  that class and belongs with the `plan` slice. This slice ports the
  load-mutate-save pattern directly as a small `TaskStore` convenience
  instead of standing up the whole class early.
- Parity-harness coverage for `plan` subcommands (stay uncovered until their
  own slice).
- Any shipping path: no release, no installer, no SKILL.md `cliBin` change.
  The Java CLI stays the daily driver.

### Contracts that must stay byte-identical

1. **Resolution gate JSON + exit codes** for a `task` command run against an
   unsettled project — the same `needs-decision`/`unresolvable` shapes
   `store` already emits, the same 10/11 exit codes, now reachable from a
   second command family for the first time.
2. **Output strings**, verbatim to stdout: `"Added task {id}: {name}"`,
   `"Comment added to task {id}"`, `"Deviation added to task {id}"`,
   `"Task {id} status set to \"{status}\""`, `"Commit set for task {id}"`.
3. **`task status`'s exit-2 quirk.** An invalid `--status` prints
   `Error: invalid status "{status}". Allowed values: {csv}` to stderr and
   exits **2**, not 1 — distinct from every other error path in the CLI
   (which exits 1). Preserve as-is.
4. **XML mutation output** — unchanged from plan-107; this slice only has to
   call `TaskStore` correctly, not re-verify its rendering.

### Design decisions

- **No `PlanService` struct.** Add
  `TaskStore::mutate(&self, plan_id, f: impl FnOnce(&mut PlanTasks) -> Result<()>) -> Result<()>`
  (load → apply → save) to `ss-core::gw::task_store`, mirroring the private
  helper Java's `PlanService` used internally. CLI leaves call `TaskStore`
  directly; `task add` additionally takes `GitTags::get_plan_version` at
  dispatch (no `Provider<T>` indirection, matching the `store` pattern).
- **Resolve gate: static classification, not lazy throw.** Per 02-cli.md,
  after `clap` parses, classify the matched command (`store *`, `probe`,
  `--help`, `--version` = independent; everything under `task` = dependent)
  *before* constructing any service. If dependent and resolution isn't
  `Settled`, print the gate JSON and exit 10/11 without touching
  `TaskStore`. This inverts Java's exception-based
  `StateRootUnsettledException` handler into an explicit pre-dispatch check
  — the one deliberate architectural divergence this slice introduces.
- **`task status` keeps its own exit code.** Every other leaf lets a
  `TaskStore` error propagate to the CLI's generic exit-1
  `"shipsmooth: {message}"` convention (matching `store`'s pattern);
  `UpdateStatus` is the one leaf that validates and formats its own error
  before calling `TaskStore` at all, matching Java's early-validation
  branch — exit 2, not 1.
- **`SetCommit`'s `--branch` flag is parsed but unused**, confirmed by
  reading the Java source: `PlanService.setTaskCommit` accepts a `branch`
  argument and never passes it to `TaskStore.setCommit`. Port as-is — accept
  the flag, don't wire it anywhere.

### Verification

Two signals:

1. **Ported Java tests green** — `AddTaskIntegrationTest`'s two cases plus
   the four `PlanServiceTest` task-mutation cases, re-targeted at the CLI
   binary via `assert_cmd` in `tests/task.rs`, following `tests/store.rs`'s
   shape.
2. **Parity harness extension** — add `task` scenarios to
   `exp/rust/parity/run.sh`: seed a plan via the Java CLI, then run each of
   the 5 leaves through both binaries and diff stdout/exit-code/resulting
   XML.

Coverage target: 95% (standing project convention; reconfirm with the human
at Phase 2 kickoff per the skill).

## Tasks

### Task 1: Resolution-gate wiring in main.rs [High]

*Depends-on: none*

Classify commands state-dependent vs. independent, resolve once via
`ProjectDataStoreResolver`, emit gate JSON + exit 10/11 for `task` commands
on an unsettled project, and construct `ShipsmoothDataLocator`/`TaskStore`
from a `Settled` resolution to hand to dispatch. Must not regress the
existing `store`/`probe` dispatch (their tests must stay green).

High risk: architecturally new territory (the one deliberate divergence from
Java's exception-based gate) that every other task in this slice depends on;
a mistake here either breaks `store` or silently lets `task` commands run
against an unresolved store.

### Task 2: TaskStore::mutate convenience + `task add` leaf [Medium]

*Depends-on: 1*

Add the `load → f → save` helper to `TaskStore`; wire the `task` group
parent and `add` leaf (options `--plan`, `--name`, `--risk`,
`--depends-on`; stamps `created-from` via `GitTags::get_plan_version`). Port
`AddTaskIntegrationTest`'s two cases.

Medium risk: establishes the pattern (helper + leaf + test shape) every
other leaf in this slice copies; `--depends-on` and `created-from` stamping
are the parts most likely to be gotten subtly wrong.

### Task 3: `task status` leaf [Medium]

*Depends-on: 2*

Wire `UpdateStatus`: validate `--status` against `TaskStatus` before calling
`TaskStore::mutate`, formatting Java's exact invalid-status message and
returning exit 2 on failure (see Design decisions). Port
`updateTaskStatusMutatesXml`.

Medium risk: the one leaf with its own error-handling and exit-code path,
diverging from the rest of the group — easy to accidentally fold into the
generic exit-1 convention.

### Task 4: `task comment` leaf [Low]

*Depends-on: 2*

Wire `AddComment` over `TaskStore::mutate`. Port `addCommentMutatesXml`.

Low risk: mechanical, follows the pattern `task add` established exactly.

### Task 5: `task deviation` leaf [Low]

*Depends-on: 2*

Wire `AddDeviation` over `TaskStore::mutate`. No dedicated Java unit test
beyond what `TaskStoreTest` already covers at the `TaskStore` level — assert
via a small CLI-level smoke test instead.

Low risk: mechanical; one extra required option (`--type`) versus comment.

### Task 6: `task set-commit` leaf [Low]

*Depends-on: 2*

Wire `SetCommit` (options `--plan`, `--task`, `--commit`, optional
`--branch`) over `TaskStore::mutate`. Port `setTaskCommitMutatesXml`.

Low risk: mechanical; the unused `--branch` flag (see Design decisions) is
the only wrinkle.

### Task 7: Parity harness: task scenarios [Medium]

*Depends-on: 3, 4, 5, 6*

Extend `exp/rust/parity/run.sh` with `task` scenarios (seed a plan, run
add/comment/deviation/status/set-commit through both binaries, diff
stdout/exit-code/XML).

Medium risk: first parity coverage for a state-dependent command family
(`store`'s scenarios never exercise the resolve gate's dependent-command
side); a genuinely different failure surface than the ported unit/
integration tests.

### Task 8: Migration notes write-back [Low]

*Depends-on: 7*

Update `docs/rust-migration/00-overview.md` and `02-cli.md` with actual cost,
divergences found (especially the resolve-gate architecture and the `task
status` exit-2 quirk), decisions that outlived the plan, and the recommended
next slice (`plan` command leaves + `NewPlan`/`PlanService`/`ScaffoldResult`/
`ScaffoldException`).

Low risk: documentation only, depends on everything above being done.
