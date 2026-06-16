# Plan 82 — Relocating shipsmooth state outside the project repo (design exploration)

**Status:** Phase 1 — design exploration only. No code changes in this plan's
scope. The deliverable is a validated decision: *should* shipsmooth offer an
"out-of-repo state" option, and if so, *which* state, *where*, and *how the user
turns it on*. Implementation, if approved, becomes a follow-on plan.

## Context

### Backlog feature this serves
`<backlog-issue>` Feature: **"Let the end user keep shipsmooth's working state
out of their project git repo."** No existing backlog issue tracks this; this
plan *defines* it. The motivation: a user adopting shipsmooth on their own
project does not necessarily want `.agents/` — plan files, a JSONL ledger, an
object store, and git worktrees — committed into, or littering, *their* repo's
working tree and history.

### What "shipsmooth state" actually is today
From `core/.../conf/ShipsmoothDataLocator.java` (the single source of truth for
path construction; its Javadoc already anticipates "a future option to relocate
the data tree outside the repo") and `.gitignore`, the state divides into two
categories that behave very differently:

| State | Path | Git status today | Nature |
|---|---|---|---|
| Plan markdown | `.agents/plans/plan-{N}.md` | **tracked** | Versioned contract; tagged; diffed between plan versions |
| Task XML | `.agents/plans/plan-{N}-tasks.xml` | **tracked** | Task-state audit trail; `git diff` between tags is the audit |
| Learnings | `.agents/learnings/` | **tracked** | Durable notes |
| Ledger | `.agents/ledger.jsonl` | ignored | Append-only runtime event log |
| Object store | `.agents/objects/` | ignored | Content-addressed blobs referenced by the ledger |
| Task worktrees | `.agents/tasks/{taskId}` | ignored | **git worktrees** (branch `agent-work/{taskId}`) |
| Integration worktrees | `.agents/integration/plan-{N}` | ignored | **git worktrees** (branch `integration/plan-{N}`) |
| Scratch | `.agents/tmp/` | ignored | Throwaway |

This table is the reason the feature is not a single switch. "Move state out of
the repo" means *at least three* different operations with different blast
radius.

### Path-resolution seam (informational, not in scope to change here)
- `ShipsmoothDataLocator(repoRoot)` resolves tracked + ledger/object paths via
  `repoRoot.resolve(...)`.
- `worktreeRel(taskId)` / `integrationRel(planId)` return **repo-relative
  strings**, consumed as `repoRoot.resolve(rel)` in `WorkflowServiceImpl`. Git
  worktrees can physically live anywhere, but each stays bound to the repo's
  `.git`. This is the load-bearing constraint for any "outside the repo" idea.
- `repoRoot` comes from `git rev-parse --show-toplevel` (`cli/.../RepoRoot.java`),
  injected through `ServicesModule`. Two CLI call-sites (`plan/Init`,
  `worker/WorkerCleanup`) still construct the locator with `Paths.get(".")`.
- Non-code surfaces also hardcode `.agents/...`: the `start` SKILL.md prose, and
  the POSIX bootstrap. The skill *writes plan `.md` files directly*, not through
  the locator — so any relocation has a documentation/skill surface, not just a
  Java surface.

## The central question: are the two options mutually consistent?

We must decide whether offering **both** "state in repo" (today's default) and
"state outside repo" is coherent, or whether it creates contradictions a user
will trip over. The answer hinges on the three categories above. Work each as a
use case.

### Use case A — Tracked plan/task files (`plans/`, `learnings/`)
**Core Invariant #4 says git is the source of truth for plan content, and the
audit trail *is* `git diff` between plan tags.** This is the hard case, and
**full relocation is in scope: the plan must design a coherent answer here, or
prove one is impossible.** If plan files move out of the user's repo, today's
model breaks in specific ways that must each be re-answered:
- Plan tags (`plan-{N}-v{K}`) live in the user's repo history but point at files
  no longer in that repo → today's audit trail (`git diff` between tags) breaks.
- The tag-based GitHub permalink convention assumes the plan file is in the
  repo being tagged.
- **Re-answer required (Task 2a):** where does versioning/tagging live when plan
  files are external? Candidate models to evaluate:
  - **(i) Separate git repo for state** — the external state dir is itself a git
    repo; tags live there; the user's project repo is untouched. Audit trail
    moves wholesale, stays a `git diff`, but now there are *two* histories to
    correlate (a state-repo commit ↔ a project-repo commit).
  - **(ii) Cross-reference commit** — plan version recorded as a pointer in the
    user repo (e.g. a lightweight marker file or note) that names the external
    state revision. Keeps one canonical project history; audit becomes a join.
  - **(iii) Non-git versioning of the external contract** — the locator/CLI
    snapshots plan versions itself (content-addressed, like the object store)
    instead of relying on the host repo's git. Most self-contained; furthest
    from the current convention; biggest surface to design.
  - The deliverable for A is a chosen model with its audit story spelled out end
    to end, not a recommendation to avoid the problem.

### Use case B — Ignored runtime state (`ledger.jsonl`, `objects/`, `tmp/`)
These are already git-ignored — moving them out of the repo changes *nothing*
about the user's history. It only changes where bytes physically sit. Candidate
homes: an XDG location (`$XDG_STATE_HOME/shipsmooth/<repo-id>/`) keyed by repo
identity, or a user-chosen path. Low inconsistency risk; this is the "clean win"
slice. Main design work: keying state to the right repo so two checkouts of the
same project don't collide (or *do* share, if that's desired).

### Use case C — Worktrees (`tasks/`, `integration/`)
Git worktrees can be created at an external path (`git worktree add /elsewhere`),
but they remain tied to this repo's `.git`. Moving them out:
- removes them from the user's working tree (nice — no stray `.agents/tasks/`
  dirs), but
