# Plan 64 — add-task CLI subcommand

## Context

Backlog issue: PB-353 — shipsmooth add-task: append a new task to an existing plan's XML

Plans sometimes need a task added mid-execution: a new risk surfaces during de-risking, or a
human decides to scope in extra work. Currently the only path is hand-editing the XML, which
is error-prone. This plan adds an `add-task` subcommand to close that gap.

## Proposed CLI interface

```
shipsmooth add-task --plan <N> --name <TEXT> --risk <high|medium|low> [--depends-on <ids>]
```

- Auto-assigns the next integer task ID (max existing ID + 1).
- Sets status to `pending`, commit to `""`, `created-from` to the current plan-version tag
  (read via `GitTags.getPlanVersion`).
- Appends `<depends-on>` if `--depends-on` is supplied (comma-separated integers, e.g. `1,3`).
- Prints `"Added task <id>: <name>"` on success.

No ledger interaction. Out of scope: bulk add, insert at a specific position, re-ordering.

## Architecture

Three layers, following the established pattern:

1. **`TaskStore.addTask`** — pure XML mutation: load plan → append task element → save.
2. **`PlanService.addTask`** — thin wrapper that calls `taskStore` load/mutate/save (no ledger).
3. **`AddTask` CLI command** — new file under `io.bitken.ss.cli.task`, registered in `CommandTree`.

`GitTags.getPlanVersion` is already called by `Init` for `created-from`, reused here.

## Open questions

- Should `--risk` be optional (default `""`)? Leaning yes — XSD allows empty string.
- Should `--depends-on` validate referenced IDs exist? Leaning no (matches `init` behaviour).

## Tasks

### Task 1: Add `TaskStore.addTask` method [Medium]

Implement `TaskStore.addTask(PlanTasks, TaskStore.Task)` that:
- Computes next ID as `max(existing IDs) + 1` (or 1 if the plan has no tasks).
- Creates a `TaskType` with all required fields (status=pending, commit="", created-from,
  closed-at-version="", empty comments/deviations containers).
- Calls `setDependsOn` if `task.dependsOn()` is non-blank.
- Appends to `planTasks.getTasks().getTask()`.
- Returns the assigned task ID.

### Task 2: Add `PlanService.addTask` method [Low]

*Depends-on: 1*

Implement `PlanService.addTask(int planId, String name, String risk, String dependsOn,
String planVersion)` that:
- Loads the plan XML, calls `taskStore.addTask`, saves the plan.
- No ledger recording.
- Returns the new task ID for display.

### Task 3: Add `AddTask` CLI command and register it [Low]

*Depends-on: 2*

Create `io.bitken.ss.cli.task.AddTask` following the `AddComment` pattern:
- Options: `--plan` (int, required), `--name` (String, required), `--risk` (String, default `""`),
  `--depends-on` (String, optional).
- Resolves `created-from` via `GitTags.getPlanVersion(plan)`.
- Calls `planService.addTask(...)`, prints `"Added task <id>: <name>"`.
- Register in `CommandTree.buildCommands` (non-experimental).
