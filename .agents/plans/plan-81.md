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

## Background research & design decisions

These decisions are grounded in `docs/references/code-quality-1.md` (the
LLM-behaviour reference). The framing matters: the skill's failure this session
was **not** a missing rule — it was a **mode-selection** failure.

Per the latent-variable view of in-context behaviour (code-quality-1.md
§"The Latent-Variable View"), a skill file is *evidence* that sharpens the
posterior over latent modes the model already has; it selects a mode, it does
not teach one. The current `## When to apply` text and the immediate jump into
`## Phase 1 — Plan, Calibrate, & Commit` condition the model onto a single mode:
**heavyweight ceremony**. A one-line kickoff and a spec-backed kickoff both land
in the same basin, because nothing in the skill selects a *lightweight* mode.
The fix is therefore not "add a rule telling the model to slow down less" — a
rule only reshapes density and cannot truncate the dominant mode's support
(§"Limitations": `P(bad output)` is never zero). The fix is to **author a second,
clearly-demarcated mode and a cheap branch point that selects between them.**

Design decisions:

1. **Add a Phase 0 intake branch point — the highest-leverage edit.** Insert a
   `## Phase 0 — Intake` *before* `## Phase 1`, doing the thin-vs-rich decision
   and, on thin, executing branch + stub + handoff + **stop**.
   *Grounded in autoregressive path dependence:* the first structural commitment
   after kickoff is sticky. Today the first thing the model sees post-kickoff is
   "Draft Plan / Risk Analysis," so it elaborates ceremony. Putting the branch
   point first makes the cheap path the default token trajectory for a thin
   prompt.

2. **Use a contrastive exemplar, not prose rules — and make this session the
   anti-target.** Embed a tiny before/after in Phase 0:
   - **Thin kickoff** — *"start plan-81, feature is X"* (no spec, no prior
     planning in-thread).
   - ✅ Create the branch → write a stub `plan-N.md` → tell the user both exist
     on the branch → **stop**.
   - ❌ Don't investigate the repo, don't fire a risk questionnaire, don't run
     `plan init` or tagging. *(This is the anti-pattern that motivated plan-81.)*

   *Grounded in §7 (contrastive exemplars):* an anti-target paired with a target
   sharpens the decision boundary further than the target alone, and conditions
   the model in output space (the transcript shape) — stronger per token than
   prose like "be lightweight when context is thin."

3. **Give the thin/rich test 2–3 concrete tripwires, not a vibe.** The model
   cannot reliably self-certify a fuzzy judgment (§"Limitations": such answers
   are guesses, not computations). Make the gate near-mechanical: *thin* =
   (a) kickoff message under ~2 sentences **and** (b) no spec/PRD/plan body
   attached **and** (c) no substantial planning earlier in the thread. Any one
   absent → rich path.

4. **Re-inject the "don't interrogate" conditioning at the Phase 0 boundary.**
   *Grounded in influence decay (§"Influence Decay", §6):* conditioning placed
   only in the `When to apply` preamble erodes by the time the model reaches
   action. The stop-and-handoff instruction must live *inside* Phase 0 at the
   point of action, restated — not merely implied up top.

5. **Keep Phase 0 free of mechanical restatement; hand off to existing
   machinery.** *Grounded in §1 (separate skill governance from tool
   governance) and the attention budget:* don't re-describe `plan branch` /
   `plan init` semantics inside Phase 0 — the CLI is the source of truth. Phase 0
   states *which mode we're in and when to stop*, then defers. The edit adds the
   missing signal (the mode), not duplicated procedure.

6. **Frame Phase 1's opening as the rich-context path explicitly.** One line at
   the top of `## Phase 1`: "Reached either directly when kickoff context is
   already rich, or after the user has fleshed out a Phase 0 stub." This stops
   the model treating Phase 1 as the unconditional next step after kickoff.

7. **Authoring hygiene — volatile version string stays single-sourced.**
   *Grounded in §10 (no volatile high-frequency tokens):* the new Phase 0 text
   references the CLI abstractly via `${model.cliBin()}` only; it must not
   hard-code another copy of the version/path. Ideally Phase 0 introduces *zero*
   new invocation strings (see decision 5), sidestepping staleness entirely.

**Explicitly rejected:**
- Adding a rule like "don't over-investigate on short prompts" — a probabilistic
  nudge competing against the dominant ceremony mode; the structural branch point
  + contrastive exemplar is what actually shifts the trajectory.
- Making Phase 0 a questionnaire — the bug *was* a questionnaire.

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

## Update (v2) — structural pivot: prose rule did not hold; carve thin-path plan creation into the CLI

**What manual testing showed.** With the Phase 0 template in place, the agent
correctly classified thin context and took the fast path — but **still committed
the stub** (`git add` + `git commit`) despite the template saying "don't commit"
in three places. This reproduced twice, and once hit the original
unconfigured-git-identity failure again. This is exactly what plan-81's own
"Background research & design decisions" predicted: a *prose rule* reshapes
density but cannot truncate the dominant "commit your work" mode
(§"Limitations": `P(bad output)` is never zero). More prose is the losing move.

