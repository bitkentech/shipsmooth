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

## Test-impact baseline (verification harness)

Captured **before execution** (plan-82-v5) from the existing suite. This is the
acceptance oracle for the deletion: after the plan runs, the surviving suite
must be green, every "will-fail" test must be either deleted (bound 1:1 to a
removed class) or edited to the ledger-free behaviour, and no "should-not-fail"
test may regress. Re-run `./gradlew build` and diff the result against this
table at Task 6.

Three buckets, by *why* a test reacts to the deletion:

### A. Should NOT fail — must stay green untouched

No real dependency on the subsystem; if one of these goes red, the cut bled
into shared code and the change is wrong.

- **core:** `gw/GitStateTest` (`worktreeList()` here is git's own worktree
  listing on `GitState`, not the deleted `WorktreeService`),
  `gw/GitTagsIntegrationTest`, `gw/TaskStoreTest`,
  `ShipsmoothDataLocatorIntegrationTest`, `svc/plan/NewPlanTest`,
  `svc/plan/PlanNumbersTest`, `svc/plan/SlugsTest`
- **cli:** `AddTaskIntegrationTest`, `PlanBranchTest`, `PlanPreflightTest`,
  `PlanTagTest`, `PlanCommandsIntegrationTest`, `RepoRootTest`
- **harness:** `TargetTest`, `HookCommandRendererTest`
- **plugin-model:** `EnvTest`, `OsTest`, `PlatformTest`, `PluginModelTest`
- **packaging:** `PackageRuntimeTest`, `PluginModelReachabilityTest`,
  `PublishReleaseTest`, `ValidateReleaseTest`, `ReleaseGuardTest` — the
  "workflow" / "ledger" strings in these are the plugin **description** text and
  a synthetic leak-detection fixture (`ReleaseGuardTest.launcherGuardFailsWhen...`
  feeds the guard a literal `"ledger   Inspect..."` help line); neither depends
  on any removed class, so both must keep passing.

### B. WILL fail — bound 1:1 to deleted classes → delete the test

These exist only to exercise removed code; they cannot survive the deletion and
are removed with their subject (Tasks 3–4).

- **core (Task 4):** `ledger/EventLedgerTest`, `LedgerIntegrationTest`,
  `git/WorktreeServiceTest`, `workflow/integration/PromptBuilderTest`,
  `workflow/TransactionTest`, `workflow/WorkflowServiceFinalizeWorkerTest`,
  `workflow/WorkflowServiceInitializeWorkerTest`,
  `workflow/WorkflowServiceRunIntegrationTest`, `workflow/WorkflowSkeletonTest`,
  and `conf/AppComponentTest` (asserts the removed `eventLedger()` /
  `worktreeService()` / `workflowService()` accessors — remove those assertions;
  if nothing else remains, delete the test)
- **cli (Task 3):** `InitLedgerTest`, `IntegrateTest`,
  `LedgerRecordPatchIntegratedTest`, `LedgerTest`, `LedgerWatchTest`,
  `WorkerDependencyIntegrationTest`, `WorkerLifecycleIntegrationTest`,
  `WorkerWorktreeLifecycleIntegrationTest`

### C. WILL fail — surviving tests that touch a removed symbol → edit them

Kept tests on the critical path that currently construct/reference a removed
symbol. They must be **rewritten to the ledger-free behaviour**, not deleted.
(Compilation fails for the whole module until each is fixed, so these gate the
build directly.)

- **core (Task 1):** `svc/plan/PlanServiceTest` — drops the `EventLedger`
  imports and ctor arg; the four ledger-recording assertions
  (`updateTaskStatus...RecordsLedgerEvent`, `addComment...RecordsLedgerEvent`,
  `...WithoutLedgerEvent`, `mutationRecordsNoLedgerEvent...`,
  `ledgerFailureDoesNotRollBackXmlMutation`) become "mutation persists to XML"
  with no ledger side-channel. This is the Invariant-#6 "update the existing
  test first" step.
- **cli (Tasks 1–3):** `CommandsTest` and `PlanQuickStartTest` (construct a
  `PlanService` with an `EventLedger` arg — drop the arg to match Task 1's new
  ctor); `ShipsmoothIntegrationTest` (imports `Event`/`EventType`/`EventLedger`,
  asserts CLI mutations record ledger entries, plus `ledger`/`integrate` gating
  — strip ledger assertions, drop the removed-command gating cases);
  `ShipsmoothTest` (`integrate`/`worker`/`claim` gating cases — remove the cases
  for deleted commands); `GroupedCommandTreeTest` (`worker`/`ledger` group
  dispatch cases — remove); `PlanResumeTest` (`printsSummaryAndWorktreeInfo`,
  `filtersWorktreesToPlanNumber`, the `stubWorktrees` helper — `printWorktrees`
  is removed from `Resume`, so drop these worktree cases and keep the plain
  summary case); `ProdSurfaceIntegrationTest` (its experimental-name set lists
  `claim`/`worker`/`integrate`/`ledger` — drop the now-deleted names; the prod
  surface must simply not list them at all).

