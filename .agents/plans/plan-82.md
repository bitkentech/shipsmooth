# plan-82 — Remove ledger / integrate / worktree subsystem

## Context

**Feature (user's words):** "Remove all the 'ledger' related code because it's
not needed for now. Identify places where 'ledger' is being used. Remove the
'Integrate' and 'Worktree' as well. If required, these will be re-written in
future."

**Backlog issue:** _Local mode — no external tracker. This is a deliberate
deletion of the parallel-execution subsystem to reduce surface area; it may be
re-introduced later in a different form._

### Scope decisions (confirmed with human)

1. **Remove the whole subsystem**, not just ledger. The `ledger`, `integrate`,
   and `worktree` pieces are one interlocking unit — the parallel-execution
   engine — together with the `worker` CLI command group and the
   `WorkflowService` `initializeWorker` / `finalizeWorker` / `runIntegration`
   methods. All of it goes.
2. **Remove the skill templates too.** The workflow templates that describe
   ledger / integrate / worktree / parallel-execution to the agent are deleted
   along with their references, so the generated SKILL.md stops naming removed
   commands.
3. **Plan number stays 82** (the parked separate-repo plan-82 lives on another
   branch, not on `main`).

### What is being removed vs. edited

This is the core map that drives the task breakdown. Two categories:

**Delete outright (subsystem-only):**

- `core/src/main/java/io/bitken/ss/ledger/` — `EventLedger`, `Event`,
  `EventType`, `ObjectStore`
- `core/src/main/java/io/bitken/ss/workflow/integration/` — entire package
  (`IntegrationLedger`, `LedgerSubagentRunner`, `Resolver`, `SubagentResolver`,
  `SubagentRunner`, `ResolverContext`, `PromptBuilder`, `IntegrationOrder`,
  `IntegrationDefaults`, `TaskOrderInput`)
- `core/src/main/java/io/bitken/ss/workflow/` — `WorkflowService`,
  `WorkflowServiceImpl`, `WorkflowException`, `WorkflowErrorCode`,
  `IntegrationOptions`, `IntegrationResult`, plus the process/progress helpers
  if they are used **only** by the workflow engine (`Transaction`,
  `DefaultProcessRunner`, `ProcessRunner`, `ConsoleProgressReporter`,
  `ProgressReporter`) — verify usage before deleting each
- `core/src/main/java/io/bitken/ss/git/` — `WorktreeService`, `MergeResult`
- `cli/src/main/java/io/bitken/ss/cli/ledger/` — entire `Ledger` command group
- `cli/src/main/java/io/bitken/ss/cli/worker/` — entire `Worker` command group
  (`Worker`, `WorkerBase`, `WorkerInit`, `WorkerFinish`, `WorkerCleanup`,
  `Claim`)
- `cli/src/main/java/io/bitken/ss/cli/Integrate.java`
- All tests bound 1:1 to the above (`EventLedgerTest`, `LedgerIntegrationTest`,
  `WorkflowService*Test`, `WorktreeServiceTest`, `IntegrateTest`, `LedgerTest`,
  `LedgerWatchTest`, `LedgerRecordPatchIntegratedTest`, `InitLedgerTest`,
  `Worker*IntegrationTest`, `PromptBuilderTest`, etc.)

**Edit surgically (shared — must survive):**

- `core/.../conf/ServicesModule.java` — drop `@Provides` for `EventLedger`,
  `WorktreeService`, `WorkflowService`, `WorkflowServiceImpl`; drop the
  `EventLedger` ctor arg from `providePlanService`
- `core/.../conf/AppComponents.java` — drop `eventLedger()`,
  `worktreeService()`, `workflowService()`, `workflowServiceImpl()` accessors
- `core/.../svc/plan/PlanService.java` — remove the `EventLedger` field, ctor
  arg, `ensureLedgerFile()` calls, `recordBestEffort` / `LedgerAction`, and the
  integration-mode event payloads
- `core/src/main/java/module-info.java` — drop `exports`/`opens` for
  `io.bitken.ss.ledger`, `io.bitken.ss.workflow.integration`, and `git` /
  `workflow` if those packages become empty
- `cli/src/main/java/module-info.java` — drop `opens ...cli.ledger` and
  `opens ...cli.worker`
- `cli/.../cli/CommandTree.java` — remove `Integrate`, `Worker`, `Claim`,
  `Ledger` construction and the `app.*` accessors they pass
- `cli/.../cli/plan/Resume.java` — remove `printWorktrees` and its call site
- `cli/.../cli/Shipsmooth.java` — drop any integrate/worker registration
- `.agents/ledger.jsonl` (and `cli/`, `core/` copies) — delete the on-disk
  ledger files; ensure `.gitignore` no longer special-cases them

**Skill templates to delete (and de-reference):**

- `skills/shared/workflow/{claude,codex,gemini}/ledger-watch-cmd.jte.md`
- `.../resolver-complete-cmd.jte.md`, `.../agent-resolver-call.jte.md`
- `.../agent-dispatch-dependent.jte.md`, `.../agent-dispatch-independent.jte.md`
- `.../background-execution.jte.md`,
  `.../task-command-sequence-{dependent,independent}.jte.md`
- `skills/shared/parallel-execution.jte.md`
- Edit the includers (`phase2-execute.jte.md`, `core-invariants.jte.md`,
  `agent-instruction.jte.md`, `permission-consent.jte.md`, and the experimental
  `start-tla` / `refine` references) to drop includes of the deleted partials
- Refresh JTE golden baselines and the generated SKILL.md surface tests
  (`ProdSurfaceIntegrationTest`, `TargetIntegrationTest`,
  `PosixBootstrapIntegrationTest`) so they no longer assert removed commands

### Invariant note (TDD)

This is a **deletion** plan. Core Invariant #6 (tests precede implementation)
applies only loosely: for deletions we instead lean on the existing suite +
compiler as the safety net — the "test" of a successful removal is that the
build is green and no surviving code references a deleted symbol. Each task's
acceptance is "`./gradlew build` green, no dangling references." Where a
surviving class changes behaviour (PlanService losing ledger recording), update
its existing unit test to assert the new, ledger-free behaviour first.

## Tasks

_Risk levels are first-draft estimates for human calibration (Phase 1, step 3)._

### Task 1: Strip ledger from PlanService and prove plan/task flows still pass [High]

Remove the `EventLedger` dependency from `PlanService` — field, ctor arg,
`ensureLedgerFile()` calls, `recordBestEffort` / `LedgerAction`, integration-mode
payloads — and update `PlanServiceTest` to assert the ledger-free behaviour.
This is the riskiest cut because `PlanService` is a kept class on the critical
plan/task path; getting its seam right de-risks every later deletion. End state:
`PlanService` compiles and its unit tests pass with no ledger references.

### Task 2: Remove DI wiring and module exports for the subsystem [High]

*Depends-on: 1*

Edit `ServicesModule`, `AppComponents`, and both `module-info.java` files to
drop every provider, accessor, export, and opens-clause for `EventLedger`,
`WorktreeService`, `WorkflowService(Impl)`, the `ledger` / `workflow.integration`
packages, and the cli `ledger` / `worker` packages. After this the DI graph no
longer references the subsystem; remaining compile errors localise precisely to
the classes scheduled for deletion in Tasks 3–4.

### Task 3: Delete the CLI subsystem (worker, ledger, Integrate) and rewire CommandTree [Medium]

*Depends-on: 2*

Delete the `cli/.../worker/` and `cli/.../ledger/` packages and
`Integrate.java`; remove their construction from `CommandTree`, the
integrate/worker registration from `Shipsmooth.java`, and `printWorktrees` from
`plan/Resume.java`. Delete the matching CLI tests. End state: `cli` compiles and
its surviving tests pass; `shipsmooth --help` lists no removed commands.

### Task 4: Delete the core subsystem packages (ledger, workflow engine, git worktree) [Medium]

*Depends-on: 2*

Delete `core/.../ledger/`, `core/.../workflow/integration/`, the
`WorkflowService*` / `IntegrationOptions` / `IntegrationResult` workflow classes,
and `core/.../git/WorktreeService.java` + `MergeResult.java`. Verify each
shared-looking helper (`Transaction`, `ProcessRunner`, progress reporters) for
remaining users before deleting; keep any still referenced. Delete the matching
core tests. End state: `core` compiles and tests pass.

### Task 5: Remove ledger/parallel-execution skill templates and refresh generated surface [Medium]

*Depends-on: 3,4*

Delete the workflow skill partials (ledger-watch-cmd, resolver-complete-cmd,
agent-resolver-call, agent-dispatch-*, background-execution,
task-command-sequence-*, parallel-execution) across claude/codex/gemini, edit
their includers to drop the includes, and refresh the JTE golden baselines and
surface integration tests so the generated SKILL.md no longer names removed
commands. End state: skills build green, golden baselines updated, surface tests
pass.

### Task 6: Full build + on-disk cleanup + dead-reference sweep [Low]

*Depends-on: 5*

Run a full `./gradlew build`, delete the on-disk `*/.agents/ledger.jsonl` files,
de-special-case them in `.gitignore`, and grep the whole repo (source + docs)
for surviving `ledger` / `Integrate` / `Worktree` / `worker` references to
confirm nothing dangles. End state: clean full build, no stray references
outside this plan file and historical plan docs.