**Root cause (structural, not textual).** The thin path hands the agent a
freshly-created branch **plus a new untracked file it just authored**, then says
"stop." That dirty-tree-immediately-after-branch state *is* the lure — it reads
as "mid-task, commit pending." Plan-file authoring lives entirely in the
LLM's hands today (there is no CLI command that creates a plan file —
`plan branch` only makes the branch; `plan init` only consumes an
already-written `.md`). So the "now commit the file I just wrote" trajectory is
inside the agent's own action sequence, where prose can't reliably suppress it.

**The fix — move thin-path plan creation into the CLI.** Add a new
`plan create` command that, in **one atomic call**, derives the plan number,
creates the branch, and writes the stub `plan-{N}.md` — and **does not commit**.
The Phase 0 thin path collapses to: detect thin → invoke `plan create` → relay
its output → stop. The agent no longer authors the file or runs git directly, so
the commit-lure leaves its action trajectory entirely. This also eliminates the
doubled-slug papercut (below), because the agent stops fabricating an `--issue`
id equal to the desc.

This is a scope shift from the original "template/prose-only" plan (hence the v2
bump): plan-81 now also ships a small CLI command. The rich path (Phase 1) stays
LLM-authored — full plans need judgment; only the thin-path stub, which needs
none, moves into the CLI.

### `plan create` — command spec

Signature:

```
shipsmooth plan create --desc "<short phrase>"
```

Behavior, in order:

1. **Derive the plan number `N`** inside the CLI — scan `.agents/plans/plan-*.md`,
   take the highest existing number + 1; if none exist, `N = 1`. The LLM does
   *not* compute or pass `N` (removes a judgment call that was an error source in
   testing). No `--plan` option.
2. **Build the branch name** `t/{N}-{slug(desc)}`. The prefix is always the
   derived plan number — never an issue id — so the doubled-slug bug
   (`t/desktop-ui-desktop-ui`, caused by the skill passing `--issue desktop-ui
   --desc desktop-ui`) cannot recur. Reuse `Branch`'s slug logic, extracted to a
   shared util (see below).
3. **Create + check out** the branch via the existing `GitState` seam
   (`createBranch`). If the branch already exists → error and stop (signals "this
   plan already exists — did you mean to resume?"; ties to the PB-356
   active-plan gap).
4. **Write the stub** `.agents/plans/plan-{N}.md` with the fixed skeleton from
   Task 2 (title, Context-placeholder echoing `desc` in the user's words with
   unknowns marked, notional `## Tasks` placeholder, a top note that this is a
   stub to flesh out).
5. **Do not commit, tag, or push.** No `git commit`, no identity configuration.
   The stub is left uncommitted on the branch for the user — sidestepping the
   identity-failure bug at its source.
6. **Print a one/two-line handoff**: branch name + the `git push -u` line +
   "stub written; flesh it out, then run `plan init`."

Slug generation — **accent-folding, zero new dependencies**:
- Prepend a `java.text.Normalizer` pass before the existing regex: normalise to
  `NFD` and strip combining marks (`\p{M}+`), so `"Café déjà"` → `cafe-deja`
  rather than the regex's lossy `caf-d-j`.
- Then the existing transform: `toLowerCase()`, `[^a-z0-9]+` → `-`, trim leading/
  trailing `-`.
- Empty result (desc was all punctuation) → branch is `t/{N}` with no slug
  suffix (keep the fast path fast; do not error).
- No slug library — these are short dev-authored ASCII-ish phrases; `Normalizer`
  covers the accented-Latin case for free. (Rejected: `com.github.slugify` —
  dependency weight for a case that will not occur here.)

Wiring follows repo conventions: a new `Create` leaf under
`cli/.../plan/`, registered in `Plan`'s constructor `addLeaves(...)` like the
other leaves (per repo memory: group parents build their own leaves; CLI
commands are not Dagger-managed). The shared slug util is extracted so both
`Branch` and `Create` reference one copy.

## Known gaps / follow-ups

- **Active-plan detection is not deterministic (PB-356).** Phase 0 must avoid
  starting a new plan on top of one already in flight, but there is no reliable
  CLI signal for "is any plan active": every plan command requires `--plan {N}`,
  the `t/` branch prefix is only a convention, and a bare "any non-`complete`
  XML" scan over-fires on stale plans (e.g. plan-45 `active`, plan-71
  `in-review`). For now Phase 0 instructs the agent to glance at the latest
  plan's state and **surface it as a question** — an LLM judgment call. Making
  this systematic/deterministic (candidate: a `plan list` / `plan active`
  command) is tracked in Linear as **PB-356**, in *shipsmooth — Backlog &
  Roadmap*.

## Open questions (for the rich-context pass; do NOT block the stub)

- Exact wording of the thin-vs-rich heuristic — keep it judgment-based prose, or
  give 2–3 concrete tripwires (message length, presence of attached doc,
  prior-planning-in-thread)?
- Should the stub plan file have a fixed skeleton, and if so what sections?
- Does phase0 need anything host-specific, or is it pure shared prose?

## Tasks

