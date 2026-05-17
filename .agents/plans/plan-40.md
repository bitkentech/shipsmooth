# Plan 40 — Gate parallel-execution features behind `--enable-experimental`

**Status:** open
**Branch:** `t/plan-40-enable-experimental-gate`
**Tracking mode:** Local (`.agents/plans/plan-40-tasks.xml`).

---

## 1. Context

The shipsmooth-tasks CLI currently exposes the parallel-execution / integrate /
ledger-coordination commands (`claim`, `worker-init`, `worker-finish`,
`worker-cleanup`, `worker-base`, `integrate`, `ledger-watch`,
`ledger-resolver-complete`, `ledger-record-commit`,
`ledger-record-patch-integrated`) unconditionally, and the matching workflow is
documented in the main `start`/`start-dev` SKILL.md.

These features are still experimental. The goal of this plan is:

1. Keep the experimental command classes in the JAR (single build artifact —
   useful for catching regressions of the experimental code against existing
   prod code) but **hide them and refuse to run them** in normal invocations.
2. Add a top-level `--enable-experimental` flag on `shipsmooth-tasks` that
   unlocks them. **Hard gate:** without the flag, picocli reports
   "Unknown command".
3. Move the Parallel Execution Protocol documentation out of `start-dev`
   SKILL.md into a new skill `experimental-start-parallel-dev` (and
   `experimental-start-parallel` in prod profiles) so the agent only sees
   parallel-execution instructions when that skill is explicitly engaged.
4. The TLA variant (`experimental-start-tla(-dev)`) remains sequential and
   unchanged — it stays an experimental skill but does not gain any parallel
   content.

Refactoring SKILL.md sources to share content uses **JTE `@template` includes**
so the common base workflow lives in one partial and both `start[-dev]` and
`experimental-start-parallel[-dev]` render from it.

---

## 2. Design notes

### Java gate (picocli)

`TasksCli` registers all commands unconditionally in the `CommandSpec` but
marks the 10 experimental subcommands `hidden(true)`. A custom
`IExecutionStrategy` runs before `RunLast`:

- If the selected subcommand is in `EXPERIMENTAL_COMMANDS` and the top-level
  `--enable-experimental` option is absent → print `Unknown command: <name>`
  to stderr, exit code 2 (matches picocli's normal unknown-command behaviour
  as closely as possible).
- If `--enable-experimental` is set → flip all experimental subcommands to
  `hidden(false)` so `--help` lists them, then delegate to `RunLast`.

The 10 experimental commands:
`ClaimCommand`, `WorkerInitCommand`, `WorkerFinishCommand`,
`WorkerCleanupCommand`, `WorkerBaseCommand`, `IntegrateCommand`,
`LedgerWatchCommand`, `LedgerResolverCompleteCommand`,
`LedgerRecordCommitCommand`, `LedgerRecordPatchIntegratedCommand`.

Non-experimental (unaffected): `InitCommand`, `ShowCommand`,
`UpdateStatusCommand`, `AddCommentCommand`, `AddDeviationCommand`,
`SetCommitCommand`, `ProjectUpdateCommand`, `LedgerCommand` (list/verify/read
subcommands).

### SKILL.md restructuring (JTE)

New file layout under `plugin-skill/src/main/jte-src/skills/`:

```
_partials/
  base-workflow.jte.md       # everything from start/SKILL.jte.md MINUS the parallel section
  parallel-execution.jte.md  # the extracted parallel section, including session-resume recovery block
start/
  SKILL.jte.md               # shell: frontmatter + title + include base-workflow
experimental/
  start-parallel/
    SKILL.jte.md             # shell: frontmatter + title + include base-workflow + include parallel-execution
  start-tla/
    SKILL.jte.md             # unchanged
    tla-model.jte.md         # unchanged
```

