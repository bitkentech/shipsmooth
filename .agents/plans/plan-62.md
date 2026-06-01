# Plan 62: Restructure the experimental-refine-dev skill to force clean-slate re-derivation

## Context

Backlog reference: `local: refine-skill-forcing-function` — make the refine skill an
active forcing function instead of a passive style guide.

The `experimental-refine-dev` skill is assembled from JTE templates:

- top-level `integrations/common/src/main/jte-src/skills/experimental/refine/SKILL.jte.md`
- one rule fragment per file under `.../refine/rules/*.jte.md`, each pulled in by a
  `@template.skills.experimental.refine.rules.<name>(model = model)` line in the
  top-level template.

The rendered artifact lands at `build/skills/experimental-refine-dev/SKILL.md` via
`SkillRenderer.renderExperimental()` (`SkillRenderer.java:46`).

### What went wrong (evidence)

`docs/observations/2026-06-01-refine-session-1.md` records a refine session on
`Target.java` that reached good final code but took **11 user interventions** to get
there. The LLM hill-climbed through small, anchored edits — the exact opposite of the
skill's headline instruction ("be very ambitious… clean-slate re-derivation"). Two
independent analyses (this session's, and a second AI's, both grounded in
`docs/references/code-quality-1.md`) converged on the same root causes:

1. **The headline "clean-slate" instruction has no enforcing mechanism.** The old code
   stays in context as the dominant anchor, so autoregressive path dependence + the RLHF
   conservative-edit bias overpower the prose instruction. (code-quality §III.4)
2. **No priority ordering among ~16 co-equal rules.** When "static methods are rare"
   collides with "all `new` in the constructor," the posterior never sharpens — the model
   oscillated (transcript steps 4–11). (code-quality §latent-variable view)
3. **Mechanical, linter-checkable rules dilute the attention budget** (method length, file
   length, nesting depth, if-block length). The model cannot self-certify them and they
   pulled attention from the one rule that mattered, which surfaced only at step 7.
   (code-quality §III.1) — *Mitigation here is partial:* these rules are demoted to lowest
   priority but retained, because no deterministic linter exists yet to replace them. Full
   removal is deferred to a follow-up plan once a linter lands.
4. **Good/Bad exemplars rely on the model to infer *why*** — risking "form without
   substance" copying of surface syntax. (code-quality §III.7, §Form Without Substance)
5. **Primitive-obsession rule is anchored** — the model won't invent a new domain type
   if none exists in context unless explicitly authorized. (code-quality §III.4)

### Scope and non-goals

- **In scope:** edits to the JTE templates only (top-level + rule fragments), plus an
  integration-test assertion proving the restructured skill still renders.
- **Out of scope / deferred (user instruction, 2026-06-01):** wiring a *deterministic
  verifier* (Checkstyle/PMD/linter) into a generate-verify-repair loop. No mechanical
  linter exists yet, so the mechanical rules (method-length, file-length, if-nesting,
  if-block-length) **stay in the skill** for now — removing them before a linter exists
  would lose the coverage entirely. They are reordered to lowest priority but kept. When a
  deterministic check lands, a follow-up plan removes them. This plan does **not** add
  that check or remove those rules.

## Goals

Turn the skill from a passive rule list into an active forcing function by:

1. Replacing the abstract "clean-slate re-derivation" instruction with a mandatory
   two-phase output contract (Phase 1: architectural extraction in natural language,
   split by provenance into *requirements from production code* vs *requirements from
   tests* so the model is explicit about where each requirement came from and test
   structure cannot anchor the design; Phase 2: clean-slate generation from the Phase-1
   production requirements only).
2. Adding an explicit rule-priority ordering and reordering the `@template` includes so
   the highest-priority rules (rich domain, class structure) render first and the
   mechanical/linter-checkable rules render last.
3. Adding a short "Why this matters" / mechanism block to the high-value contrastive
   exemplars (rich-domain, class-structure, srp, single-source, constructor-di).
4. Authorizing creation of new domain types in the primitive-obsession rule.

The mechanical rules (method-length, file-length, if-nesting, if-block-length) are **kept**
— see Scope/non-goals. They are demoted to lowest priority but not removed, because no
deterministic linter exists yet to take over their job.

## Design decisions

- **Edit fragments in place; reorder includes in the top-level template.** The priority
  ordering is expressed structurally (include order) *and* explicitly (a new priority
  block near the Execution Contract), so the two reinforce each other.
- **Keep the four mechanical rule fragment files and their includes**, but move their
  `@template` lines to the bottom of `SKILL.jte.md` so they render after the
  judgment-level rules. The priority block (below) explicitly ranks them lowest. This
  preserves the coverage until a deterministic linter can take over, while still relieving
  the attention-budget pressure by demoting them out of the high-priority region.
