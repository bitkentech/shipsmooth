# Plan 63: Refine Shipsmooth.java — extract CommandTree, drop redundant ExperimentalMode param

## Context

Backlog reference: `local: refine-codebase-cli` — apply the `experimental-refine-dev`
skill's clean-slate re-derivation to the `cli/` package, starting with the CLI entry point.

This plan applies the refine skill to `app/src/main/java/io/bitken/ss/cli/Shipsmooth.java`
(133 lines). The PHASE 1 architectural extraction (design of record) lives at
`.agents/tmp/refine-Shipsmooth.md`; the generated artifacts at
`.agents/tmp/refine-Shipsmooth.java`, `.agents/tmp/refine-Shipsmooth-CommandTree.java`, and
`.agents/tmp/refine-Shipsmooth-test-diffs.md`. The scratchpad is throwaway; this plan and the
code are the durable record.

### What the refine extraction found

1. **Fused responsibilities.** `Shipsmooth` does two unrelated jobs: (a) assemble the picocli
   command tree (root spec, help/version/`--enable-experimental` mixin, build the 18
   subcommands, gate the experimental ones, attach specs — ~70 lines), and (b) run it
   (`execute()` / `main()`). SRP says (a) belongs in its own collaborator.

2. **Duplicated source of truth.** Every caller threads `ExperimentalMode` through two doors:
   into `ServicesModule` (so it lands in the Dagger graph) *and* as a separate `Shipsmooth`
   ctor argument. But `AppComponents` already exposes `experimentalMode()` — `app` already
   carries the mode. The third ctor param is redundant (Single-source-of-truth +
   primitive/param-count smell). The caller's authentic verb is just
   `new Shipsmooth(app, args).execute()`.

### Scope and non-goals

- **In scope:** extract a package-private `cli/CommandTree` that owns command-tree assembly;
  shrink `cli/Shipsmooth` to the runnable one-shot CLI reading mode from
  `app.experimentalMode()`; update the production `main()` call site and all ~6 test call
  sites; preserve the `integrateCommand()` test seam. Also (Task 2) two in-progress prose
  tweaks to the `experimental-refine-dev` skill's JTE templates that this refine session
  surfaced — see Task 2.
- **Out of scope:** behavioural changes to any subcommand; changes to `ExperimentalMode`,
  `ServicesModule`, or `AppComponents` (the `experimentalMode()` accessor already exists);
  the other `cli/` classes (future refine passes).

## Design decisions

- **`CommandTree` (package-private, `cli/CommandTree.java`)** — one responsibility: "build the
  picocli root command, registering each subcommand only when non-experimental or
  experimental mode is enabled." Ctor `CommandTree(AppComponents app)` reads
  `app.experimentalMode()` once, builds the root spec + mixin, builds the 18 commands, gates +
  attaches them, constructs the `CommandLine`. Exposes `commandLine()` and `integrate()` (the
  retained `Integrate` instance for the test seam). All construction is in the ctor; helpers
  (`buildRootSpec`, `standardOptions`, `buildCommands`, `isExperimental`, `register`) are
  ctor-only `private static`.
- **`buildCommands` is `private static`** (user call): it derives the command array purely
  from its `app` parameter plus the `integrate` instance, so the ctor builds `integrate` first
  and passes it in — `buildCommands(app, integrate)`. No instance-state read inside it.
- **`Shipsmooth` shrinks to ~45 lines** — holds `args`, the `CommandTree`, and its built
  `CommandLine`; `execute()`, `integrateCommand()` (delegates to `commandTree.integrate()`),
  `main()`. Reads mode from the graph; **drops the `ExperimentalMode mode` ctor param.**
- **Mode must now match the graph.** Because gating reads from `app.experimentalMode()` rather
  than a per-args ctor arg, each call site's graph mode must match the flag it passes. This
  splits the tests two ways (see `refine-Shipsmooth-test-diffs.md`):
  - *Build graph per `run()`* — `ShipsmoothTest`, `ShipsmoothIntegrationTest`: they assert
    both gated-off (exit 2 / hidden) and gated-on behaviour from one fixture, so a single
    fixed-mode graph cannot serve both. Graph construction moves into `run()`, seeded from
    `ExperimentalMode.fromArgs(args)`.
  - *Seed graph `experimental=true`* — `WorkerLifecycleIntegrationTest` (×2 packages),
    `WorkerDependencyIntegrationTest`, `IntegrateTest`, `LedgerRecordPatchIntegratedTest`:
    every `run(...)` already passes `--enable-experimental`, so their field-init graph is just
    seeded `new ExperimentalMode(true)`.
