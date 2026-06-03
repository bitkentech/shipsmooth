# Plan 67 — thin deterministic recipes out of SKILL.md into CLI commands

## Context

Backlog issue: **PB-310 — Reduce size of SKILL.md.**

The generated `build/skills/start-dev/SKILL.md` is ~25k chars (~6.2k tokens). The skill's
`_partials/workflow/` fragments contain deterministic bash recipes spelled out token-by-token:
git tagging sequences, a four-command precondition verifier, a session-resume inspection block,
a branch-creation incantation. Each can be replaced with a single `shipsmooth plan <verb>`
invocation, shrinking the skill prose and moving the oracle role to the CLI where it belongs.

This plan implements the four commands proposed in
`docs/proposals/thin-skill-recipes-into-cli.md` and then thins the three skill fragments that
contain those recipes.

## Decision: print, don't push

Every new command performs the **local** mutation (create tag, create branch) and **prints the
exact `git push` line** to run. The irreversible outward action stays with the human/agent,
mirroring the existing `init` command. No command here calls `git push`.

## Approach

Each command follows the `plan/Show.java` / `plan/Init.java` pattern:
`Callable<Integer> implements HasSpec`, registered under the `plan` group in `Plan.java`,
backed by a gateway method, tested in `io.bitken.ss.cli`. Commands are hand-built and not
Dagger-managed (constructors take gateways, not `@Inject`).

`plan preflight` and `plan resume` read git state via a new small read-only git-state gateway
(`GitState`). `plan tag` extends the existing `GitTags` gateway with write-side logic.
`plan branch` reuses `GitState` to create the local branch.

After all four commands land, the three skill fragments (`git-tagging`, `phase1-plan` step 6,
`phase2-execute` session-resume + Step 0) are thinned to single-line invocations, and
`mvn compile` regenerates the SKILL.md to confirm it shrinks.

## Risk-sorted tasks

### Task 1: plan preflight — read-only git-state oracle [High]
*Depends-on:*

New `GitState` gateway reading: working-tree cleanliness (`git status --porcelain`), current
branch name and upstream tracking, whether branch is pushed and up-to-date, whether a given
tag exists locally and remotely (`git ls-remote`). New `Preflight` command under `plan`
accepting `--plan N`: checks clean tree (FAIL on dirty), version tag exists locally (FAIL if
absent), branch pushed and not ahead (WARN only, still passes). Prints `PASS` with any warnings,
or the specific first FAIL reason with non-zero exit. Tests in
`io.bitken.ss.cli.PlanPreflightTest`. `Plan.java` wired up; `CommandTree` unchanged.

### Task 2: plan tag — write-side tagging with refuse-on-exists [Medium]
*Depends-on: 1*

Extend `GitTags` with: `nextPlanVersion(int planNum)` (compute next vK where plan-N-vK does
not yet exist), `tagExists(String tag)`, `createTag(String tag)` (shells `git tag`). New `Tag`
command under `plan` accepting `--plan N --kind version|complete|abandoned`. For `--kind
version`: compute next vK, refuse if already exists (error + non-zero), create tag, print the
push line. For `complete`/`abandoned`: create `plan-N-complete` or `plan-N-abandoned`, print
push line. Tests in `io.bitken.ss.cli.PlanTagTest`. Reuse `GitState` from Task 1 for tag
existence check where appropriate.

### Task 3: plan branch — local branch creation [Medium]
*Depends-on: 1*

New `Branch` command under `plan` accepting `--issue ID --desc S`. Slugifies the description
(lowercase, spaces→hyphens, strip non-alphanumeric), constructs `t/{ID}-{slug}`, creates the
local branch via `git checkout -b`, prints the `git push -u origin ...` line to stdout. Errors
non-zero if branch already exists. Tests in `io.bitken.ss.cli.PlanBranchTest`. Uses `GitState`
for branch-exists check.

### Task 4: plan resume — session-resume pre-flight composite [Low]
*Depends-on: 1*

New `Resume` command under `plan` accepting `--plan N`. Composes: (1) XML task file exists
check (delegates to `TaskStore`), (2) `plan show` output (reuses `Show` call), (3)
`git worktree list` output filtered for this plan's integration worktree. Prints all three
sections in one shot. Tests in `io.bitken.ss.cli.PlanResumeTest`.

### Task 5: thin the skill fragments [Low]
*Depends-on: 1,2,3,4*

Edit `git-tagging.jte.md`, `phase1-plan.jte.md` (step 6 verifier block), and
`phase2-execute.jte.md` (session-resume pre-flight + Step 0 branch block) to replace each
multi-line bash recipe with the corresponding single `${model.cliBin()} plan <verb>` line.
Run `mvn compile`; diff `build/skills/start-dev/SKILL.md` against a pre-edit snapshot in
`.agents/tmp/` to confirm the file shrinks and new invocations render. Record before/after
`wc -c` in the plan file for PB-310 tracking.

## Verification

- `mvn test` green; new test classes (`PlanPreflightTest`, `PlanTagTest`, `PlanBranchTest`,
  `PlanResumeTest`) all pass.
- `shipsmooth plan --help` lists `init`, `show`, `update`, `preflight`, `tag`, `branch`,
  `resume`.
- `shipsmooth plan preflight --plan N` exits 0 on a clean repo with a version tag present.
- `shipsmooth plan tag --plan N --kind version` creates the next tag and prints the push line;
  re-running errors with a clear message.
- `shipsmooth plan branch --issue pb-999 --desc "foo bar"` creates `t/pb-999-foo-bar` locally
  and prints the push command.
- `mvn compile` regenerates skills; `wc -c build/skills/start-dev/SKILL.md` is smaller than
  the pre-edit snapshot.
