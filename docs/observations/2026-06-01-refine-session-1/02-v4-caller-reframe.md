# Refine Session 1 — v4 re-run: Target.java

**Date:** 2026-06-01
**File:** `integrations/common/src/main/java/io/bitken/ss/resources/Target.java`
**Skill:** `experimental-refine-dev` (plan-62 **v4** — adds the "Caller's-eye view" Phase-1
step + SRP caller-naming tweak; Task 7)

This is a re-run of [`01-v3-baseline.md`](01-v3-baseline.md) on the **same original
`Target.java`** (commit `8aac5c5`, run from an isolated worktree at `~/tmp/shipsmooth`), with
the v4 skill installed. It is the before/after control for the v4 change.

---

## Why this re-run exists

`01-v3-baseline.md` recorded the *first* validation of the restructured skill. The v3 skill
fixed **structural** anchoring (one-shot Phase-1 extraction, surfaced the validation-bypass
trade-off, resisted caving under user override) but a residual anchor survived: the model
took `Target`-the-`(Platform, Os, Env)`-triple as the central value object to *protect* and
asked only "how should `Target` be shaped" — never "what object/verb does the *caller* want."
The user had to supply the reframe by hand: `new BuildTarget(settings).build()`, with the
triple demoted to an internal collaborator.

That manual reframe was the §III.4 autoregressive anchor surviving in a subtler form — the
model escaped the code's *structure* but not its *naming/ownership decisions*. v4 adds a
"Caller's-eye view (do this first, before reading for structure)" step at the top of Phase 1
to force the reframe up front, plus an SRP-rule tweak asking whether the class name is the
one a *caller* would reach for.

## Result: the reframe is now self-generated

The v4 Phase-1 scratchpad **opens** with the caller's-eye section and reaches the
triple-is-an-internal-detail conclusion **unprompted**, before any user turn:

> Two distinct callers want two different objects:
> - The build (`pom.xml` `<mainClass>`) wants `new PluginBuild(properties).run()` — "build
>   the plugin resources." It does **not** care about a platform/os/env value triple at all
>   — that is an internal detail of how the build decides what to render.
> - The tests/model want a `Target` value to interrogate (`.platform()`, `.cliBin(...)`,
>   `.buildPluginModel(...)`). They never call `build()`.

This is the structurally identical conclusion the user had to hand the v3 run. The model
named the orchestrator `PluginBuild` (vs. the user's `BuildTarget`) — arguably better, since
it names the verb (build the plugin) rather than re-decorating the noun.

### Proposed structure (v4, unprompted)

```
resources/
  Target.java          // record(Platform,Os,Env) + from() + buildPluginModel/cliBin/
                       //   skillFragmentDir/launcherFileName.  NO main/build.
  PluginBuild.java     // NEW. public main(); ctor builds Target + PluginModel +
                       //   ObjectMapper + renderers from BuildProperties; run() drives them.
  BuildProperties.java // NEW (package-private). Wraps the ~13 System.getProperty lookups;
                       //   one place per property key (kills primitive-obsession smell).
```

Dependency direction: `PluginBuild` → `BuildProperties`, `Target`, `PluginModel`, renderers.
`Target` no longer depends on the renderers or Jackson — a strict SRP improvement.

## Before / after

| Dimension | v3 baseline (01) | v4 re-run (02) |
|---|---|---|
| Reframe to "caller's object, not the triple" | **User supplied it** (`BuildTarget`) | **Model self-generated** (`PluginBuild`) |
| Phase-1 opening | Requirements from existing class | Caller's-eye view first |
| `static from()` vs instance | Oscillated (the v3 *original* session took 7 steps) | Resolved in one pass, with stated rationale |
| `BuildProperties` (primitive obsession) | — | Proposed unprompted |
| Package boundary | Correctly declined to invent packages | Same — declined again |
| Blast radius identified | `pom.xml` mainClass + integration test | `pom.xml` mainClass only; no test changes (calls land on preserved `Target` signatures) |

## What v4 still does (correctly, not a regression)

It ends on one open question for the user — the `pom.xml` `<mainClass>` →
`io.bitken.ss.resources.PluginBuild` edit, since that is a real cross-file change. Surfacing
it before generating is the intended two-phase discipline, not hesitation.

## Verdict

Task 7's behavioral claim is **confirmed**: the caller's-eye Phase-1 step defeats the
ownership/naming anchor that survived v3. The full Phase-1 scratchpad from this run is at
`~/tmp/shipsmooth/.agents/tmp/refine-Target.md` (ephemeral; not committed).
