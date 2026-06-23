# plan-03 · Add per-task commit checkpoint to Phase 2

## Feature reference
Backlog issue: n/a (this repo is the skill itself; the change is to the skill's own documentation)

## Narrative
Phase 2's per-task loop has no explicit commit after a task completes. The only Git checkpoints are the failing unit test commit (step 1) and the PR open (step 4). A human reviewing the work has no stable rollback point per completed task — they can only go back to the integration test commit or the PR head.

This plan adds a commit step immediately after coverage passes (before opening the PR), capturing the completed test + implementation as a discrete checkpoint. The commit message uses a `task(N):` prefix so the history is scannable with `git log`. The PR is then opened referencing all task commits.

This directly addresses the README TODO: "Each task of the plan should be optionally committed after review."

## Design decisions

**Commit after coverage passes, not after each sub-step.** Committing after coverage passes means each task commit is verified — it contains a passing test and passing coverage. Committing sub-steps (red test, green implementation separately) would produce a noisier history with mixed red/green states. One commit per task keeps the log clean and each commit meaningful.

**Commit before opening the PR.** The PR should aggregate completed, verified task commits. Opening the PR first and committing after would mean the PR's initial state doesn't reflect the final task state.

**`task(N):` prefix in commit message.** Makes task completion commits identifiable in `git log --oneline` without needing to read PR descriptions. N matches the task number from the Linear issue.

**Push immediately after the task commit.** The point of the checkpoint is to be accessible to a human reviewer. An unpushed commit doesn't serve that purpose.

## Change

**Files:**
- `SKILL.md` at `/opt/workspace/code-flow/SKILL.md`
- `README.md` at `/opt/workspace/code-flow/README.md`
- Sync to `/home/pramod/.claude/skills/code-flow/SKILL.md`

### Change 1 — Per-task loop in SKILL.md Phase 2

In the per-task loop, replace:

```
   Do not close the Linear issue until coverage passes.
4. Open a PR referencing the Linear issue (e.g. `fixes TF-42`). Mark the issue **In Review**.
```

With:

```
   Do not proceed until coverage passes.
4. Commit the completed task (tests + implementation):
   ```bash
   git commit -m "task(N): <short description>"
   git push origin {branch}
   ```
   This creates a stable rollback point. A human reviewing the PR can check out this commit to inspect each task in isolation.
5. Open a PR referencing the Linear issue (e.g. `fixes TF-42`). Mark the issue **In Review**.
```

### Change 2 — README.md TODO

Replace:
```
- TODO: Each task of the plan should be optionally committed after review
```
With:
```
- Each task is committed after tests pass and coverage is verified, creating a rollback point per task
```

## Open questions
None.
