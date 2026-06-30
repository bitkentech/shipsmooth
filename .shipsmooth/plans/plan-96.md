# plan-96 — skill file cleanups

## Context

Feature: maintenance/quality — clean up the `start` skill file so its rendered
output is concise, internally consistent, and free of stale references, applying
the principles in `docs/references/code-quality-1.md` (attention budget,
deterministic-tool-owns-mechanical-rules, progressive disclosure). Local
backlog; no external issue.

Backlog: maintenance — skill-file quality. (Permanent backlog feature for skill
documentation upkeep; see also plan-95 which corrected the Lefthook reference.)

## Tasks

### Task 1: Abbreviate the CLI invocation in the start skill and reuse it throughout [Low]

The rendered `start` skill repeats the full CLI binary path
(`${XDG_CACHE_HOME:-~/.cache}/shipsmooth/<ver>/bin/shipsmooth`) ~28 times.
In the JTE source the path is already a single expression (`${model.cliBin()}`),
so source maintainability is fine — the cost is in the *rendered* output: 28
long invocations spend tokens and dilute attention away from the load-bearing
steering content (code-quality-1.md principle #1, attention budget).

Introduce a shell abbreviation that survives rendering: define the alias **once**
near the top of the rendered skill (e.g. a one-line `SS=…` definition derived
from `${model.cliBin()}`), then emit the short alias (`$SS plan tag …`) at every
call site. The version still lives in exactly one place; the rendered context
gets the concise form. Re-render all hosts and confirm the alias resolves to the
correct per-host/per-version path.

- Touches `skills/shared/workflow/*.jte.md` and `skills/start/SKILL.jte.md`
  (every `${model.cliBin()}` call site).
- Pure documentation/template change — no runtime behaviour change.

### Task 2: Fix the stale "This workflow" self-reference path [Low]

The "What Lives Where" table claims this workflow lives at
`~/.claude/skills/start/SKILL.md`, but the prod plugin actually loads it from
`~/.claude/plugins/cache/bitkentech/shipsmooth/<ver>/skills/start/SKILL.md`.
Same staleness class as the version path — a hard-coded location that does not
survive the plugin layout. The row is also marginal value (the model already
has the skill loaded), so consider whether it earns its place at all.

Either correct it to a layout-agnostic description (don't bake an absolute
plugin-cache path with a version into the rendered text) or drop the row.

- Touches `skills/shared/workflow/what-lives-where.jte.md:11`.
- Pure documentation change.

### Task 3: Restructure the skill for progressive disclosure [Medium]

*Depends-on: 1,2*

The rendered skill is ~450 lines read in a single pass at session start. Most
of it (Closeout, Audit Trail, tagging mechanics, first-run handshake) is
reference material consulted occasionally, yet it competes for attention with
the load-bearing steering content every session ("longer is not stronger";
code-quality-1.md attention-budget principle).

Split into a compact always-loaded core (when-to-apply, core invariants, the
control strategy, the phase skeleton, and the per-phase checklists) plus
progressively-disclosed reference files the agent opens only when it reaches
that phase (full closeout procedure, audit-trail format, first-run handshake
details, tagging reference). The `skills/shared/workflow/*.jte.md` partials
already map cleanly onto these sections, so the split is largely a question of
which partials stay inline vs. become on-demand references.

- Medium risk: changes the skill's load-time surface and the agent's
  navigation path; must preserve discoverability of the deferred content (the
  core must point to each reference file clearly enough that the agent reliably
  follows it at the right moment).
- Verify all hosts re-render and the deferred references resolve to real,
  loadable files in each host's layout.

### Task 4: Reconcile the task-status vocabulary the skill instructs against [Low]

The XSD (`core/src/main/resources/plan-tasks.xsd:88-94`) accepts task statuses
`pending, in-progress, de-risked, agent-coded, closed, needs-triage, abandoned`.
The skill drives a completed task to `agent-coded` and a loose end to
`needs-triage`, but **never** uses `closed` — even though `closed` exists in the
XSD and reads as the intended terminal state. The result is ambiguity about
which status marks a task genuinely done: the skill steers the model toward
`agent-coded` as the end state while the schema implies `closed` is the closure
token.

Decide the intended terminal status and make the skill consistent end-to-end
(every `task status --status <X>` step in `phase2-execute.jte.md` and
`plan-closeout.jte.md`), so the model is never steered toward a status that
contradicts the closeout semantics. If `closed` is the real terminal state,
the harden/low-risk completion steps should set it (or the closeout step
should), not leave tasks at `agent-coded`.

- Touches `skills/shared/workflow/phase2-execute.jte.md` and
  `skills/shared/workflow/plan-closeout.jte.md`.
- No XSD/CLI change expected — this aligns the *prose* to the existing enum.

### Task 5: Stop restating mechanically-checkable rules in the skill [Low]

The skill spends prose (and attention budget) restating rules a deterministic
tool already enforces — the 95% coverage threshold (repeated), the
`### Task N: Name [Risk]` heading format, the `*Depends-on: P[,Q]*` syntax,
"N is a positive integer — alphanumeric IDs not supported". Per code-quality-1.md
principle #1, the deterministic tool is the source of truth; restating its rules
dilutes attention and creates false confidence that the model self-enforces them.

Reduce each mechanical rule to a single pointer ("the CLI validates task
headings / dependency syntax; fix what it reports") and state the coverage
number once in a designated place, referenced abstractly elsewhere. Keep only
the *judgment* content (thin vertical slices, risk calibration) that no tool can
check.

- Touches `skills/shared/workflow/phase1-plan.jte.md` (heading/depends-on/
  coverage prose) and the per-task coverage steps in `phase2-execute.jte.md`.
- Do not remove anything the CLI does *not* actually validate — verify each rule
  has a real deterministic gate before demoting it to a pointer.

### Task 6: Populate the skill's YAML frontmatter [Low]

The source template already conditionally emits frontmatter
(`${model.skillFrontmatter()}` in `skills/start/SKILL.jte.md`), but it renders
empty — the prod skill opens with blank lines and no YAML `name`/`description`
block. Populate `skillFrontmatter()` so the rendered skill carries proper
frontmatter and drop the leading blank lines.

Note: this skill is invoked explicitly via `/shipsmooth:start`, not
auto-discovered, so the frontmatter is for correctness/convention and a clean
file head, not to enable automatic invocation.

- Touches the `skillFrontmatter()` provider (`PluginModel` / its resources) and
  the top of `skills/start/SKILL.jte.md`.
- Verify the rendered head is well-formed YAML with no stray leading blank lines
  across all hosts.

### Task 7: Reconcile the hard-coded `.shipsmooth/plans/` path with separate-dir mode [Low]

The skill contradicts itself about where plan files live. Phase 0 and Phase 2
correctly state that **separate-dir is the default** — plans live in a separate
state directory, *not* `.shipsmooth/plans/` in the repo — and instruct the agent
to read the real `plansDir` from `$SS store info --json`. But ~8 other spots
(Repository Structure, the What Lives Where table, Task Tracking, and several
Phase 1 steps) hard-code `.shipsmooth/plans/...` as if same-repo were the only
mode. The result is a skill that tells the agent "don't assume `.shipsmooth/plans/`"
in two places while assuming exactly that in eight others — the same stale/
contradictory-reference class as Task 2.

Replace the literal `.shipsmooth/plans/` in those spots with a neutral
`<plansDir>` placeholder (the directory `store info --json` reports), and state
once — where the structure is first introduced — that same-repo storage resolves
`<plansDir>` to the in-repo `.shipsmooth/plans/`. This makes the skill
mode-agnostic and consistent with the CLI-is-source-of-truth principle already in
Phase 2.

- Touches `skills/shared/workflow/repo-structure.jte.md`,
  `what-lives-where.jte.md`, `task-tracking-mode.jte.md`, and the
  `.shipsmooth/plans/...` references in `phase1-plan.jte.md`.
- Leave genuinely mode-correct mentions intact (the Phase 0/2 prose that already
  explains the separate-dir default and the `store info` lookup).
- Pure documentation change; re-render all hosts to confirm consistency.
