# Plan 82 — Zero-trace mode: shipsmooth state in a separate git repository

## The approach (committed)

shipsmooth will support a usage mode in which it leaves **no trace in the user's
main repository** and instead keeps **all of its state in a separate git
repository**.

- The user's project repo is untouched: no `.agents/` directory, no marker or
  pointer files, nothing added to its working tree or its history.
- Everything shipsmooth produces — plan markdown, task XML, the ledger, the
  object store, learnings, and the worktree/integration bookkeeping — lives in a
  **separate git repo** dedicated to shipsmooth state.
- That state repo carries its own permanent `plan-N-vK` plan-revision tags, so
  the audit trail (the `git diff` between two plan tags) is **fully
  self-contained in the state repo**. The project repo plays no part in the
  audit story.
- The one explicit, opt-in exception is that shipsmooth *may* write
  plan-revision tags into the user's main repo. Tags leave no working-tree trace
  (only `git tag -l` reveals them) and are permanent (they reference commit
  objects, so they survive squash-merge and branch deletion). This is the only
  thing shipsmooth is ever allowed to add to the main repo in this mode, and only
  if the user turns it on.

This document describes only this approach. It does not weigh alternatives.

## Context

### Backlog feature this serves
`<backlog-issue>` Feature: **"A zero-trace shipsmooth mode that keeps all state
in a separate git repository."** No existing backlog issue tracks this; this
plan defines it. Motivation: a user adopting shipsmooth on their own project does
not want `.agents/` — plan files, a JSONL ledger, an object store, and git
worktrees — committed into, or even sitting untracked inside, *their* repo's
working tree.

### Why a separate git repo (not just an external directory)
The versioned plan contract needs a permanent audit anchor. Today that anchor is
a git tag (`plan-N-vK`) in the project repo; the audit trail is `git diff`
between two such tags. If the contract leaves the project repo, the permanence
has to leave with it — and the natural home that preserves the *exact same*
`git diff`-between-tags audit model is another git repo. So the state repo is a
real git repo with real `plan-N-vK` tags, not a loose directory.

### What "shipsmooth state" is today (and where it must move to)
From `core/.../conf/ShipsmoothDataLocator.java` — the single source of truth for
path construction; its Javadoc already says it was "Named 'Locator' to
anticipate a future option to relocate the data tree outside the repo" — and
`.gitignore`:

| State | Path today | Git status today | In zero-trace mode |
|---|---|---|---|
| Plan markdown | `.agents/plans/plan-{N}.md` | tracked in project repo | tracked in **state repo** |
| Task XML | `.agents/plans/plan-{N}-tasks.xml` | tracked in project repo | tracked in **state repo** |
| Learnings | `.agents/learnings/` | tracked in project repo | tracked in **state repo** |
| Ledger | `.agents/ledger.jsonl` | ignored | in **state repo** |
| Object store | `.agents/objects/` | ignored | in **state repo** |
| Task worktrees | `.agents/tasks/{taskId}` | ignored git worktrees | git worktrees of the **project** repo, located outside it |
| Integration worktrees | `.agents/integration/plan-{N}` | ignored git worktrees | git worktrees of the **project** repo, located outside it |
| Scratch | `.agents/tmp/` | ignored | in the state-repo area |

Note the worktree rows: `tasks/` and `integration/` are **git worktrees of the
project repo**, not files we can simply move into the state repo. They stay bound
to the project repo's `.git` (a worktree cannot belong to a different repo), but
their *physical location* moves outside the project working tree so nothing
appears inside it. This is the one part of the state that is "project-repo
worktree, parked elsewhere" rather than "owned by the state repo."

### Path-resolution seam
- `ShipsmoothDataLocator(repoRoot)` resolves tracked + ledger/object paths via
  `repoRoot.resolve(...)`. This is the single seam where the data tree's root is
  decided — the natural place to introduce "state root ≠ project root."
- `worktreeRel(taskId)` / `integrationRel(planId)` return **repo-relative
  strings**, consumed as `repoRoot.resolve(rel)` in `WorkflowServiceImpl`. These
  must change to place worktrees at an external path while keeping them attached
  to the project repo's git.
- `repoRoot` comes from `git rev-parse --show-toplevel` (`cli/.../RepoRoot.java`),
  injected via `ServicesModule`. Two CLI call-sites (`plan/Init`,
  `worker/WorkerCleanup`) still construct the locator with `Paths.get(".")`.
- Non-code surfaces also hardcode `.agents/...`: the `start` SKILL.md prose and
  the POSIX bootstrap. The skill *writes plan `.md` files directly*, not through
  the locator, so the zero-trace mode has a documentation/skill surface too.

