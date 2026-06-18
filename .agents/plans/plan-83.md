# plan-83 — Help commands improvement

## Context

Feature (in the user's words): the CLI help commands are not well structured /
missing pieces.

The `shipsmooth` CLI is hand-built with picocli `CommandSpec`s in
`cli/src/main/java/io/bitken/ss/cli/` — there are no `@Command`/`@Option`
annotations. The root spec and the two noun groups (`plan`, `task`) are wired in
`CommandTree`; each leaf builds its own `CommandSpec` in its constructor and is
attached to its group by the group's `addLeaves(...)` helper.

Backlog feature (Local mode, no external tracker): **CLI help quality** — the
`shipsmooth` CLI's `--help` output across the command tree should be correct,
complete, and consistent. This plan delivers the first increment against that
feature.

Test coverage threshold (hardening phase, net-new code): **95%** (skill
default).

### Findings (observed on the dev jlink build, v0.3.23)

Captured by running `--help` across the whole tree on
`cli/build/jlink-image-linux-x64/bin/shipsmooth`:

1. **Bug — `--help` fails on every leaf command.** `shipsmooth plan show --help`
   prints `Missing required option: '--plan=PARAM'` and exits **2** (error)
   instead of printing help and exiting 0. This affects all 13 leaves under
   `plan` and `task`. Root cause: `Plan.addLeaves` and `Task.addLeaves`
   (`cli/.../plan/Plan.java`, `cli/.../task/Task.java`) attach each leaf spec
   *without* calling `mixinStandardHelpOptions(true)`. The root and group specs
   get the mixin (in `CommandTree.buildRootSpec` / `CommandTree.register`), so
   `shipsmooth --help` and `shipsmooth plan --help` correctly exit 0 — only the
   leaves are broken. With no `--help` option present, picocli treats `--help`
   as an unknown arg and runs required-option validation first.

2. **Missing option descriptions.** Several leaves build options with no
   `.description(...)`, so picocli renders a bare placeholder with empty help
   text: `plan show --plan=PARAM`, `plan update` (`--blocked/--message/--plan/
   --status`), `task status` (`--plan/--status/--task`), `task set-commit`
   (`--branch/--commit/--plan/--task`), and `plan quick --desc=PARAM`. Contrast
   `task add`, which documents every option.

3. **Inconsistent placeholder labels.** A mix of descriptive labels
   (`PLAN_NUMBER`, `TASK_ID`, `TEXT`) and the meaningless default `PARAM`. The
   same concept (plan number) renders as `PLAN_NUMBER` in `task add` but `PARAM`
   in `plan show`.

4. **Group description duplicates the command list.** `plan`'s description is
   `"Plan-level commands (init, quick, show, update, preflight, tag, branch,
   resume)."` — redundant with the `Commands:` block rendered right below it,
   and guaranteed to drift as commands are added/removed. `task` has the same
   pattern.

5. **No enumerated valid values / examples.** `plan tag --kind=PARAM` does not
   state the valid kinds (`version|complete|abandoned`); `plan update
   --status=PARAM` and `task status --status=PARAM` do not list valid statuses;
   `task deviation --type=PARAM` does not list valid types. Users must read the
   skill or source to know accepted values.

### Goal

Every command in the tree — root, both groups, and all 13 leaves — should:
- respond to `--help` with proper help text and exit 0;
- give every option a one-line description and a meaningful param label;
- enumerate valid values for constrained options;
- have group descriptions that don't restate the auto-rendered command list.

### Out of scope

- Renaming commands or changing their argument contracts.
- Restructuring the command tree (no new groups, no moved leaves).
- Changing runtime behaviour of any command beyond help output and the
  `--help`-exits-0 fix.

## Tasks

Risk-sorted (descending). T1 is Medium and is also a hard dependency for the
rest, so it leads naturally.

### Task 1: Fix leaf --help mixin bug [Medium]

Make every leaf under `plan` and `task` respond to `--help` with usage and exit
0. Root cause is `Plan.addLeaves` / `Task.addLeaves` attaching leaf specs
without `mixinStandardHelpOptions(true)` (the root and group specs already get
it in `CommandTree`). Fix at the `addLeaves` seam so all 13 leaves are covered
uniformly. Add a unit test asserting a representative leaf (e.g. `plan show`)
returns exit 0 on `--help` and prints its usage to stdout/stderr rather than a
`Missing required option` error.

### Task 2: Add option descriptions and param labels [Low]

*Depends-on: 1*

Give every under-documented option a one-line `.description(...)` and a
meaningful `paramLabel` instead of the bare `PARAM` default. Cover the offending
leaves: `plan show` (`--plan`), `plan update` (`--blocked/--message/--plan/
--status`), `plan quick` (`--desc`), `task status` (`--plan/--status/--task`),
`task set-commit` (`--branch/--commit/--plan/--task`). Match the existing good
labels (`PLAN_NUMBER`, `TASK_ID`, `TEXT`) for consistency across the tree.

### Task 3: Enumerate valid values for constrained options [Low]

*Depends-on: 1*

Add the accepted values into the descriptions of constrained options, sourced
from the actual service-layer contracts: `plan tag --kind`
(`version|complete|abandoned`), `plan update --status` and `task status
--status` (valid statuses), `task deviation --type` (valid types). Verify each
list against the code that consumes the value before writing it.

### Task 4: Fix self-duplicating group descriptions [Low]

*Depends-on: 1*

Replace the `plan` and `task` group descriptions that re-list their own verbs
(redundant with picocli's auto-rendered `Commands:` block, and drift-prone) with
a stable one-line summary of the group's purpose.