### D. MIGHT fail — golden/surface tests gated on template content (Task 5)

These assert the *generated SKILL.md surface*. Outcome depends on getting the
template edits right; they are the signal that Task 5 landed correctly.

- **harness `TargetIntegrationTest`:** the three
  `prod*BaseSkillHasNoLedgerReference` cases should stay green (prod already
  hides ledger). The **`devBaseSkillKeepsLedgerReference`** case *will fail* once
  Task 5 strips ledger from the dev templates — it currently asserts the dev
  skill still contains `"ledger"`. Update or delete it: post-removal there is no
  ledger paragraph in either build. Also re-check `mustNotContainWorkerBlock`
  / parallel-skill assertions after the partials are deleted.
- **harness `PosixBootstrapIntegrationTest`:** golden bootstrap surface; verify
  it still matches after template edits (the JTE golden baseline at
  `.agents/tmp/baseline` may need refreshing alongside it).

> **One-line oracle for Task 6:** `git build` green ⇒ bucket A all pass,
> bucket B files no longer exist, bucket C compiles+passes ledger-free, bucket D
> reflects the new (ledger-absent) surface in both prod *and* dev.

## Tasks

_Risk levels are first-draft estimates for human calibration (Phase 1, step 3)._

### Task 1: Strip ledger from PlanService and prove plan/task flows still pass [High]

Remove the `EventLedger` dependency from `PlanService` — field, ctor arg,
`ensureLedgerFile()` calls, `recordBestEffort` / `LedgerAction`, integration-mode
payloads — and update `PlanServiceTest` to assert the ledger-free behaviour.
This is the riskiest cut because `PlanService` is a kept class on the critical
plan/task path; getting its seam right de-risks every later deletion. End state:
`PlanService` compiles and its unit tests pass with no ledger references.

_Verify (bucket C): `PlanServiceTest` rewritten to ledger-free behaviour;
`CommandsTest`, `PlanQuickStartTest` ctor args dropped. See Test-impact baseline._

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

_Verify: delete bucket-B cli tests (`InitLedgerTest`, `IntegrateTest`,
`Ledger*Test`, `Worker*IntegrationTest`); edit bucket-C cli tests
(`ShipsmoothTest`, `ShipsmoothIntegrationTest`, `GroupedCommandTreeTest`,
`PlanResumeTest`, `ProdSurfaceIntegrationTest`). See Test-impact baseline._

### Task 4: Delete the core subsystem packages (ledger, workflow engine, git worktree) [Medium]

*Depends-on: 2*

Delete `core/.../ledger/`, `core/.../workflow/integration/`, the
`WorkflowService*` / `IntegrationOptions` / `IntegrationResult` workflow classes,
and `core/.../git/WorktreeService.java` + `MergeResult.java`. Verify each
shared-looking helper (`Transaction`, `ProcessRunner`, progress reporters) for
remaining users before deleting; keep any still referenced. Delete the matching
core tests. End state: `core` compiles and tests pass.

_Verify: delete bucket-B core tests (`EventLedgerTest`, `LedgerIntegrationTest`,
`WorktreeServiceTest`, `PromptBuilderTest`, `TransactionTest`,
`WorkflowService*Test`, `WorkflowSkeletonTest`); strip removed-accessor
assertions from `AppComponentTest`. See Test-impact baseline._

### Task 5: Remove ledger/parallel-execution skill templates and refresh generated surface [Medium]

*Depends-on: 3,4*

Delete the workflow skill partials (ledger-watch-cmd, resolver-complete-cmd,
agent-resolver-call, agent-dispatch-*, background-execution,
task-command-sequence-*, parallel-execution) across claude/codex/gemini, edit
their includers to drop the includes, and refresh the JTE golden baselines and
surface integration tests so the generated SKILL.md no longer names removed
commands. End state: skills build green, golden baselines updated, surface tests
pass.

_Verify (bucket D): `TargetIntegrationTest.devBaseSkillKeepsLedgerReference`
will fail — update/delete it (no ledger paragraph in either build now); keep the
`prod*HasNoLedgerReference` cases green; re-check worker-block / parallel-skill
assertions; refresh `PosixBootstrapIntegrationTest` golden. See Test-impact
baseline._

### Task 6: Full build + on-disk cleanup + dead-reference sweep [Low]

*Depends-on: 5*

Run a full `./gradlew build`, delete the on-disk `*/.agents/ledger.jsonl` files,
de-special-case them in `.gitignore`, and grep the whole repo (source + docs)
for surviving `ledger` / `Integrate` / `Worktree` / `worker` references to
confirm nothing dangles. End state: clean full build, no stray references
outside this plan file and historical plan docs.

_Verify: run the Test-impact baseline oracle — every bucket-A test green,
bucket-B test files gone, bucket-C/D tests green ledger-free. This is the final
gate for the whole plan._