`base-workflow.jte.md` and `parallel-execution.jte.md` both take
`PluginModel model` as a `@param` so `${model.cliBin()}` etc. continue to
render. Existing `claude/` and `gemini/` platform-conditional sub-partials
stay in `skills/start/{claude,gemini}/` and are referenced from
`parallel-execution.jte.md` (those partials are exclusively about
parallel-execution UI; they don't need to move).

### Recovery block

The Phase 2 session-resume "integrate recovery" block in the current base
SKILL.md (lines 195–229 of `start/SKILL.jte.md`) calls
`ledger-record-patch-integrated`. It **moves into** `parallel-execution.jte.md`
because recovery only matters if you ran `integrate`, which is now
experimental. `ledger-record-patch-integrated` stays experimental.

### CLI invocation rewrites

Every `${model.cliBin()} <experimental-subcommand> ...` example inside
`parallel-execution.jte.md` becomes
`${model.cliBin()} --enable-experimental <experimental-subcommand> ...`.

### ResourceBuilder

Add a third `renderTo(...)` block alongside the existing TLA block (currently
at `ResourceBuilder.java:41-58`) that emits `experimental-start-parallel` or
`experimental-start-parallel-dev` depending on whether the primary skill
ends in `-dev`. Frontmatter derivation mirrors the TLA pattern.

---

## 3. Tasks

### Task 1: Extract base workflow into JTE partial (no content change) [Medium]

Move the entire body of `start/SKILL.jte.md` (everything after frontmatter and
title) into a new partial `_partials/base-workflow.jte.md` with `@param
PluginModel model`. Rewrite `start/SKILL.jte.md` as a thin shell:

```
@import io.bitken.shipsmooth.resources.PluginModel
@param PluginModel model
@if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif

# ${model.skillName()} — Agent Coding Workflow

@template.skills._partials.base-workflow(model = model)
```

No deletion yet — the parallel section still ships inside the partial.
Verification: `mvn -P dev clean install` produces `build/skills/start-dev/SKILL.md`
that is **byte-identical** (modulo possible trailing-newline differences) to
the pre-refactor output. Capture the pre-refactor file before this task and
diff after.

### Task 2: Add `--enable-experimental` gate to TasksCli [High]

*Depends-on: 1*

Edit `plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/TasksCli.java`:

1. Define `private static final Set<String> EXPERIMENTAL_COMMANDS = Set.of(
   "claim", "worker-init", "worker-finish", "worker-cleanup", "worker-base",
   "integrate", "ledger-watch", "ledger-resolver-complete",
   "ledger-record-commit", "ledger-record-patch-integrated");`
   (Subcommand names — confirm exact strings from each command's `getSpec()`
   before committing.)
2. Add top-level `@CommandLine.Option(names = "--enable-experimental")` boolean
   into the existing mixin at lines 38–44.
3. After `addSubcommand`, walk the spec and call `subSpec.usageMessage().hidden(true)`
   for every name in `EXPERIMENTAL_COMMANDS`.
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

### Task 3: Extract parallel section into partial; create experimental-start-parallel skill [High]

*Depends-on: 1,2*

1. Move lines `## Parallel Execution Protocol (optional)` through end of
   `### Worker Instruction Block` and its trailing horizontal rule out of
   `_partials/base-workflow.jte.md` into a new
   `_partials/parallel-execution.jte.md` (with `@param PluginModel model`).
2. Move the Phase 2 session-resume "integrate recovery" block (the bulleted
   list under "**If an `integration/plan-{N}` worktree is found...**") from
   `base-workflow.jte.md` into the top of `parallel-execution.jte.md` under a
   new `### Session-resume recovery` heading.
3. In `parallel-execution.jte.md`, prefix every experimental CLI invocation
   with `--enable-experimental` (e.g.
   `${model.cliBin()} --enable-experimental integrate --plan {N} ...`).
4. Create `skills/experimental/start-parallel/SKILL.jte.md`:
   ```
   @import io.bitken.shipsmooth.resources.PluginModel
   @param PluginModel model
   @if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif

   # ${model.skillName()} — Agent Coding Workflow (Parallel Execution)

   @template.skills._partials.base-workflow(model = model)
   @template.skills._partials.parallel-execution(model = model)
   ```
5. Edit `plugin-skill/src/main/java/io/bitken/shipsmooth/resources/ResourceBuilder.java`
   to add a third render block (mirrors the TLA block at lines 41–58). Derive
   skill name: `start` → `experimental-start-parallel`,
   `start-dev` → `experimental-start-parallel-dev`. Frontmatter pattern
   identical to TLA's derivation with description rewritten to mention the
   parallel-execution variant.
6. Update
   `plugin-skill/src/test/java/io/bitken/shipsmooth/resources/ResourceBuilderIntegrationTest.java`
   to assert:
   - `start-dev/SKILL.md` no longer contains the string
     `## Parallel Execution Protocol`.
   - `experimental-start-parallel-dev/SKILL.md` contains both
     `## Core Invariants` (base) and `## Parallel Execution Protocol`
     (parallel).
   - `experimental-start-tla-dev/SKILL.md` still renders and contains
     `## Core Invariants` (unchanged).

Run `mvn -P dev clean install` — all green, all three skills appear under
`build/skills/`.

### Task 4: Verify gemini-dev and prod profiles still build [Low]

*Depends-on: 3*

Run, in this order, with `mvn clean install` between each:
- `mvn -P dev clean install` → `build/skills/{start-dev, experimental-start-parallel-dev, experimental-start-tla-dev}`
- `mvn -P gemini-dev clean install` → `build-gemini-dev/skills/{start-dev, experimental-start-parallel-dev, experimental-start-tla-dev}`
- `mvn -P prod clean install` → `build/skills/{start, experimental-start-parallel, experimental-start-tla}`
- `mvn -P gemini clean install` → `build-gemini/skills/{start, experimental-start-parallel, experimental-start-tla}`

Spot-check one rendered file per profile: confirm `${model.cliBin()}` is
correctly substituted and the `--enable-experimental` flag appears on every
experimental command invocation in the parallel skill.

---

## 4. Verification (end-to-end)

```bash
# 1. Java gate
mvn -pl plugin-tasks-java test
# Then with a freshly jlinked runtime:
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks --help
  # → exit 0, no experimental commands in list
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks integrate --help
  # → exit 2, "Unknown command: integrate" on stderr
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks --enable-experimental --help
  # → exit 0, all 10 experimental commands listed
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks --enable-experimental integrate --help
  # → exit 0, integrate usage

# 2. SKILL rendering — all four profiles
for P in dev gemini-dev prod gemini; do
  mvn -P $P clean install -DskipTests
done
# Confirm three skills per profile, content correctness:
grep -L "Parallel Execution Protocol" build/skills/start-dev/SKILL.md           # found = good
grep -l "Parallel Execution Protocol" build/skills/experimental-start-parallel-dev/SKILL.md  # found = good
grep -l "Core Invariants" build/skills/experimental-start-tla-dev/SKILL.md                   # found = good
grep -l "\-\-enable-experimental integrate" build/skills/experimental-start-parallel-dev/SKILL.md  # found = good

# 3. All tests
mvn clean test
```
