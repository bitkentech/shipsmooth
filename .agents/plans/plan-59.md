# Plan 59: Introduce PlanService + Rename LedgerService → EventLedger

## Context

Discussion in session 2026-05-27 using PoEAA lens identified two issues with the
current architecture in the `app` module:

1. **`LedgerService` is misnamed.** It is a Gateway (Fowler base pattern) — it
   encapsulates the append-only `.agents/ledger.jsonl` file and the SHA-addressed
   object store. The `*Service` suffix implies Service Layer membership, which it
   is not. Rename to `EventLedger`.

2. **No Service Layer exists for plan mutations.** Every CLI command that mutates
   plan state (AddComment, AddDeviation, UpdateStatus, SetCommit, ProjectUpdate,
   Claim, Init) duplicates the same two-step pattern: mutate XML via `XmlService`,
   write XML, then record a ledger event via `LedgerService` with a try/catch warn.
   This logic belongs in a `PlanService` that owns both steps atomically from the
   caller's perspective.

Backlog reference: no dedicated backlog issue — this is a structural refactor
improving Clean Architecture layer separation.

## Design

### Task 1: Rename LedgerService → EventLedger [Low]

Rename the class, its file, all import sites, variable names, and the Dagger
provider method. The `ledger` local variable name used in most CLI commands reads
cleanly against `EventLedger` and should be kept. The `ledgerService` field name
in AddComment/AddDeviation/UpdateStatus/SetCommit/ProjectUpdate/WorkerBase can be
shortened to `ledger` for consistency during this pass.

Files to touch:
- `ledger/LedgerService.java` → `ledger/EventLedger.java` (rename + package decl)
- All CLI command files importing `LedgerService`
- `conf/ServicesModule.java` — provider method rename
- `conf/AppComponents.java` — accessor rename
- `workflow/WorkflowServiceImpl.java`
- `workflow/integration/IntegrationLedger.java`
- `workflow/integration/LedgerSubagentRunner.java`

### Task 2: Introduce PlanService [Medium]

Add `service/PlanService.java` that encapsulates the XML-mutate + ledger-record
pattern. `PlanService` takes `XmlService` and `EventLedger` as constructor
dependencies.

Public API:
```java
void initPlan(int planId, String planVersion, List<XmlService.Task> tasks) throws Exception;
void updateTaskStatus(int planId, int taskId, String status) throws Exception;
void setTaskCommit(int planId, int taskId, String commit, String branch) throws Exception;
void addComment(int planId, int taskId, String message) throws Exception;
void addDeviation(int planId, int taskId, String type, String message) throws Exception;
void projectUpdate(int planId, String status, Boolean blocked, String message) throws Exception;
String findCommitSha(String taskId) throws IOException;
PlanTasks loadPlan(int planId) throws JAXBException;
String recordEvent(Event event) throws IOException;
```

The try/catch warn pattern (XML mutation preserved, ledger record best-effort)
moves into `PlanService` once and is removed from all CLI commands.

Wire into Dagger in `ServicesModule` / `AppComponents`.

### Task 3: Migrate CLI commands to PlanService [Medium]

*Depends-on: 2*

Migrate these CLI commands to inject `PlanService` instead of `XmlService` +
`EventLedger` separately:

- `AddComment` — `addComment(plan, task, message)`
- `AddDeviation` — `addDeviation(plan, task, type, message)`
- `UpdateStatus` — `updateTaskStatus(plan, task, status)`
- `SetCommit` — `setTaskCommit(plan, task, commit, branch)`
- `ProjectUpdate` — `projectUpdate(plan, status, blocked, message)`
- `Init` — `initPlan(plan, planVersion, tasks)` (keep `GitTagService` injection)
- `WorkerBase` — `loadPlan` + `findCommitSha`

Commands that only write ledger events with no XML mutation (`Claim`,
`LedgerRecordCommit`, `LedgerRecordPatchIntegrated`, `LedgerResolverComplete`,
`LedgerWatch`, `WorkerCleanup`) stay on `EventLedger` directly via `recordEvent`
or the `EventLedger` instance — they do not need `PlanService`.

`Shipsmooth.java` wiring updated to pass `PlanService` where needed.

## Verification

```bash
mvn test -pl app
```

All existing tests must pass. No behaviour change — this is a pure structural
refactor.
