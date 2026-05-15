# Plan 39 — Migrate remaining commands to Dagger DI

**Status:** in-progress
**Branch:** `t/plan-39-di-remaining-commands`
**Tracking mode:** Local (`.agents/plans/plan-39-tasks.xml`).

---

## 1. Context

Plan 38 established the Dagger 2 DI foundation and migrated `AddCommentCommand`
to constructor injection. The remaining 17 commands still instantiate services
inline inside `call()`.

A complete set of updated command files was prepared externally (stored in
`.agents/tmp/updated-java-2026-05-15/`) with `@Inject` constructors on all
commands. This plan integrates those files into the codebase and wires them into
`AppComponent` / `TasksCli`.

---

## 2. What the updated files change

Each command (except `AddCommentCommand`, already done) gains:

- `@Inject` constructor that takes services as parameters
- Service fields stored as `final` instance fields
- `call()` uses fields instead of `new Service()` calls

Commands affected:
`AddDeviationCommand`, `ClaimCommand`, `InitCommand`, `IntegrateCommand`,
`LedgerCommand` (+ inner `ListCmd`, `VerifyCmd`, `ReadCmd`),
`LedgerRecordCommitCommand`, `LedgerRecordPatchIntegratedCommand`,
`LedgerResolverCompleteCommand`, `LedgerWatchCommand`, `ProjectUpdateCommand`,
`SetCommitCommand`, `ShowCommand`, `UpdateStatusCommand`, `WorkerBaseCommand`,
`WorkerCleanupCommand`, `WorkerFinishCommand`, `WorkerInitCommand`.

---

## 3. Tasks

### Task 1: Copy updated command files and verify compile [Medium]

Replace command files in `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/commands/`
with the updated versions from `.agents/tmp/updated-java-2026-05-15/io/bitken/shipsmooth/tasks/commands/`
(excluding `AddCommentCommand.java` which is already migrated).
Run `mvn compile` — must pass clean.

### Task 2: Expand AppComponent with provision methods for all commands [Low]

*Depends-on: 1*

Add one provision method per migrated command to `AppComponent`. Add any
additional `@Provides` methods to `ServicesModule` for new service types needed
(e.g. `WorkflowService`, `WorktreeService`).
Run `mvn compile` — Dagger codegen must produce `DaggerAppComponent` without errors.

### Task 3: Update TasksCli to obtain all commands via AppComponent [Medium]

*Depends-on: 2*

Replace each `new XxxCommand()` in `TasksCli` with `app.xxxCommand()`.
Run full test suite — all existing tests must pass without assertion changes.

### Task 4: Verify end-to-end and clean up [Low]

*Depends-on: 3*

- Run `mvn test` — all green.
- Spot-check a few commands manually via the CLI jar.
- Remove the temp folder `.agents/tmp/updated-java-2026-05-15/` once integration confirmed.
