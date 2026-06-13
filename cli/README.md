# cli

The **shipsmooth CLI** (`shipsmooth`, a picocli app; JPMS module
`io.bitken.ss.cli`) — the command-line tool the shipsmooth
[SKILL](https://github.com/bitkentech/shipsmooth/blob/releases/dist/skills/start/SKILL.md)
relies on for **plan and task management**.

When an agent runs the shipsmooth workflow, the SKILL shells out to this CLI for
the operations it needs — creating and tagging plans, initialising the task file,
moving tasks through their states, and recording deviations (e.g. `plan init`,
`plan tag`, `plan branch`, `task status`, `task set-commit`, `task deviation`).

## How it relates to the rest of the repo

- **Depends on [`../core`](../core)** for much of its functionality. `core`
  (`io.bitken.ss.core`) holds the actual domain logic — the workflow, ledger, git
  operations, and plan/task services. This module is the thin command-line surface
  over that logic: it parses arguments, wires the commands, and prints results,
  while `core` does the work.
- **Builds the jlink runtime image.** `cli` produces the self-contained
  `shipsmooth` runtime (`image_<host>` tasks) that gets packaged and shipped — the
  binary the installed plugin downloads and runs per session.

## See also

- [`../core/`](../core) — the domain logic this CLI drives
- [`../DEVELOPMENT.md`](../DEVELOPMENT.md) — repo structure and build instructions
- the shipsmooth [SKILL](https://github.com/bitkentech/shipsmooth/blob/releases/dist/skills/start/SKILL.md)
  — the workflow that calls this CLI
