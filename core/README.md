# core

The core data model and functionality for shipsmooth, exposed as services. This is the
domain layer. It has no command line or plugin rendering bits.

- The data model is roughly the value types the workflow needs: task/plan state, 
  configuration (`ExperimentalMode`), and so on. Mostly immutable records and enums.
- The services are actual behaviour, grouped by area. Some examples below:
  - `PlanService`: plan and task lifecycle (init, state transitions, deviations)
  - `WorkflowService`: the end-to-end agent-coding workflow
  - `TaskStore` : reads/writes the task state file

The services are wired together with [Dagger](https://dagger.dev/). Two pieces, both
in the `io.bitken.ss.conf` package:

- **`ServicesModule`** (`@Module`): `@Provides` each service as a `@Singleton`,
  declaring how it's constructed and what it depends on.
- **`AppComponents`** (`@Component(modules = ServicesModule.class)`): the
  `@Singleton` component interface that exposes the services to consumers
  (`planService()`, `workflowService()`, `taskStore()`, …).

## See also

- [`../cli/`](../cli) : the CLI that drives these services
- [`../DEVELOPMENT.md`](../DEVELOPMENT.md): repo structure and build instructions