## Audit trail in zero-trace mode

- Plan revisions are tagged `plan-N-vK` **in the state repo**; `git diff
  plan-82-v1 plan-82-v2` (run in the state repo) is the audit, identical in shape
  to today's model.
- Tier "tags only": the same tag name *may* also be written to the project repo,
  giving a permanent tag-name ↔ tag-name correlation across the two repos, both
  ends surviving squash-merge and branch deletion.
- Any reference *from* the state repo *to* a specific project-repo commit (e.g.
  "this plan version was developed around project commit abc123 / PR #N") is
  **best-effort and non-load-bearing**: squash-merge or rebase in the project
  repo can make that SHA unreachable, so it is recorded only as a human-readable
  hint and no flow may gate on it.
- A consequence the flow-walk must respect: the state repo and (in the tags-only
  tier) the project repo carry plan tags of the **same name** pointing at commits
  in **different** repos. The existing tagging commands (`plan tag --kind
  version`, the printed `git push origin plan-N-vK` lines) assume a single repo
  and must be made repo-explicit.

## User flows to walk through

1. **Opt-in at first use.** New user wants zero-trace mode on their repo.
   Where/how do they turn it on, and where does shipsmooth create/locate the
   state repo? Walk the first-run flow end to end, including the tags-only
   sub-choice.
2. **Default user, unchanged.** Confirm the default (in-repo) path is bit-for-bit
   today's behavior — zero-trace mode must be invisible to anyone not using it.
3. **Switching mid-project.** A user with existing in-repo state turns zero-trace
   mode on. What happens to the existing `.agents/` content — migrate into the
   state repo, or start fresh? Walk the flow and its failure modes.
4. **Two clones / CI.** Same project checked out twice, or on CI. Does the state
   repo collide, share, or isolate? How is the state repo keyed to a project?
5. **Teammate without the mode.** User A uses zero-trace mode; teammate B clones
   the project and runs shipsmooth with defaults. B's project repo has no trace
   (correct) — confirm B gets a coherent picture and doesn't see dangling tags
   (relevant only in the tags-only tier) or expect state that isn't there.
6. **Resume / recovery.** Session-resume pre-flight and integrate-recovery look
   for worktrees and the ledger by repo-relative path today. Walk how each reads
   when state and worktrees live outside the project repo.

## Tasks

### Task 1: Inventory state + invariants, and name the seam for zero-trace mode [Low]
Produce a definitive table of every `.agents/` artifact: its git status today,
which Core Invariant or workflow step depends on its location, and exactly where
it lands in zero-trace mode (state repo vs. external project-repo worktree).
Confirm `ShipsmoothDataLocator` is the single code seam and list every non-code
surface (SKILL.md prose, POSIX bootstrap, `.gitignore` handling) that the mode
touches. Output: a section appended to this plan. No code.

### Task 2: ~~Decide the opt-in mechanism and the state-repo layout/keying~~ [ABANDONED — superseded]
*Depends-on: 1*
**Abandoned at v3.** Superseded by the per-host opt-in tasks (7 Claude, 8 Codex,
9 Gemini) plus the shared keying decision folded into Task 7. The opt-in
mechanism turned out to be materially host-specific — Claude plugins, Codex, and
Gemini each configure differently — so a single "decide the opt-in mechanism"
task was the wrong granularity. The host-agnostic state-repo layout/keying
(location, repo identity, external worktree location) is now decided once in
Task 7 and reused by Tasks 8 and 9.

### Task 3: Walk all six user flows under zero-trace mode [Medium]
*Depends-on: 7*
For each flow, write the narrated end-to-end experience, listing every surface
(CLI output, SKILL.md prose, git visibility in both repos, recovery) that
changes. Pin down, per tier, which repo each tagging command targets. Flag any
flow that still feels inconsistent; if one does, loop back to Task 2.

### Task 4: Choose a name for this mode of operation [Low]
*Depends-on: 1*
Propose and select a good, user-facing name for the zero-trace / separate-state-
repo mode (e.g. candidates to be generated and weighed for clarity, brevity, and
fit with existing shipsmooth vocabulary). The chosen name becomes the term used
in the config knob, CLI output, and SKILL.md. Output: the chosen name + a short
rationale and the rejected alternatives.

### Task 5: Prepare the existing codebase for the feature [High]
*Depends-on: 3, 4*
Refactoring only — no behavior change, default mode stays bit-for-bit identical.
Make the codebase ready to support a state root distinct from the project root:
route every `.agents/` path and the two `Paths.get(".")` call-sites through
`ShipsmoothDataLocator`, introduce the "state root" concept the locator resolves
against (defaulting to project root so nothing changes yet), and make the
worktree/integration path construction capable of an external location. Tests
must prove the default path is unchanged.