Risk-sorted (High → Med → Low), with the one dependency-ordering exception
honoured. Tasks 1–3 are template/prose work (verification is the golden-baseline
render check, not a coverage threshold). The v2 pivot adds Tasks 5–6, which are
CLI code and **do** carry the normal unit-test/coverage bar. Task 4 (render
verify) now depends on the CLI tasks too, since the thin-path template is
rewritten to call `plan create`.

> **v2 note:** Tasks 1–3 shipped against the original "branch → LLM writes stub →
> commit" thin path. The pivot replaces that with a single `plan create` call.
> Task 1's thin-path action sequence is superseded by Task 7's rewrite; the
> contrastive exemplar and "don't interrogate" conditioning from Task 1 stay.

### Task 1: Author phase0-intake.jte.md content [High]

The core, novel work and the only genuinely uncertain part — this is where the
skill either selects the lightweight mode or doesn't (the spiral risk). Create
`skills/shared/workflow/phase0-intake.jte.md` containing:

- The thin-vs-rich tripwire test (design decision 3): *thin* = short kickoff
  (under ~2 sentences) **and** no spec/PRD/plan body attached **and** no
  substantial prior planning in-thread; any one absent → rich path.
- The thin-path action sequence: branch → write stub `plan-N.md` → one/two-line
  handoff → **stop**.
- The contrastive ✅/❌ exemplar with this session as the anti-target
  (decision 2).
- The re-injected "don't interrogate" instruction at the point of action
  (decision 4).

Zero new invocation strings; CLI referenced only via `${model.cliBin()}`
(decisions 5, 7).

### Task 2: Define the stub plan-file skeleton the thin path writes [Medium]

*Depends-on: 1*

Resolves the open question "should the stub have a fixed skeleton." Pin down
exactly what sections the stub `plan-N.md` contains (title, Context placeholder,
notional task list, clearly marked as a stub for the user to flesh out) and bake
that skeleton into the Phase 0 text so it is reproducible, not improvised
per-session.

### Task 3: Wire Phase 0 into base-workflow + cross-reference Phase 1 [Low]

*Depends-on: 1*

Mechanical wiring. Add
`@template.shared.workflow.phase0-intake(model = model)` to
`skills/shared/base-workflow.jte.md` immediately before the `phase1-plan` line,
and add the one-line "rich-context path, reached directly or after a Phase 0
stub" framing to the top of `phase1-plan.jte.md` (decision 6).

### Task 5: Extract shared slug util with Normalizer accent-folding [Medium]

The reusable core for `plan create`. Extract `Branch`'s inline `slugify` into a
shared util (e.g. `io.bitken.ss.cli.plan.Slugs` or a `gw`/`svc` home consistent
with the codebase) and add a `java.text.Normalizer` NFD + `\p{M}+`-strip pass
*before* the existing lowercase/`[^a-z0-9]+`→`-`/trim transform. Point `Branch`
at the extracted util so there is one copy. Unit-test: plain phrase, accented
Latin (`"Café déjà"` → `cafe-deja`), all-punctuation (→ empty), mixed case,
internal punctuation runs. No new dependencies.

### Task 6: Implement `plan create` CLI command [High]

*Depends-on: 5*

The genuinely uncertain part of the pivot — does moving plan-file authoring into
the CLI actually remove the commit-lure. Add a `Create` leaf under
`cli/.../plan/` per the spec in the v2 Update section: derive `N` (highest
`plan-*.md` + 1, else 1), build `t/{N}-{slug(desc)}`, create + check out the
branch (`GitState`), write the stub `plan-{N}.md` (Task 2 skeleton), **no
commit/tag/push**, print branch + push line + handoff. Register it in `Plan`'s
`addLeaves(...)`. Error cleanly on existing branch. Unit-test N-derivation
(none / gaps / max), branch-name construction, stub contents, and the
no-commit / left-uncommitted guarantee. Meet the agreed coverage bar.

### Task 7: Rewrite Phase 0 thin path to call `plan create`; drop the commit step [Low]

*Depends-on: 6*

Replace the thin-path action sequence in `phase0-intake.jte.md` so it is a
single CLI invocation, not an LLM-authored branch+file+commit sequence: detect
thin → run `${model.cliBin()} plan create --desc "<phrase>"` → relay its output
→ **stop**. Remove the "write the stub yourself" and any commit/tag/push prose
(the CLI now owns file creation and deliberately does not commit). Keep the
contrastive ✅/❌ exemplar and the "don't interrogate" conditioning. The ✅
target becomes "run `plan create` → relay output → stop"; the ❌ anti-target
keeps this-session-as-anti-pattern and adds "don't hand-author the stub then
commit it."

### Task 4: Refresh golden baselines and verify cross-host render [Low]

*Depends-on: 1,2,3,7*

Re-render all four hosts (claude-prod, gemini-prod, codex-prod, windows), update
the golden baseline fixtures, and confirm: Phase 0 prose appears (now invoking
`plan create`), no experimental leakage in the base skill, no broken JTE
templating, and `cliBin` renders correctly per host (watch the Windows `.cmd`
path bug). Also confirm `plan create` appears in the `plan` group `--help`.
