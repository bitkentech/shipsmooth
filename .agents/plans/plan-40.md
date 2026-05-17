# Plan 40 — Gate parallel-execution features behind `--enable-experimental`

**Status:** open
**Version:** v3 (scope reduced — picocli gate moved to plan-41)
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

These features are still experimental. This plan does the **template /
skill-restructuring half** of that work; the **Java CLI gate** is deferred to
plan-41.

Scope of this plan (v3):

1. Move the Parallel Execution Protocol documentation out of `start-dev`
   SKILL.md into a new skill `experimental-start-parallel-dev` (and
   `experimental-start-parallel` in prod profiles) so the agent only sees
   parallel-execution instructions when that skill is explicitly engaged.
2. The TLA variant (`experimental-start-tla(-dev)`) remains sequential and
   unchanged — it stays an experimental skill but does not gain any parallel
   content.
3. The base `start-dev` skill contains **zero references** to experimental
   features (no pointers, no fallback notes). An agent on the base skill
   that encounters an integration worktree from a prior session will fail
   to find recovery instructions — that is the intended behaviour.

Refactoring SKILL.md sources to share content uses **JTE `@template` includes**
so the common base workflow lives in one partial and both `start[-dev]` and
`experimental-start-parallel[-dev]` render from it.

**Deferred to plan-41:** the `--enable-experimental` top-level flag on
`shipsmooth-tasks`, the picocli `IExecutionStrategy` that hides and refuses
experimental subcommands, and the corresponding rewrite of CLI invocations
in `parallel-execution.jte.md` to prefix every command with the flag. Until
plan-41 lands, the experimental commands remain runnable; they're just no
longer documented in `start-dev`.

---

## 2. Design notes

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

### Task 2: Extract parallel section into partial; create experimental-start-parallel skill [High]

*Depends-on: 1*

1. Move lines `## Parallel Execution Protocol (optional)` through end of
   `### Worker Instruction Block` and its trailing horizontal rule out of
   `_partials/base-workflow.jte.md` into a new
   `_partials/parallel-execution.jte.md` (with `@param PluginModel model`).
2. Move the Phase 2 session-resume "integrate recovery" block (the bulleted
   list under "**If an `integration/plan-{N}` worktree is found...**") from
   `base-workflow.jte.md` into the top of `parallel-execution.jte.md` under a
   new `### Session-resume recovery` heading.
3. Create `skills/experimental/start-parallel/SKILL.jte.md`:
   ```
   @import io.bitken.shipsmooth.resources.PluginModel
   @param PluginModel model
   @if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif

   # ${model.skillName()} — Agent Coding Workflow (Parallel Execution)

   @template.skills._partials.base-workflow(model = model)
   @template.skills._partials.parallel-execution(model = model)
   ```
4. Edit `plugin-skill/src/main/java/io/bitken/shipsmooth/resources/ResourceBuilder.java`
   to add a third render block (mirrors the TLA block at lines 41–58). Derive
   skill name: `start` → `experimental-start-parallel`,
   `start-dev` → `experimental-start-parallel-dev`. Frontmatter pattern
   identical to TLA's derivation with description rewritten to mention the
   parallel-execution variant.
5. Update
   `plugin-skill/src/test/java/io/bitken/shipsmooth/resources/ResourceBuilderIntegrationTest.java`
   to assert:
   - `start-dev/SKILL.md` no longer contains the string
     `## Parallel Execution Protocol`.
   - `experimental-start-parallel-dev/SKILL.md` contains both
     `## Core Invariants` (base) and `## Parallel Execution Protocol`
     (parallel).
   - `experimental-start-tla-dev/SKILL.md` still renders and contains
     `## Core Invariants` (unchanged).

Note: CLI invocations in `parallel-execution.jte.md` do **not** include
`--enable-experimental`. That's added in plan-41 when the picocli gate is
introduced. Until plan-41 lands, the experimental commands remain runnable
without any flag; they're just not documented in `start-dev`.

Run `mvn -P dev -pl plugin-skill compile` — all green, all three skills
appear under `build/skills/`.

### Task 3: Verify gemini-dev and prod profiles still build [Low]

*Depends-on: 2*

Run, in this order, with `mvn -P <profile> -pl plugin-skill compile` (skill
verification only; `package` is required only for the jlink image):
- `mvn -P dev -pl plugin-skill compile` → `build/skills/{start-dev, experimental-start-parallel-dev, experimental-start-tla-dev}`
- `mvn -P gemini-dev -pl plugin-skill compile` → `build-gemini-dev/skills/{start-dev, experimental-start-parallel-dev, experimental-start-tla-dev}`
- `mvn -P prod -pl plugin-skill compile` → `build/skills/{start, experimental-start-parallel, experimental-start-tla}`
- `mvn -P gemini -pl plugin-skill compile` → `build-gemini/skills/{start, experimental-start-parallel, experimental-start-tla}`

Spot-check one rendered file per profile: confirm `${model.cliBin()}` is
correctly substituted in the parallel skill.

---

## 4. Verification (end-to-end)

```bash
# 1. SKILL rendering — all four profiles
for P in dev gemini-dev prod gemini; do
  mvn -P $P -pl plugin-skill compile -DskipTests
done

# 2. Content correctness:
grep -c "Parallel Execution Protocol" build/skills/start-dev/SKILL.md                            # → 0
grep -c "Parallel Execution Protocol" build/skills/experimental-start-parallel-dev/SKILL.md      # → 1
grep -c "Core Invariants"             build/skills/experimental-start-parallel-dev/SKILL.md      # → 1
grep -c "Core Invariants"             build/skills/experimental-start-tla-dev/SKILL.md           # → 1

# 3. plugin-skill tests
mvn -pl plugin-skill test
```

The picocli `--enable-experimental` flag is **not** part of plan-40's
verification. See plan-41 for that.
