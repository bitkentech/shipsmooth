# Plan 37 — Service Layer: WorkflowService

**Status:** Draft
**Date:** 2026-05-12
**Proposal:** `docs/proposals/service-layer.md`
**Backlog reference:** Local mode — recorded here in lieu of a Linear backlog issue. The architectural motivation is the "Service Layer" proposal committed in `docs/proposals/service-layer.md` (commit `1d4714e`).

---

## 1. Context

Today the PicoCLI commands in `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/commands/` directly orchestrate the domain services (`WorktreeService`, `LedgerService`, `XmlService`). Concretely:

- `WorkerInitCommand.call()` resolves branch names, calls `WorktreeService.addWorktree`, computes the base SHA, and writes a `WORKTREE_CREATED` event.
- `WorkerFinishCommand.call()` enforces the "no commits in worktree" invariant, captures the diff, commits, writes two ledger events, and re-materializes the XML.
- `IntegrateCommand` walks the resume-state decision tree inline.

Per `docs/proposals/service-layer.md`, this couples orchestration to one delivery mechanism (PicoCLI). The goal of plan-37 is to extract a single `WorkflowService` that owns orchestration, leaving the commands as thin argv → service-call shells. This is a **structural refactor — no new behavior**.

Goal-oriented-impl (a future plan) plugs into this layer; it is out of scope here.

---

## 2. Scope

**In scope:**

- New `io.bitken.shipsmooth.tasks.workflow` package containing `WorkflowService` interface, `WorkflowServiceImpl`, and `WorkflowException`.
- Migration of three commands (`worker-init`, `worker-finish`, `integrate`) to call the service.
- A narrow `Transaction` helper used only by `finalizeWorker` to batch git-commit + two ledger writes + XML re-materialization with best-effort rollback.
- Tests at the service layer mirroring existing command tests; existing command tests retained as integration coverage of the thin shell.

**Out of scope (deferred):**

- Migration of remaining commands (`claim`, `update-status`, `add-comment`, `set-commit`, `worker-cleanup`, `worker-base`, ledger-* commands). They keep their current shape.
- Invariant guards (e.g. "tests precede implementation" pre-condition). Service layer must be behaviorally equivalent first; new invariants land in a follow-up plan.
- The `reconcile` primitive and the SKILL resume-tree collapse. That is goal-oriented-impl's job.
- Any new public CLI surface, flag, or output format change.

---

## 3. Design Decisions

### 3.1 Package layout

```
plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/workflow/
  WorkflowService.java         # interface
  WorkflowServiceImpl.java     # implementation, wires WorktreeService + LedgerService + XmlService
  WorkflowException.java       # checked exception with typed error code + exit code
  Transaction.java             # unit-of-work helper (finalizeWorker only, for now)
  IntegrationOptions.java      # parameter object for runIntegration
  IntegrationResult.java       # return type for runIntegration
```

### 3.2 Service API (initial)

Three methods only — one per migrated command. Other commands stay on the current code path.

```java
public interface WorkflowService {
    void initializeWorker(int planId, String taskId, String baseSha) throws WorkflowException;
    void finalizeWorker(int planId, String taskId) throws WorkflowException;
    IntegrationResult runIntegration(int planId, IntegrationOptions options) throws WorkflowException;
}
```