- **Keep all Good/Bad exemplars** — they are the skill's strongest asset.
- **Verification:** the existing `TargetIntegrationTest` renders skills but asserts
  nothing about refine content. Add one assertion that the rendered
  `experimental-refine-dev/SKILL.md` contains the new Phase-1/Phase-2 contract heading
  (including the two provenance subsection markers). This proves the JTE assembly still
  works after reordering and catches accidental regressions. (No "must-not-contain" check
  for mechanical headings, since those rules are retained.)

## Tasks

### Task 1: Add two-phase execution contract + rule-priority block to the top-level template [Medium]
*Depends-on:*

Rewrite the Execution Contract in `SKILL.jte.md` to mandate the two-phase output format.

**Phase 1 — Architectural Extraction (natural language only).** The extraction is split
by *provenance* into two labelled subsections so the model is explicit about where each
requirement came from:

- *Requirements from production code* — inputs, state mutations, outputs, downstream
  collaborators/renderers, and invariants enforced by the code itself. The proposed new
  class shape (which params move to the constructor, dependency direction) is derived from
  **this** section.
- *Requirements from tests* — invariants the tests assert (guards, defaults, error cases).
  A note states these constrain **behaviour only**; test structure is **not** a template
  for the class's constructor/method shape. If the re-derived structure changes the
  surface tests touch, the tests are rewritten to match — and no test is deleted without
  surfacing it to the user.

The provenance split is the anti-anchoring mechanism: separating "came from production"
from "came from a test" forces the model to notice when test structure is leaking into the
design (code-quality §III.4). It de-anchors without blinding the model to the invariants
tests encode.

**Phase 2 — Clean-Slate Generation.** Derive the ideal production structure from the
*production* requirements subsection alone; treat the *tests* subsection as a behaviour
checklist to preserve. Generate from the Phase-1 proposal only — do not preserve a
production method or constructor solely because a test calls it. Include an explicit "if
you find yourself making a chain of small edits, STOP and re-derive" clause.

Also add a Rule-Priority block listing the rules in precedence order — judgment-level
rules (rich domain, class structure, SRP, …) highest, mechanical rules (method length,
file/if-block length, nesting) explicitly lowest — with a tie-break instruction that
forbids introducing a `static` factory or half-initialized object to satisfy a
lower-priority rule.

Medium risk: this is the core behavioural change; wording must steer without bloating the
attention budget, and it interacts with the include reordering in Task 2.

### Task 2: Reorder rule includes into priority order (mechanical rules last) [Low]
*Depends-on: 1*

In `SKILL.jte.md`, reorder all `@template` include lines into priority order:
judgment-level rules first (rich-domain, class-structure, srp, single-source,
constructor-di, private-final-fields, avoid-primitives, static-rare, method-ordering,
method-structure, ternaries-booleans, package-structure), then the mechanical rules last
(method-length, if-block-length, if-nesting, file-length). **Keep all includes and all
rule fragment files** — nothing is removed or deleted; the mechanical rules are retained
because no deterministic linter exists yet (see Scope/non-goals).

Low risk: pure reorder; correctness is proven by render.

### Task 3: Add mechanism ("Why this matters") blocks to high-value exemplars [Low]
*Depends-on: 1*

Add a concise mechanism block after the Good/Bad pairs in `rules/rich-domain.jte.md`,
`rules/class-structure.jte.md`, `rules/srp.jte.md`, `rules/single-source.jte.md`, and
`rules/constructor-di.jte.md`, explaining *why* the Good form is better (the decision
boundary), per code-quality §III.7.

### Task 4: Authorize new domain types in the primitive-obsession rule [Low]
*Depends-on: 1*

Amend `rules/avoid-primitives.jte.md` to explicitly authorize and expect creation of a new
`private record` or package-private class when no suitable type exists, removing the
conservative-edit anchor.

### Task 5: Add render assertion to TargetIntegrationTest [Low]
*Depends-on: 1,2*

Add an assertion in `TargetIntegrationTest` that the rendered
`experimental-refine-dev/SKILL.md` contains the new two-phase contract markers — including
the two provenance subsection headings (*requirements from production code* /
*requirements from tests*). Proves the restructured JTE assembly renders and guards against
regressions. (No "must-not-contain" check for mechanical-rule headings, since those rules
are retained.)

## Open questions

- Exact precedence list in the priority block — proposed order above; confirm during
  review.
- ~~Whether to also add mechanism blocks to `single-source` and `constructor-di`~~ —
  resolved during calibration: yes, five exemplars total (rich-domain, class-structure,
  srp, single-source, constructor-di).
- ~~How to stop test code from anchoring the re-derived structure~~ — resolved: Phase 1
  extraction is split by provenance (production vs tests); structure derives from the
  production subsection, tests contribute behaviour-only invariants. Folded into Task 1.
