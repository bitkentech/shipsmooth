# Plan 41 — Feature-flag gate for experimental CLI commands

**Status:** open
**Version:** v1
**Branch:** `t/plan-41-experimental-gate`
**Tracking mode:** Local (`.agents/plans/plan-41-tasks.xml`).
**Depends on:** plan-40 (parallel-execution skill split, merged to main as 60ff636).

---

## 1. Context

Plan-40 split the parallel-execution workflow into its own skill
(`experimental-start-parallel(-dev)`), so the base `start-dev` skill no longer
documents the parallel commands. The Java CLI was deliberately left untouched
in plan-40 — all 10 experimental subcommands are still registered on
`shipsmooth-tasks` and runnable without any flag.

This plan adds the **hard gate**:

1. A top-level `--enable-experimental` option on `shipsmooth-tasks`.
2. The 10 experimental subcommands (`claim`, `worker-init`, `worker-finish`,
   `worker-cleanup`, `worker-base`, `integrate`, `ledger-watch`,
   `ledger-resolver-complete`, `ledger-record-commit`,
   `ledger-record-patch-integrated`) become `hidden = true` by default.
3. Invoking any experimental subcommand without `--enable-experimental` prints
   `Unknown command: <name>` and exits with code 2 (matching picocli's normal
   unknown-command behaviour as closely as possible).
4. When the flag is set, the experimental subcommands become visible in
   `--help` and execute normally.

After this gate lands, the CLI examples in
`_partials/parallel-execution.jte.md` are rewritten to include
`--enable-experimental` so the rendered `experimental-start-parallel(-dev)`
skill is self-consistent with CLI behaviour.

The experimental command **classes still ship** in the JAR — the goal is
"hidden and refuses to run," not "not compiled in" (matches plan-40's existing
single-build-artifact decision).

---

## 2. Design notes

### picocli gate

`TasksCli` registers all commands unconditionally in the `CommandSpec` but
marks the 10 experimental subcommands `hidden(true)`. A custom
`IExecutionStrategy` runs before `RunLast`:

- Walk `ParseResult.subcommand()` chain to find the deepest invoked
  subcommand name (top-level if none).
- If the selected subcommand is in `EXPERIMENTAL_COMMANDS` and
  `--enable-experimental` was **not** set on the top-level → print
  `Unknown command: <name>` to stderr, exit 2.
- If `--enable-experimental` **was** set → walk the spec and flip
  `hidden(false)` on all experimental subcommands (so `--help` lists them),
  then delegate to `new CommandLine.RunLast().execute(parseResult)`.

The 10 experimental command names (confirm exact strings from each command's
`getSpec()` before committing):
`claim`, `worker-init`, `worker-finish`, `worker-cleanup`, `worker-base`,
`integrate`, `ledger-watch`, `ledger-resolver-complete`,
`ledger-record-commit`, `ledger-record-patch-integrated`.

Non-experimental (unaffected): `init`, `show`, `update-status`, `add-comment`,
`add-deviation`, `set-commit`, `project-update`, `ledger` (with its
`list`/`verify`/`read` subcommands).

### SKILL.md update

After the gate is in place, `parallel-execution.jte.md` gets every
`${model.cliBin()} <experimental-subcommand> ...` rewritten to
`${model.cliBin()} --enable-experimental <experimental-subcommand> ...`. A
matching integration test asserts the rendered
`experimental-start-parallel-dev/SKILL.md` contains the flag.

---

## 3. Tasks

### Task 1: Add `--enable-experimental` gate to TasksCli [High]

Edit `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/TasksCli.java`:

1. Define `private static final Set<String> EXPERIMENTAL_COMMANDS = Set.of(
   "claim", "worker-init", "worker-finish", "worker-cleanup", "worker-base",
   "integrate", "ledger-watch", "ledger-resolver-complete",
   "ledger-record-commit", "ledger-record-patch-integrated");`
   (Subcommand names — confirm exact strings from each command's `getSpec()`
   before committing.)
2. Add top-level `@CommandLine.Option(names = "--enable-experimental")` boolean
   into the existing mixin (currently around `TasksCli.java:38-44`).
3. After `addSubcommand` calls, walk the spec and call
   `subSpec.usageMessage().hidden(true)` for every name in
   `EXPERIMENTAL_COMMANDS`.
4. Install a custom `IExecutionStrategy` on `cmd`:
   - Walk `ParseResult.subcommand()` chain to find the deepest invoked
     subcommand name (top-level if none).
   - If that name is in `EXPERIMENTAL_COMMANDS` and the top-level
     `--enable-experimental` flag is `false`:
     - Print `Unknown command: <name>` to `cmd.getErr()`.
     - Return exit code 2.
   - If the flag is `true`, walk the spec and un-hide all experimental
     subcommands (so `--help` lists them), then delegate to
     `new CommandLine.RunLast().execute(parseResult)`.

Write `plugin-tasks-java/src/test/java/io/bitken/shipsmooth/tasks/TasksCliTest.java`
(new) covering:

1. `new TasksCli(app).execute("integrate", "--help")` → exit 2, stderr
   contains `Unknown command: integrate`.
2. `new TasksCli(app).execute("--enable-experimental", "integrate", "--help")`
   → exit 0, stdout contains integrate's usage line.
3. `new TasksCli(app).execute("--help")` → exit 0, stdout does **not**
   contain "integrate" or any of the 10 experimental subcommand names.
4. `new TasksCli(app).execute("--enable-experimental", "--help")` → exit 0,
   stdout contains all 10 experimental subcommand names.
5. `new TasksCli(app).execute("show", "--help")` → exit 0 (non-experimental
   command still works without flag).

Run `mvn -pl plugin-tasks-java test` — all green.

### Task 2: Update parallel-execution partial to pass --enable-experimental [Medium]

*Depends-on: 1*

In `plugin-skill/src/main/jte-src/skills/_partials/parallel-execution.jte.md`,
prefix every `${model.cliBin()} <experimental-subcommand> ...` with
`--enable-experimental` (e.g.
`${model.cliBin()} --enable-experimental integrate --plan {N} ...`).

Also update any `claude/` or `gemini/` sub-partials referenced from
`parallel-execution.jte.md` that contain experimental CLI invocations.

Add an integration-test assertion to
`plugin-skill/src/test/java/io/bitken/shipsmooth/resources/ResourceBuilderIntegrationTest.java#experimentalParallelSkillIsRendered`:

```java
assertTrue(content.contains("--enable-experimental"),
    "experimental parallel skill should call the CLI with --enable-experimental");
```

Run `mvn -pl plugin-skill test` — all green.

### Task 3: End-to-end verification with the freshly built CLI [Low]

*Depends-on: 1,2*

Build the jlink image and exercise the gate with the real binary:

```bash
mvn -pl plugin-tasks-java -am -Pjlink package
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks --help
  # → no experimental commands listed
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks integrate --help
  # → exit 2, "Unknown command: integrate"
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks --enable-experimental --help
  # → all 10 experimental commands listed
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks --enable-experimental integrate --help
  # → integrate's usage prints
```

Spot-check the rendered `build/skills/experimental-start-parallel-dev/SKILL.md`:
`grep --enable-experimental` should match every experimental command line.

---

## 4. Verification (end-to-end)

See Task 3 commands. Coverage threshold: 80% (matches plan-40 agreement).