`planId` is `int` (matches existing CLI option types — the proposal's `UUID` is aspirational). Keeping types aligned with current code reduces refactor surface.

### 3.3 Failure model

`WorkflowException` carries a typed `WorkflowErrorCode` enum and an `exitCode()` accessor. Each command's `call()` maps the exception to `System.err.println(e.getMessage()); return e.exitCode();`. No change to today's CLI exit codes — they become properties of the service rather than of the command.

### 3.4 Transactional scope (narrow)

`Transaction` is **not** a general framework. It is a class with:
- `register(Runnable rollback)` — called after each step that succeeds, registering its inverse.
- `commit()` — clears the rollback list (success path).
- `rollback()` — invoked on failure; runs registered inverses in reverse order, best-effort, logs each failure.

Used by `finalizeWorker` to roll back the git commit (via `git update-ref` to the prior tip) if a subsequent ledger write fails. Not used by `initializeWorker` in this plan — its failure modes are already handled by the existing pre-check (worktree-exists guard). Adding rollback there would expand scope; defer until measured need.

This deliberately keeps the helper one file, one use site. If a second site needs it later, generalize then.

### 3.5 Wiring

`TasksCli` constructs a single `WorkflowServiceImpl` and passes it to each migrated command via a constructor parameter. PicoCLI supports field injection through `@Command` factories; we follow the existing pattern in `TasksCli` (whatever it currently is) rather than introducing a DI container.

### 3.6 What does *not* change

- The wire format of `.agents/ledger.jsonl` and `.agents/plans/plan-{N}-tasks.xml`.
- The set of events written by each operation (same `WORKTREE_CREATED`, `PATCH_EMITTED`, `COMMIT_RECORDED`).
- Exit codes, stdout/stderr formats, CLI flags.
- The "no git in subagent worktree" invariant — still enforced inside `finalizeWorker`, same check.
- `WorktreeService`, `LedgerService`, `XmlService` public APIs.

---

## 4. Tasks

### Task 1: Workflow package skeleton [Low]

Create the `workflow` package with empty `WorkflowService` interface, `WorkflowServiceImpl` (no methods yet), `WorkflowException` (with `WorkflowErrorCode` enum, message, and `exitCode()`), and a passing smoke test that constructs an impl and asserts the exception class shape. No command changes.

**Why:** Establishes the package, classes, test fixture, and Maven module wiring before any orchestration logic moves. Low risk — pure scaffolding.

### Task 2: Migrate worker-init [Medium]

Add `WorkflowService.initializeWorker`. Move the branch-name calculation, base-SHA resolution, worktree-exists guard, `WorktreeService.addWorktree` call, and `WORKTREE_CREATED` event write from `WorkerInitCommand` into the service. The command shrinks to argv parsing + one service call + exception → exit code mapping.

Service-layer tests cover: existing-worktree failure, missing base SHA falls back to HEAD, ledger event written with correct fields. Existing `WorkerInitCommand` tests stay green unchanged.

**Why:** Smallest blast radius of the three migrations (the proposal explicitly calls this out as the starting point). Medium because it is the first migration and proves the wiring pattern that tasks 3 and 4 follow.

*Depends-on: 1*

### Task 3: Migrate worker-finish + Transaction helper [High]

Add `WorkflowService.finalizeWorker` and the `Transaction` helper. Move the no-commits-in-worktree invariant check, diff capture, commit, two ledger writes, and XML re-materialization into the service. Wrap the commit-and-write sequence in `Transaction` so a failed ledger write rolls the commit back via `git update-ref`.

Service-layer tests cover: rogue-commit detection, empty-diff abort, happy path produces both `PATCH_EMITTED` and `COMMIT_RECORDED`, transaction rollback restores branch tip when the second ledger write is forced to throw. Existing `WorkerFinishCommand` tests stay green.

**Why:** Highest risk task in the plan. Touches three subsystems in one transactional unit; the `Transaction` rollback path is genuinely new behavior even though the happy path is identical. Need to confirm the `git update-ref` rollback is correct on a worktree's branch ref before locking the pattern in.

*Depends-on: 2*

### Task 4: Migrate integrate to runIntegration [High]

Add `WorkflowService.runIntegration(planId, IntegrationOptions)` and `IntegrationResult`. Move the resume-state inference and dispatch from `IntegrateCommand` into the service. The command becomes argv parsing + options construction + one service call.

`IntegrationOptions` carries: `taskBranch`, `verifyCmd`, `force`, and any other flags currently on `IntegrateCommand`. `IntegrationResult` carries: integration-tip SHA, fast-forward command string, and final ledger event written. The CLI prints the same lines it prints today — formatting stays in the command shell.

Service-layer tests cover the existing resume-tree branches (fresh start, resume with `PATCH_INTEGRATED`, resume after `RESOLVER_REQUESTED`). Existing `IntegrateCommand` integration tests stay green.

**Why:** Largest command by line count and the one with the most state-machine logic. High risk because the resume tree is the load-bearing piece — getting it wrong silently breaks recovery. Same behavioral surface, so equivalence is testable, but the surface is big.

*Depends-on: 3*

### Task 5: Delete duplicated logic from migrated commands [Low]

Once tasks 2–4 are green, remove any lingering helper methods, imports, or unused fields from the three migrated commands. Confirm by grep that no command in the migrated set references `WorktreeService`, `LedgerService`, or `XmlService` directly. Add a comment in `TasksCli` noting which commands route through `WorkflowService` and which still use the old pattern.

**Why:** Cleanup pass. Per the proposal's §7 step 4, deletion follows after the service path is proven. Low because by this point nothing depends on the duplicated code; this is mechanical.

*Depends-on: 4*

---

## 5. Test Strategy

- **Service-layer unit tests:** new test class per service method, mocking domain services where they would touch disk, exercising both happy path and each failure mode.
- **Existing per-command tests:** retained unchanged. They now cover the thin-shell mapping (argv → service call → exit code) rather than the orchestration logic. If any existing test fails after migration, the refactor is not behaviorally equivalent — fix before proceeding.
- **Coverage threshold:** confirm with human at Phase-2 kickoff. Default per SKILL is 95%.
- **Integration preamble (per SKILL Phase 2):** one or two end-to-end tests that run `worker-init` → fake subagent edit → `worker-finish` → `integrate` against a temp repo, asserting the ledger contains the expected event sequence. Committed red before any task implementation.

---

## 6. Risks

**`Transaction` rollback is harder than it looks on a worktree branch.** `git update-ref` against a branch that has an active worktree may behave differently than against a quiescent branch. Mitigation: task 3 includes an explicit test for the rollback path; if `update-ref` is not sufficient, fall back to `git branch -f <branch> <old-sha>` and document the choice.

**`IntegrateCommand` is bigger than the proposal suggests.** It has retry loops, ledger-watch interaction, and resolver dispatch. Task 4 moves orchestration but leaves the IPC patterns (ledger-watch, ledger-resolver-complete) where they are — those are CLI concerns, not service concerns. If during implementation the line between "orchestration" and "IPC plumbing" turns out to be fuzzier than expected, surface as a major deviation rather than expanding scope.

**Behavioral drift during migration.** A subtle change in event ordering or XML write timing could break downstream tooling that scrapes the ledger. Mitigation: every migrated command keeps its existing tests green; any test change is itself a behavior change and must be reviewed.

---

## 7. Open Questions

1. **`TasksCli` factory style.** What does today's command construction look like — default no-arg constructors, or a factory? The service injection approach in §3.5 depends on this. Will confirm in task 1.
2. **`integrate` IPC scope.** Confirm at task 4 kickoff: do we want `ledger-watch` and `ledger-resolver-complete` invocations to stay in the command, or also move to the service? Initial assumption: stay in command.

---

## 8. Closeout

On all five tasks `agent-coded` and integration green:

- Tag `plan-37-complete`, push.
- Note in this file's "Status" line: shipped.
- The "service layer" foundation is now in place for goal-oriented-impl (future plan-38).
