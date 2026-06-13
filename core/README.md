# core

shipsmooth's **data model and core functionality, exposed as services** (JPMS
module `io.bitken.ss.core`). This is the domain layer — it has no command-line or
plugin-rendering concerns; those live in [`../cli`](../cli) and
[`../harness`](../harness), which drive the services defined here.

## What's in here

- **Data model** — the value types the workflow operates on: the event ledger
  (`Event`, `EventType`), git/integration results (`MergeResult`,
  `IntegrationResult`), task/plan state, configuration (`ExperimentalMode`), and so
  on. Mostly immutable records and enums.
- **Core functionality as services** — the actual behaviour, grouped by area:
  - `PlanService` — plan and task lifecycle (init, state transitions, deviations)
  - `WorkflowService` — the end-to-end agent-coding workflow
  - `TaskStore` — reads/writes the task state file
  - `EventLedger` — the append-only event log
  - `GitState` / `GitTags` / `WorktreeService` — git operations
  - `ShipsmoothDataLocator` — resolves where shipsmooth's data lives in a repo

## Dependency injection (Dagger)

The services are wired together with [Dagger](https://dagger.dev/). Two pieces, both
in the `io.bitken.ss.conf` package:

- **`ServicesModule`** (`@Module`) — `@Provides` each service as a `@Singleton`,
  declaring how it's constructed and what it depends on. Its one runtime input is
  the repo root (`Path`); everything else is derived from that.
- **`AppComponents`** (`@Component(modules = ServicesModule.class)`) — the
  `@Singleton` component interface that exposes the services to consumers
  (`planService()`, `workflowService()`, `taskStore()`, …).

A consumer bootstraps the graph by building the component with a repo root, e.g.:

```java
AppComponents app = DaggerAppComponents.builder()
    .servicesModule(new ServicesModule(repoRoot))
    .build();
app.planService();   // fully-wired, ready to use
```

`cli` does exactly this at startup (see `cli/.../Shipsmooth.java`). Keeping the
wiring in `core` means any consumer gets the same fully-constructed service graph
from a single entry point.

## See also

- [`../cli/`](../cli) — the CLI that drives these services
- [`../DEVELOPMENT.md`](../DEVELOPMENT.md) — repo structure and build instructions
