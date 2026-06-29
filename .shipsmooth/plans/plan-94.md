# plan-94 — remove all Linear references (collapse dual-mode → single local workflow)

## Context

Backlog (local): **maintenance — remove legacy Linear feature.** Tracked in this plan; no
external backlog issue (pure removal of a half-baked, unmaintained feature). Remove all
references to Linear from the codebase. Linear task-tracking was a half-baked, legacy feature: the
workflow ships a two-mode design (`[Linear]` vs `[Local]`) where only the Local mode is
real and maintained. The `[Linear]` paths add ongoing noise to the generated SKILL.md,
the build descriptions advertise "Linear integration", and the docs claim it is
"available". This plan deletes Linear entirely and collapses the two modes into a single,
unmarked workflow where **Local is the workflow**.

**Design decision (confirmed with human):** drop the modes *entirely*. Delete the
`[Linear]`/`[Local]` distinction and the "Task Tracking Mode" section; convert every
`[Local]`-marked instruction to plain unmarked prose; delete `linear-structure.jte.md`
and its include. This is the cleanest result and the larger rewrite — chosen over leaving
vestigial `[Local]` markers, which would read oddly in a single-mode workflow.

### Scope inventory (what actually mentions Linear)

The SKILL.md shipped to each host is generated from JTE templates under
`skills/shared/workflow/`. The bulk of the work is there.

- **Workflow templates (core):** `task-tracking-mode.jte.md`, `linear-structure.jte.md`
  (delete whole file), `when-to-apply.jte.md`, `core-invariants.jte.md`,
  `audit-trail.jte.md`, `what-lives-where.jte.md`, `phase1-plan.jte.md`,
  `phase2-execute.jte.md`, `plan-closeout.jte.md`. 19 `[Linear]` markers + 26 `[Local]`
  markers to resolve. `base-workflow.jte.md` includes `linear-structure` — remove the
  include.
- **Build descriptions:** `harness/claude/build.gradle.kts` and
  `harness/shared/build.gradle.kts` — `prodDescription` says
  "…vertical slices, Linear integration, and immutable git-based…".
- **User docs:** `README.md` and
  `harness/gemini/src/main/resources/gemini-extension/README.md` — both say
  "(but [Linear](https://linear.app) integration is currently available)".
- **Code comment:** `cli/.../plan/Branch.java:14` Javadoc — "In Linear mode pass
  `--issue`…". The `--issue` flag itself is not Linear-specific and stays; only the
  wording changes.

### Out of scope (deliberately)

- **`.shipsmooth/plans/` history** — past plan files mentioning Linear are an immutable
  audit trail; not touched.
- **`skills/experimental/refine/rules/method-structure.jte.md`** — "mostly linear flow of
  control" is unrelated English, not the product. False positive; left alone.
- **MCP/Linear server availability** in the harness environment — that's external tooling,
  not our code.

### Verification anchor

`harness/shared/.../TargetIntegrationTest.java` already renders SKILL.md end-to-end. The
preamble integration test adds an assertion there that the rendered skill contains no
"Linear" — this is the red→green driver for the whole template rewrite. No existing test
asserts Linear content, so removal carries low regression risk.

## Tasks

### Task 1: Remove Linear from the dual-mode template core [High]
*Depends-on:*

The high-risk, high-blast task: prove the dual-mode collapse renders cleanly. Delete
`linear-structure.jte.md` and remove its include from `base-workflow.jte.md`; delete the
"Task Tracking Mode" section in `task-tracking-mode.jte.md` and rewrite the file (or remove
it from the include chain) so the workflow reads as single-mode. Resolve every `[Linear]`
and `[Local]` marker in `core-invariants.jte.md`, `when-to-apply.jte.md`,
`what-lives-where.jte.md`, `audit-trail.jte.md` to unmarked prose. Risk is High because
JTE include/parse breakage or an orphaned `@template` reference fails the whole skill
render — this validates the approach before touching the remaining templates.

### Task 2: Resolve Linear markers in the phase/closeout templates [Medium]
*Depends-on: 1*

Mechanical follow-through once the collapse approach is proven: convert the `[Linear]`
(delete) and `[Local]` (unmark) markers in `phase1-plan.jte.md`, `phase2-execute.jte.md`,
and `plan-closeout.jte.md`. These hold the densest run of markers (Phase 2 alone has 7
`[Linear]` + 5 `[Local]`). Medium because the prose must still read coherently as a single
path — e.g. lines that branched "`[Linear]` do X · `[Local]` do Y" must become just the Y.

### Task 3: Scrub build descriptions and user-facing docs [Low]
*Depends-on:*

Drop "Linear integration, " from the `prodDescription` string in
`harness/claude/build.gradle.kts` and `harness/shared/build.gradle.kts`. Remove the
"(but [Linear](…) integration is currently available)" clause from `README.md` and the
gemini `gemini-extension/README.md`, leaving the "No external services required." claim
clean and accurate. Low risk: plain string edits, no logic. Independent of the template
work, but ordered after it so the docs match the shipped skill.

### Task 4: Fix the Branch.java Javadoc wording [Low]
*Depends-on:*

Reword the `Branch.java:14` Javadoc that says "In Linear mode pass `--issue`" so it no
longer references Linear — `--issue` is a generic issue/branch identifier. Comment-only
change; the flag and behaviour are untouched. Low risk.
