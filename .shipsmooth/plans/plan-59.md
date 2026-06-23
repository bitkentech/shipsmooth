# Plan 59: Introduce PlanService + Rename LedgerService → EventLedger + Package Restructure

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

3. **Package structure does not reflect PoEAA roles.** A follow-up session
   (2026-05-27) applied the PoEAA lens to the remaining classes:
   - `XmlService` is a Gateway (wraps XML file I/O), not a service. Rename to
     `TaskStore` and move to `gw/`.
   - `GitTagService` is a Gateway (wraps `git tag` operations). Rename to `GitTags`
     and move to `gw/`.
   - Plan-related classes (`PlanService`, `PlanMarkdown`, `PlanMarkdownParser`,
     `PlanSummaryFormatter`) form a coherent group — move to `svc/plan/`.
   - `WorktreeService` stays in `git/` — it owns a `Semaphore` for concurrent git
     index writes and is too deeply referenced to move without significant churn.

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

### Task 3: Migrate UpdateStatus to PlanService (tracer bullet) [High]

*Depends-on: 2*

Migrate `UpdateStatus` as the tracer bullet — it is the simplest command that
exercises the full XML-mutate + ledger-record path and validates that the
`PlanService` API shape is correct end-to-end before the bulk migration.

- `UpdateStatus` — replace `XmlService` + `EventLedger` injection with
  `PlanService`; delegate to `updateTaskStatus(plan, task, status)`
- Update `Shipsmooth.java` wiring for this one command
- Confirm `mvn test -pl app` is green before proceeding to Task 4

### Task 4: Migrate remaining CLI commands to PlanService [Low]

*Depends-on: 3*

Migrate the remaining commands after the tracer bullet confirms the API:

- `AddComment` — `addComment(plan, task, message)`
- `AddDeviation` — `addDeviation(plan, task, type, message)`
- `SetCommit` — `setTaskCommit(plan, task, commit, branch)`
- `ProjectUpdate` — `projectUpdate(plan, status, blocked, message)`
- `Init` — `initPlan(plan, planVersion, tasks)` (keep `GitTagService` injection)
- `WorkerBase` — `loadPlan` + `findCommitSha`

Commands that only write ledger events with no XML mutation (`Claim`,
`LedgerRecordCommit`, `LedgerRecordPatchIntegrated`, `LedgerResolverComplete`,
`LedgerWatch`, `WorkerCleanup`) stay on `EventLedger` directly — they do not
need `PlanService`.

`Shipsmooth.java` wiring updated for all migrated commands.

### Task 5: Rename XmlService → TaskStore and move to gw/ [Low]

*Depends-on: 4*

Apply the PoEAA Gateway pattern naming. `XmlService` wraps XML file I/O — it is
a Gateway, not a Service Layer member.

- Rename `service/XmlService.java` → `gw/TaskStore.java`
- Update package declaration to `io.bitken.ss.gw`
- Update all import sites (main + test): `WorkflowServiceImpl`, `PlanService`,
  `PlanMarkdown`, `PlanMarkdownParser`, `ServicesModule`, `AppComponents`,
  `Claim`, `Show`, `WorkerBase`, `Init`, `Shipsmooth`, all test files referencing
  `XmlService` or `XmlServiceTest`
- Rename `XmlServiceTest` → `TaskStoreTest`, update package
- Any references to `XmlService.Task`, `XmlService.PlanTasks` etc. update to
  `TaskStore.Task`, `TaskStore.PlanTasks`

### Task 6: Rename GitTagService → GitTags and move to gw/ [Low]

*Depends-on: 5*

`GitTagService` wraps `git tag` operations — a Gateway. Move alongside `TaskStore`.

- Rename `git/GitTagService.java` → `gw/GitTags.java`
- Update package declaration to `io.bitken.ss.gw`
- Update all import sites: `ServicesModule`, `AppComponents`, `Init`, `Shipsmooth`,
  any test files referencing `GitTagService`
- The `git/` package retains only `WorktreeService`

### Task 7: Move PlanService and friends to svc/plan/ [Low]

*Depends-on: 6*

Collect all Plan-domain classes into a coherent subpackage.

- Move `service/PlanService.java` → `svc/plan/PlanService.java`
- Move `service/PlanMarkdown.java` → `svc/plan/PlanMarkdown.java`
- Move `service/PlanMarkdownParser.java` → `svc/plan/PlanMarkdownParser.java`
- Move `service/PlanSummaryFormatter.java` → `svc/plan/PlanSummaryFormatter.java`
- Update package declarations to `io.bitken.ss.svc.plan`
- Update all import sites
- Rename `service/PlanServiceTest.java` → `svc/plan/PlanServiceTest.java`
- Delete the now-empty `service/` package directory

## Final Package Structure

```
io/bitken/ss/
├── cli/
├── conf/
├── git/
│   └── WorktreeService.java
├── gw/
│   ├── GitTags.java
│   └── TaskStore.java
├── ledger/
│   └── EventLedger.java
├── svc/
│   └── plan/
│       ├── PlanMarkdown.java
│       ├── PlanMarkdownParser.java
│       ├── PlanService.java
│       └── PlanSummaryFormatter.java
└── workflow/
```

## Verification

```bash
mvn test -pl app
```

All existing tests must pass after each task. No behaviour change — this is a
pure structural refactor.