### Task 6: Implement zero-trace mode [High]
*Depends-on: 5, 7, 8, 9*
Make the necessary changes to actually support the separate-state-repo mode
end to end: the opt-in mechanisms from Tasks 7–9, state-repo creation/location/
keying, relocating tracked state + ledger + objects into the state repo, parking
the project-repo worktrees externally, the dual-repo plan tagging (incl. the
tags-only tier), and the matching SKILL.md / bootstrap / `.gitignore` updates.
Cover the six flows, especially mid-project switching (flow 3) and
resume/recovery (flow 6).

### Task 7: Opt-in mechanism for Claude (+ shared state-repo layout/keying) [Medium]
*Depends-on: 1*
Design how a **Claude** user turns separate-repo mode on, using Claude plugin
customization mechanisms (plugin config / settings / env, TBD by research). Also
make the **host-agnostic** decisions here, reused by Tasks 8–9: where the state
repo lives, how repo identity is computed (so two clones/CI don't collide), the
external worktree location, and the tags-only sub-choice. Justify against
existing config conventions; define precedence rules. Output: a decision section.
Gate: human sign-off before any code.

### Task 8: Opt-in mechanism for Codex [Medium]
*Depends-on: 7*
Design how a **Codex** user turns separate-repo mode on, reusing Task 7's shared
keying/layout. Codex has no SessionStart hook (see plan-77) and uses a one-time
installer model — account for that in how the knob is read and persisted. Output:
a decision section.

### Task 9: Opt-in mechanism for Gemini [Medium]
*Depends-on: 7*
Design how a **Gemini** user turns separate-repo mode on, reusing Task 7's shared
keying/layout, via the Gemini extension mechanism. Output: a decision section.

## Research log

### Task 7 research — Claude plugin customization (2026-06-16)
Source: code.claude.com/docs/en/plugins-reference (User configuration; Environment
variables; Version management).

**Finding: Claude plugins have a first-class `userConfig` mechanism — the right
lever for the separate-repo opt-in.** Declared in `plugin.json`:

```json
"userConfig": {
  "state_repo_dir": {
    "type": "directory",
    "title": "Separate state repository",
    "description": "Keep all shipsmooth state in this directory instead of the project repo. Leave empty for in-repo (default) mode.",
    "required": false
  }
}
```

How it surfaces, all authoritative:
- Per-option `type` ∈ {string, number, boolean, **directory**, file}; `directory`
  fits a state-repo path. Other fields: `title`, `description` (both required),
  `default`, `required`, `sensitive`, `multiple`, `min`/`max`.
- Claude Code **prompts the user at enable time** — no hand-editing settings.
- Values reach our code three ways: `${user_config.KEY}` substitution in hook /
  MCP / LSP / monitor commands; **exported to plugin subprocesses as
  `CLAUDE_PLUGIN_OPTION_<KEY>`** env vars; and substituted into skill/agent
  content (non-sensitive only).
- Non-sensitive values persist in `settings.json` under
  `pluginConfigs[<plugin-id>].options`. (Per-user; not per-project — note for
  flow 4 keying.)
- Path vars available to hooks/subprocesses: **`${CLAUDE_PLUGIN_ROOT}`** (install
  dir, ephemeral — don't store state), **`${CLAUDE_PLUGIN_DATA}`** (persistent
  across updates — viable *default* state home), **`${CLAUDE_PROJECT_DIR}`** /
  `CLAUDE_PROJECT_DIR` env (project root — the repoRoot we already derive).

**Mechanism decision for Claude (Task 7):** declare a `userConfig` option (e.g.
`state_repo_dir`, type `directory`); the SessionStart hook / CLI reads
`CLAUDE_PLUGIN_OPTION_STATE_REPO_DIR` and passes it as the `stateRoot` to
`ServicesModule(repoRoot, stateRoot, …)` (the seam built in Task 5). Empty/unset
⇒ in-repo default. `${CLAUDE_PROJECT_DIR}` gives repoRoot for keying.

Open sub-questions for Task 7 proper: per-user storage means the same option
value applies across projects unless we key the state location by project
identity (flow 4); and the tags-only sub-choice needs its own boolean
`userConfig` option.

## Out of scope
- Multi-repo / shared-team state *servers*. Local filesystem only; the state repo
  is a local git repo.
- Changing the default in-repo behavior in any observable way.