- a worktree outside the repo still shows in `git worktree list`, still holds a
  branch, and cleanup/`worker cleanup` logic currently assumes `repoRoot.resolve`.
- Medium inconsistency risk: behavior is *mostly* the same, but recovery flows
  (e.g. the integrate-died-mid-resolver recovery) and humans eyeballing the tree
  now have to look elsewhere.

### Consistency question (to resolve, not pre-judge)
Full relocation — including the versioned contract (A) — is in scope. The
question the plan must answer is: **can offering both "state in repo" (today's
default) and "all state outside repo" be made internally consistent, given the
audit invariants?** B is the easy win, C is medium, and **A is the crux**: a
"both options" story is only honest if the external-plan-files model has a real
audit trail and a real tagging/permalink answer (one of A's models i/ii/iii).
The plan drives A to a designed answer, and only concludes "scope down to
runtime-only" if A is shown to be genuinely irreconcilable — not by default.

## User flows to walk through (the actual deliverable)

For each, narrate the end-to-end feel and surface every place the user or the
agent would notice the difference.

1. **Opt-in at first use.** New user runs the workflow on their repo. Where/how
   do they choose "keep state outside my repo"? (CLAUDE.md override? a `shipsmooth
   config` command? an env var like `SHIPSMOOTH_STATE_HOME`? a prompt on first
   `plan init`?) Walk the first-run flow for each candidate and pick the one with
   the least ceremony.
2. **Default (in-repo) user, unchanged.** Confirm the default path is bit-for-bit
   today's behavior — the option must be invisible to anyone who doesn't want it.
3. **Switching mid-project.** A user with existing in-repo state turns the option
   on. What happens to the existing ledger/objects/worktrees? Migrate, or
   start-fresh-and-orphan? Walk the flow and the failure modes.
4. **Two clones / CI.** Same project checked out twice, or on a CI box. Does
   out-of-repo state collide, share, or isolate? What's least surprising?
5. **Teammate without the option.** User A keeps state outside the repo; teammate
   B clones and runs shipsmooth with defaults. Do they get a coherent picture, or
   does B see a repo whose plan tags reference state B can't find? (This is where
   A-vs-B-vs-C scoping pays off.)
6. **Resume / recovery.** Session-resume pre-flight and the integrate-recovery
   flow both look for worktrees and ledger by repo-relative path today. Walk how
   each reads under the out-of-repo option.

## Tasks

### Task 1: Inventory and categorize all state + their invariants [Low]
Produce a definitive table (extending the Context table) of every `.agents/`
artifact, its git status, which Core Invariant or workflow step depends on its
location, and whether relocating it is Safe / Conditional / Breaks-invariant.
Output: a section appended to this plan. No code.

### Task 2: Design the full-relocation consistency answer (incl. the audit/tagging model for A) [Medium]
*Depends-on: 1*
Full relocation is in scope, so this task must produce a *designed* answer for
the hard case, not a scope-down. Two parts:
- **2a — Audit/tagging model for external plan files.** Evaluate models (i)
  separate state-repo, (ii) cross-reference commit, (iii) non-git snapshotting
  from §Use case A. Pick one; write its end-to-end audit story (how a human
  reconstructs "what changed between plan-N-v1 and v2" when files are external).
- **2b — Both-options coherence.** Confirm the chosen A-model coexists with the
  unchanged in-repo default without contradiction, or document the precise
  conditions under which it can't (which would then justify a runtime-only
  fallback). Record the decision. Gate: human sign-off before flows are detailed.

### Task 3: Walk all six user flows under the chosen scope [Medium]
*Depends-on: 2*
For each of the six flows above, write the narrated end-to-end experience,
listing every surface (CLI output, SKILL.md prose, git visibility, recovery)
that changes. Flag any flow that still feels inconsistent under the chosen scope;
if one does, loop back to Task 2.

### Task 4: Decide the opt-in mechanism [Medium]
*Depends-on: 3*
From flow 1's candidates (CLAUDE.md override / `shipsmooth config` / env var /
first-run prompt), pick one, justify it against the existing config conventions
in the repo, and specify the precedence rules (what wins if two are set).
Output: a decision entry + the exact knob name and default.

### Task 5: Specify the relocation target layout [Low]
*Depends-on: 4*
Define the external directory layout (e.g. XDG path keyed by repo identity), how
repo identity is computed (avoiding the two-clones collision from flow 4), and
the migration story for flow 3. Output: a layout spec section.

### Task 6: Write the go/no-go recommendation + follow-on implementation sketch [Medium]
*Depends-on: 5*
Synthesize into a one-page recommendation: ship it or not, with what scope; and
if yes, a thin-vertical-slice sketch of the *implementation* plan (which would be
plan-83), naming `ShipsmoothDataLocator` as the single seam and listing the
non-code surfaces (SKILL.md, bootstrap) that must move in lockstep.

## Out of scope
- Any code change. This plan produces decisions and specs only.
- Multi-repo / shared-team state servers. Local filesystem only.

Note: relocating the versioned plan contract out of the repo is explicitly
**in** scope (Task 2a designs its audit/tagging model).
