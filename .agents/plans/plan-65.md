# Plan 65 — split base-workflow.jte into per-section fragments

## Context

Backlog issue: PB-354 — start-dev SKILL source is a 370-line monolith; split it into
browsable per-section fragments.

The `start-dev` skill's generated `build/skills/start-dev/SKILL.md` (369 lines) is JTE output
— never hand-edited. Its real source is `_partials/base-workflow.jte.md` (370 lines), a single
file holding every `##` section of the workflow. That monolith is hard to browse and review.

The `experimental-refine-dev` skill already demonstrates the target pattern: its
`refine/SKILL.jte.md` keeps intro prose inline, then ends with a flat list of
`@template...rules.<name>(model = model)` includes — one fragment file per rule under
`refine/rules/`. We mirror that for the workflow.

## Approach (Option A — keep base-workflow as the shared TOC)

`base-workflow.jte.md` has **two** includers — `start/SKILL.jte.md` and
`experimental/start-parallel/SKILL.jte.md` — so it stays as the single shared body both skills
depend on. We do **not** delete it and we do **not** move the TOC up into `start/SKILL.jte.md`
(that would force start-parallel to duplicate the include list).

Instead:
1. Extract each `##` section of `base-workflow.jte.md` into its own fragment file under
   `_partials/workflow/`, following the `refine/rules/*.jte.md` shape
   (`@import PluginModel` / `@param PluginModel model` / section body).
2. Shrink `base-workflow.jte.md` to a ~12-line ordered list of
   `@template.skills._partials.workflow.<name>(model = model)` includes — the refine-style TOC.

### Self-containment rule for separators

Today the `---` horizontal rules sit *between* sections inside the monolith. To keep the TOC a
pure include list, each fragment owns its **trailing** `---` (heading → body → `---`). The TOC
then needs no separators of its own, and the flattened output keeps the same `---` placement.

### Phase 2 fragment caveat

The Phase 2 section embeds nested per-agent includes
(`@if(model.isGemini()) @template.skills.start.gemini.set-commit-hardening ... @else
@template.skills.start.claude.set-commit-hardening ... @endif`, likewise for
`set-commit-low-risk`). The `phase2-execute` fragment must preserve those verbatim and keep
receiving `model` — they are why every fragment takes `@param PluginModel model` even when its
body has no other interpolation.

## Section → fragment mapping

Source line ranges in `_partials/base-workflow.jte.md` (heading line → its trailing `---`):

| Fragment (`_partials/workflow/`)        | Section                              | Lines    |
|------------------------------------------|--------------------------------------|----------|
| `when-to-apply.jte.md`                   | When to apply this skill             | 4–11     |
| `core-invariants.jte.md`                 | Core Invariants                      | 13–22    |
| `task-tracking-mode.jte.md`              | Task Tracking Mode                   | 24–35    |
| `control-strategy.jte.md`                | Control Strategy: Risk-Quality Loop  | 37–50    |
| `repo-structure.jte.md`                  | Repository Structure                 | 52–63    |
| `git-tagging.jte.md`                     | Git Tagging Convention + lefthook    | 65–119   |
| `linear-structure.jte.md`               | Linear Structure                     | 121–151  |
| `phase1-plan.jte.md`                     | Phase 1 — Plan, Calibrate, Commit    | 153–189  |
| `phase2-execute.jte.md`                  | Phase 2 — Execute (holds sub-includes)| 191–313  |
| `plan-closeout.jte.md`                   | Plan Closeout                        | 315–340  |
| `audit-trail.jte.md`                     | Audit Trail                          | 342–357  |
| `what-lives-where.jte.md`                | What Lives Where — Quick Reference   | 359–370  |

## Verification (the safety invariant)

This is a pure source refactor: the generated `SKILL.md` must be **byte-identical** before and
after. No automated test for now — verification is a manual before/after `diff`. Before
touching any source, snapshot the current generated outputs
(`build/skills/start-dev/SKILL.md` and `build/skills/start-parallel/SKILL.md`, which share the
body) to `.agents/tmp/`. After the split, `mvn compile` regenerates `build/skills/`; `diff`
against the snapshots must be empty for both skills.

## Open questions

- Fragment naming: kebab-case matching section intent (above) vs numbered prefixes
  (`01-when-to-apply`). Leaning kebab-case to match `refine/rules/` (which is unnumbered).
- Whether to also split the two `start-parallel`-only extra sections — out of scope here;
  this plan only fragments the shared `base-workflow` body.

## Tasks

### Task 1: Snapshot current output and extract section fragments [Medium]

Snapshot the current generated `build/skills/start-dev/SKILL.md` and
`build/skills/start-parallel/SKILL.md` to `.agents/tmp/` (the before-images for verification).
Then create one fragment file per row of the mapping table under
`_partials/jte-src/.../_partials/workflow/`, each with the
`@import PluginModel` / `@param PluginModel model` header + the section body + its trailing
`---`. The `phase2-execute.jte.md` fragment preserves the nested `model.isGemini()`
`set-commit-*` includes verbatim. Do not yet change `base-workflow.jte.md`.

### Task 2: Reduce `base-workflow.jte.md` to a TOC of includes [Low]

*Depends-on: 1*

Replace the section bodies in `base-workflow.jte.md` with the ordered list of
`@template.skills._partials.workflow.<name>(model = model)` includes (refine-style), keeping the
`@import`/`@param` header. Run `mvn compile` to regenerate `build/skills/`, then `diff` the new
`start-dev/SKILL.md` and `start-parallel/SKILL.md` against the `.agents/tmp/` snapshots — both
diffs must be empty.
