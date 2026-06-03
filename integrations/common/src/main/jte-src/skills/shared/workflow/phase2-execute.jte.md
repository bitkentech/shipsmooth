@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Phase 2 — Execute

**Session-resume pre-flight `[Local]`** — If you are picking up a plan that was started in a previous session, run these checks before doing anything else:

```bash
# 1. Confirm the XML task file exists (must not be missing)
ls .agents/plans/plan-{N}-tasks.xml   # if absent, run: ${model.cliBin()} init --plan {N} --tasks-from .agents/plans/plan-{N}.md

# 2. Review current task state
${model.cliBin()} show --plan {N}

# 3. Confirm no stray worktrees or background jobs remain
git worktree list

# 4. Check for a stale integration worktree from a prior session
git worktree list | grep "integration/plan-{N}"
```

Only proceed once you know which tasks are done and which are next.

---

**Step 0: Create a branch**

Create and push a branch named after the primary Linear issue for this plan:
```bash
git checkout -b t/{issue-id}-{short-description}
# e.g. git checkout -b t/pb-149-branch-creation-step
git push -u origin t/{issue-id}-{short-description}
```
All task commits go on this branch. The `t/` prefix stands for "task" (covers features, bugs, chores, etc.). Usernames are intentionally omitted — the task identity is what matters long-term.

**Before writing any code**, confirm the test coverage threshold with the human (default: 95%). Record the agreed value before proceeding.

`[Local]` All `${model.cliBin()}` mutations also append one event to `.agents/ledger.jsonl` (content-addressed blobs in `.agents/objects/`). The XML remains the human-readable source of truth; the ledger is the machine-readable execution trace and the foundation for future parallel execution. Inspect with `${model.cliBin()} ledger list` or `${model.cliBin()} ledger verify`.

### Preamble: integration tests (once, before any task)

1. Write 1–2 integration tests that exercise the feature end-to-end. No more than two.
2. Commit and push them with no implementation — they must fail (red). `[Linear]` Reference the Linear project in the commit message.
3. Confirm red state:
   ```bash
   # run your project's test command, e.g.:
   npm test          # or: pytest, go test ./..., etc.
   ```
   If a test passes at this point, it is testing the wrong thing. Fix or discard it before continuing.

### Per-task loop (The De-risk & Harden Cycle)

For every task in the risk-sorted sequence, apply the appropriate sub-phases:

#### High and Medium risk tasks — De-risk & Harden Cycle

##### Step A: De-risking (Spiral Phase)
- **Goal:** Validate logic and architectural direction. Ignore "Implementation Quality" rules.
- Write at least one failing test (and not more than 3) that targets the core logic (preserving 
Core Invariant #6).
- Implement just enough to prove the approach works. Focus on the core complexity.
- Commit as `draft(N): de-risk [task name]`.
- `[Linear]` Post a comment on the Linear issue notifying the human the draft is ready.
- `[Local]` Run `${model.cliBin()} update-status --plan {N} --task {id} --status de-risked` and `${model.cliBin()} add-comment --plan {N} --task {id} --message "De-risk draft ready for review"`.
- **Wait for explicit approval of the approach.**

##### Step B: Hardening (Quality Phase)
- **Goal:** Achieve technical excellence, human readability, and coverage threshold.
- Refactor the de-risked code for readability, performance, and project patterns. If skill 
"experimental-refine-dev" exists, then use it to improve the design.
- Follow Test Driven Development if possible: Write only one test at a time, then the implementing code 
and then refactor.
- Keep doing Step B until coverage meets the agreed threshold (and if "experimental-refine-dev" skill exists,
quality conforms to its instructions):
  ```bash
  # example — adjust to your toolchain:
  npm test -- --coverage --coverageThreshold='{"global":{"lines":95}}'
  ```
- Commit the completed task (tests + implementation):
  ```bash
  git commit -m "task(N): <short description>"
  git push origin t/{issue-id}-{short-description}
  ```
  This creates a stable rollback point. A human reviewing the PR can check out this commit to inspect each task in isolation.
- `[Linear]` Mark the Linear issue **Agent Coded**.
@if(model.isGemini())
@template.skills.start.gemini.set-commit-hardening(model = model)
@else
@template.skills.start.claude.set-commit-hardening(model = model)
@endif

#### Low risk tasks — Single-pass (current behavior)

1. Write the unit test(s) for this task. Commit them failing (red).
2. Implement the task. Run tests until green.
3. Check coverage meets the agreed threshold:
   ```bash
   # example — adjust to your toolchain:
   npm test -- --coverage --coverageThreshold='{"global":{"lines":95}}'
   ```
   Do not proceed until coverage passes.
4. Commit the completed task (tests + implementation):
   ```bash
   git commit -m "task(N): <short description>"
   git push origin t/{issue-id}-{short-description}
   ```
   - `[Linear]` Mark the Linear issue **Agent Coded**. No draft review needed.
@if(model.isGemini())
@template.skills.start.gemini.set-commit-low-risk(model = model)
@else
@template.skills.start.claude.set-commit-low-risk(model = model)
@endif

---

- **Minor deviation** (task split, reorder, clarification):
  - `[Linear]` Update the Linear issue(s), add a deviation comment explaining why, continue.
  - `[Local]` Run `${model.cliBin()} add-deviation --plan {N} --task {id} --type minor --message "..."`, continue.
- **Major deviation** (fundamental plan problem, architecture issue, blocked): Stop immediately.
  - `[Linear]` Post a Linear project update. Set project health to **"At Risk"**.
  - `[Local]` Run `${model.cliBin()} project-update --plan {N} --blocked --message "..."`.
  - Wait for the human to revise the plan file, commit, push, and give a new go-ahead.

Never autonomously modify the `.agents/plans/` file during execution. If a plan change is needed, surface it and wait.

---
