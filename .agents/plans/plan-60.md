# Plan 60: Encapsulate Agent Layout Path Access via ShipsmoothDataLocator

## Context

`AgentsLayout.java` exists as the canonical path authority for the `.agents/` directory
tree, but it is almost entirely unused. Only `Init.java` calls it (for `bootstrap()`).
Every other site in the codebase constructs `.agents/` paths independently:

- `TaskStore.java` — two path methods duplicating `AgentsLayout` exactly, using
  `new File(".")` relative paths instead of the injected `repoRoot`
- `Claim.java`, `Show.java` — each manually build `new File(".agents/plans/plan-N-tasks.xml")`
- `WorkerCleanup.java` — builds `.agents/tasks/{taskId}` inline
- `WorkflowServiceImpl.java` — builds worktree and integration paths in two or three places each
- `EventLedger.java`, `ObjectStore.java` — construct ledger/object-store paths via
  `repoRoot.resolve(".agents").resolve(...)` rather than delegating to `AgentsLayout`
- `Init.java` — hardcodes the four gitignore entries as string literals

The fix is to rename `AgentsLayout` → `ShipsmoothDataLocator` (a Registry in PoEAA
terms: a well-known object others consult to find where shipsmooth data lives), wire it
into Dagger, and thread it through to every caller. `TaskStore.planTasksFile` and
`TaskStore.planMarkdownFile` become private — `TaskStore` is a Gateway over XML I/O and
has no business owning path logic. The name `ShipsmoothDataLocator` anticipates a future
config option to relocate the `.agents/` tree outside the repo.

The `Init.java` gitignore list is a minor secondary concern: it can be driven from
`ShipsmoothDataLocator` constants to avoid drift if paths ever change.

## Design

### Task 1: Rename AgentsLayout → ShipsmoothDataLocator and wire into Dagger [Low]

Rename `AgentsLayout.java` → `ShipsmoothDataLocator.java`, update the class name and
all existing import sites (currently only `Init.java`). Add a static constant for
gitignore entries (used in Task 5):

```java
public static final List<String> GITIGNORE_ENTRIES = List.of(
    ".agents/tasks/*",
    ".agents/integration/*",
    ".agents/objects/",
    ".agents/ledger.jsonl"
);
```

Add a `@Provides` method in `ServicesModule`:

```java
@Provides @Singleton
ShipsmoothDataLocator provideDataLocator(Path repoRoot) {
    return new ShipsmoothDataLocator(repoRoot);
}
```

Expose it in `AppComponents`:

```java
ShipsmoothDataLocator dataLocator();
```

No other changes in this task — this just makes `ShipsmoothDataLocator` available to
all Dagger-managed classes.

### Task 2: Make TaskStore a pure storage Gateway [Low]

*Depends-on: 1*

`TaskStore` is a Gateway over XML task file I/O. It should encapsulate storage
completely — callers ask it to load or save a plan by ID, not where the file is.
Path resolution is `ShipsmoothDataLocator`'s job.

Changes:
- Add `ShipsmoothDataLocator` as a constructor dependency; update `ServicesModule.provideTaskStore`
- Replace the two hardcoded `new File(".agents/...")` methods with `ShipsmoothDataLocator`
  delegation internally — `planTasksFile` and `planMarkdownFile` become **private**
- `loadPlan(int planId)` and `savePlan(int planId, PlanTasks plan)` are already public and
  use the private path methods internally; they remain unchanged
- Remove any remaining callers of the now-private path methods:
  - `PlanService` line 26: `xml.writePlanTasks(plan, xml.planTasksFile(planId))`
    → `xml.savePlan(planId, plan)`
  - `PlanService` line 103: `xml.planTasksFile(planId)` → `xml.loadPlan(planId)` (the
    file reference is only used to call `readPlanTasks` immediately after)
  - `Claim` line 49: `new File(".agents/plans/...")` + `xmlService.readPlanTasks(xmlFile)`
    → `xmlService.loadPlan(plan)`
  - `Show` line 35: same pattern → `xmlService.loadPlan(plan)`

`WorkflowServiceImpl` is the one caller that needs the XML file path for a purpose
beyond XML I/O — it passes `ctx.xmlFile` to `git.commitFile()` after integration.
That path should come from `ShipsmoothDataLocator` directly (injected in Task 3), not
from `TaskStore`. The `xmlService.planTasksFile(plan)` calls in `WorkflowServiceImpl`
lines 131 and 220 are replaced with `locator.planTasksFile(plan)` once
`ShipsmoothDataLocator` is injected in Task 3.

### Task 3: Replace hardcoded paths in WorkflowServiceImpl, WorkerCleanup [Low]

*Depends-on: 1*

Add `ShipsmoothDataLocator` to `WorkflowServiceImpl`'s constructor (alongside existing
`repoRoot` — `repoRoot` stays, it is still needed for `getFilesTouched` and
`git.commitFile`). Replace inline path constructions:

- Lines 65 & 95: `".agents/tasks/" + taskId` → `locator.worktreeRel(taskId)`
- Line 224: `".agents/integration/plan-" + plan` → `locator.integrationRel(plan)`
- Lines 131 & 220: `xmlService.planTasksFile(plan)` → `locator.planTasksFile(plan)`

`WorkerCleanup` line 43: inject `ShipsmoothDataLocator`, replace `".agents/tasks/" + task`
→ `locator.worktreeRel(task)`. Wire via `Shipsmooth.java`.

### Task 4: Replace path construction in EventLedger and ObjectStore [Low]

*Depends-on: 1*

Both classes construct `.agents/` paths from `repoRoot` directly. Thread
`ShipsmoothDataLocator` in instead:

- `EventLedger`: constructor takes `ShipsmoothDataLocator`; replace
  `repoRoot.resolve(".agents").resolve("ledger.jsonl")` with `locator.ledgerPath()`;
  pass `locator.objectStorePath()` to `ObjectStore`
- `ObjectStore`: constructor takes `Path root` (already does); the path now comes from
  `locator.objectStorePath()` passed by `EventLedger`, not reconstructed internally
- Update `ServicesModule.provideEventLedger` to inject `ShipsmoothDataLocator`

### Task 5: Drive Init.java gitignore entries from ShipsmoothDataLocator constants [Low]

*Depends-on: 1*

Replace the hardcoded string array in `Init.java` line 86 with
`ShipsmoothDataLocator.GITIGNORE_ENTRIES` (added in Task 1).

## Verification

```bash
mvn test -pl app
```

All tests must remain green after each task. No behaviour change — pure structural
refactor: rename + centralize path resolution.
