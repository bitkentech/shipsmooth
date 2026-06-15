# plan-81 — Fast-start the `start` skill when kickoff context is thin

## Context

**Backlog feature:** The `start` skill's Phase 1 ("Plan, Calibrate, & Commit")
assumes the agent already has enough context to draft a real plan, estimate
per-task risk, and stand up task-tracking infrastructure. But the most common
real kickoff is a *one-line* message — "start a new plan-81, feature is X" —
where the user is deliberately providing little context because he intends to
add detail later or work exploratorily/iteratively. The current skill responds
to that thin signal with the *heavyweight* Phase 1 flow. That is the wrong
default: it slows the user down at exactly the moment he wants to move fast.
(No external Linear issue; tracked here, continuing the skill-authoring lineage.)

### Motivating example — this very session (captured per user request)

The user opened with:

> "start a new plan-81. Feature is how start skill should behave when kicking
> off a plan/branch with very little context"

In response, the agent (me) did **all of the following before creating
anything**:

1. Ran three rounds of repo investigation (locating the skill source, reading
   the JTE template tree, reading `phase1-plan.jte`, `when-to-apply.jte`).
2. Fired a **four-part multi-select questionnaire** asking the user to choose
   the behavior, the authoring location, and the delivery mechanism.

The user's correction was blunt and correct:

> "Haha. All that you're doing now in this session is exactly what should not
> happen. … When there's little context, and user gives a short message, he's
> looking to add more details later or do some exploratory/iterative work.
> Don't slow him down. Just quickly create a branch. Tell the user a branch and
> basic plan file has been created on the branch for his use and return him
> back to the chat."

This session **is** the bug report. The skill should have produced, in one
shot: a `t/plan-81-…` branch, a stub `plan-81.md`, and a one-line handoff —
then stopped. Instead it interrogated. plan-81 changes the skill so the next
agent does the right thing automatically.

## Desired behavior

When the start skill is invoked to kick off a plan/branch and **context is
thin** (short user message, no attached spec/PRD, no prior planning in the
conversation):

1. **Do not investigate.** No multi-round repo spelunking before acting.
2. **Do not interrogate.** No risk questionnaire, no clarifying-question gate,
   no `AskUserQuestion` to choose scope/approach.
3. **Act immediately:**
   - Create the `t/{issue-id}-{slug}` branch.
   - Drop a minimal **stub** `plan-81.md` on the branch (title + a Context
     placeholder + an empty/notional task list — clearly marked as a stub for
     the user to flesh out).
   - **Do not** run risk calibration, `plan init`, tagging, or task-tracking
     setup yet — those belong to the later, context-rich pass.
4. **Hand back:** Tell the user, in one or two lines, that the branch and a
   basic plan file exist on the branch for his use, and return control to the
   chat. Stop.

When context is **rich** (the user supplies a spec, or substantial planning has
already happened in the conversation), the existing Phase 1 flow still applies
unchanged — draft, risk-calibrate, init, tag, review.

The two paths share one mechanism that degrades gracefully: a cheap
context-richness check at intake decides which path to take.

## Design

- **New template:** `skills/shared/workflow/phase0-intake.jte.md`. It defines
  the intake decision and the thin-context fast path, and explicitly says: if
  context is thin, branch + stub + handoff + **stop**; if rich, fall through to
  Phase 1 as written.
- **Wire-in:** add `@template.shared.workflow.phase0-intake(model = model)` to
  `skills/shared/base-workflow.jte.md`, **before** the `phase1-plan` line.
- **Phase 1 cross-reference:** a small edit to `phase1-plan.jte.md` noting it is
  the *rich-context* path, reached either directly (context was already rich) or
  after the user has fleshed out the phase-0 stub.
- **No version bump.** Edit JTE sources + refresh golden baselines only; leave
  version at 0.3.22 (human cuts releases). Per repo memory, never run
  publishRelease.
- The phase0 template must contain **zero references to experimental features**
  (base-skill invariant), and must render cleanly across all four hosts
  (claude-prod, gemini-prod, codex-prod, windows).

## Open questions (for the rich-context pass; do NOT block the stub)

- Exact wording of the thin-vs-rich heuristic — keep it judgment-based prose, or
  give 2–3 concrete tripwires (message length, presence of attached doc,
  prior-planning-in-thread)?
- Should the stub plan file have a fixed skeleton, and if so what sections?
- Does phase0 need anything host-specific, or is it pure shared prose?

## Tasks

*(Stub — to be fleshed out and risk-calibrated with the user before Phase 2.
Notional slices below.)*

### Task 1: Author phase0-intake template + wire into base-workflow [Medium]

Create `skills/shared/workflow/phase0-intake.jte.md` defining the intake
decision and thin-context fast path; add it to `base-workflow.jte.md` before
phase1. Cross-reference from `phase1-plan.jte.md`.

### Task 2: Refresh golden baselines and verify cross-host render [Low]

*Depends-on: 1*

Re-render all four hosts, update the golden baseline fixtures, and confirm the
new phase0 prose appears with no experimental leakage and no broken templating.