- **No test deleted.** The ctor-shape change (drop `mode`) is the refine skill's sanctioned
  "rewrite the tests to match" case; behaviour assertions are all preserved.

## Tasks

### Task 1: Extract CommandTree and shrink Shipsmooth; update all call sites [Medium]
*Depends-on:*

This is a single cohesive structural change — `CommandTree` and the slimmed `Shipsmooth` are
two halves of one extraction and cannot compile or pass tests independently, and every
`new Shipsmooth(...)` call site changes in lockstep with the dropped ctor param. Splitting it
would leave the tree red between commits.

Subtasks (all in one task commit):
- Add `cli/CommandTree.java` from `.agents/tmp/refine-Shipsmooth-CommandTree.java`
  (`buildCommands` is `private static`, taking `(AppComponents app, Integrate integrate)`).
- Replace `cli/Shipsmooth.java` with `.agents/tmp/refine-Shipsmooth.java` (2-arg ctor, reads
  `app.experimentalMode()`, delegates assembly to `CommandTree`).
- Update `main()` to seed `ServicesModule` with `ExperimentalMode.fromArgs(args)` and call
  `new Shipsmooth(app, args)`.
- Apply the seven test edits per `.agents/tmp/refine-Shipsmooth-test-diffs.md`:
  - per-`run()` graph: `ShipsmoothTest`, `ShipsmoothIntegrationTest`;
  - seed-true field graph: `WorkerLifecycleIntegrationTest` (io.bitken.ss + cli),
    `WorkerDependencyIntegrationTest`, `cli/IntegrateTest`, `cli/LedgerRecordPatchIntegratedTest`.
- Verify behaviour: `ShipsmoothTest` (gating, help, flag-position, build-visibility) and the
  integration tests pass; `IntegrateTest` resolver seam (`integrateCommand().setResolverFactory`)
  still works.

Medium risk: touches the CLI entry point and seven test files; the gating/mode-source change
is subtle (graph mode must match the flag), and a wrong seeding silently breaks experimental
gating rather than failing to compile. Mitigated by the per-file resolution already worked out
in the diffs scratchpad and the existing `ShipsmoothTest` gating assertions.

### Task 2: Refine-skill JTE tweaks surfaced by this session [Low]
*Depends-on:*

*(Added in v2.)*

**Why (added 2026-06-02):** While applying the refine skill to `Shipsmooth.java`, two prose
improvements to the skill's own JTE templates were in progress in the working tree. The user
is experimenting with the skill and wants them tracked alongside this refine session rather
than left as stray edits. Independent of Task 1's code change.

Edits to the `experimental-refine-dev` skill templates under
`integrations/common/src/main/jte-src/skills/experimental/refine/`:
- `SKILL.jte.md` — reword the **Caller's-eye view** step to frame it as "identify what the
  calling code is *authentically trying to achieve* semantically," explicitly calling out
  anemic loose-primitive / successive-procedural-call sites and asking for the *ideal unified
  verb the caller should be using*, with the one-line ideal call site written last.
- `rules/class-structure.jte.md` — add "Avoid calling class's own non-static methods in
  constructor" to the rule text, and extend the Good/Bad exemplars with a fourth collaborator
  (`obj4 = createObj4(obj3)`) showing a `private static` ctor helper (Good) vs an instance
  method reading a possibly-uninitialized field (Bad), plus a closing note on the Bad
  `createObj4` ordering hazard.

Low risk: JTE prose/exemplar edits only; correctness proven by render (`mvn compile`
regenerates `build/skills/experimental-refine-dev/SKILL.md`). No behavioural code change. The
existing `TargetIntegrationTest` render assertions (plan-62 Task 6) guard against assembly
regressions.

## Open questions

- None outstanding. Scope confirmed with the user (full re-derivation, `buildCommands` static,
  skill tweaks as Task 2) on 2026-06-02.
