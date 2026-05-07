@import io.bitken.shipsmooth.resources.PluginModel
@param PluginModel model
@if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif# ${model.skillName()} — Agent Coding Workflow

## When to apply this skill
Apply this skill whenever you are:
- Starting work on a new feature or task
- Asked to write, revise, or execute a plan
- Picking up existing work from Linear
- Closing out, abandoning, or handing off a plan

---

## Core Invariants — Never Violate These

1. **Features vs Plans are strictly separate.** Feature issues live in the permanent backlog forever. Plan issues live in transient `[agent]` projects and are archived after completion. Never create feature issues inside an `[agent]` project.
2. **A committed, pushed, human-reviewed plan is the contract.** You execute against it. You do not autonomously modify it.
3. **Every plan must reference at least one permanent backlog feature issue.** `[Linear]` Create an `[agent]` project linking to it. `[Local]` Record it in the `<backlog-issue>` metadata element of the tasks XML file. If no backlog issue exists, stop and create one before proceeding.
4. **Task tracking is never the source of truth for plan content.** Git is. Linear (or the local tasks file) tracks task state only.
5. **Tags are permanent.** Never delete a plan version tag from remote, even on abandonment or squash merge.
6. **Tests precede implementation.** Write integration test(s) before any task code (Phase 2 preamble), then the unit test for each task before its implementation. Never implement without a failing test already committed. (Apply as far as possible — migrations and config may not be TDD-able.)

---

## Task Tracking Mode

This workflow supports two task tracking modes. Choose one at the start of each plan:

- **`[Linear]`** — Uses Linear issues and projects. Requires a Linear account and the Linear MCP server configured in Claude Code.
- **`[Local]`** — Uses a local XML file at `.agents/plans/plan-{N}-tasks.xml`. No external services required. Requires the plugin's SessionStart hook to have run (downloads the Java CLI runtime to `~/.cache/shipsmooth/`).

Throughout this skill, instructions marked `[Linear]` apply only in Linear mode; instructions marked `[Local]` apply only in Local mode. Unmarked instructions apply to both.

`[Local]` Script invocations use `${model.cliBin()} <subcommand>`. All scripts read/write `.agents/plans/plan-{N}-tasks.xml` relative to the repo root.

---

## Control Strategy: The Risk-Quality Loop

To maximize productivity while minimizing "hallucination drift," apply an adaptive control strategy.

**The Control Equation:**
$$u[k] = K_S(R) \cdot \frac{\Delta e[k]}{T_s} + K_Q \cdot \frac{e[k]}{T_s}$$

- **$K_S(R)$ (Spiral Risk Gain):** Sensitivity to architectural or logic drift. High when risk is unknown.
- **$K_Q$ (Implementation Quality Gain):** Sensitivity to code quality, readability, and test coverage.
- **$T_s$ (Sampling Interval):** Frequency of human intervention. Smaller $T_s$ = tighter control.

**Strategy:** De-risk aggressively first (High $K_S$, Low $K_Q$). Once logic is proven, harden the code (Low $K_S$, High $K_Q$).

---

## Repository Structure

```
.agents/
  plans/
    plan-07.md            # plan files live here, versioned in git
    plan-07-tasks.xml     # [Local] task state (sibling to plan file)
```

Plans are markdown files. They contain: narrative, design decisions, architecture notes, open questions, and references. Code never goes here.

---

## Git Tagging Convention

Every time a plan file is committed and pushed, immediately create and push a version tag:

```bash
# After committing a plan file change:
git tag plan-07-v1
git push origin plan-07-v1

# Subsequent revisions:
git tag plan-07-v2
git push origin plan-07-v2

# On clean completion:
git tag plan-07-complete
git push origin plan-07-complete

# On abandonment (tag the deletion commit too):
git tag plan-07-abandoned
git push origin plan-07-abandoned
```

Tag naming: `plan-{N}-v{version}` for iterations, `plan-{N}-complete` for clean closeout, `plan-{N}-abandoned` for abandonment.

### Automate with lefthook

Commit a hook so tagging fires automatically on every push, regardless of whether a human or agent made the commit:

```yaml
# lefthook.yml
pre-push:
  commands:
    auto-tag-plans:
      run: |
        # Detect if any .agents/plans/ file changed in the push
        if git diff --name-only HEAD~1 HEAD | grep -q '^\.agents/plans/'; then
          PLAN=$(git diff --name-only HEAD~1 HEAD | grep '^\.agents/plans/' | head -1)
          PLAN_ID=$(echo "$PLAN" | grep -oP 'plan-\d+')
          # Find next version number
          LATEST=$(git tag -l "${"${"}PLAN_ID}-v*" | sort -V | tail -1)
          if [ -z "$LATEST" ]; then
            NEXT="${"${"}PLAN_ID}-v1"
          else
            N=$(echo "$LATEST" | grep -oP '\d+$')
            NEXT="${"${"}PLAN_ID}-v$((N+1))"
          fi
          git tag "$NEXT"
          git push origin "$NEXT"
          echo "Auto-tagged: $NEXT"
        fi
```

Install lefthook if not present: `npm install -g lefthook && lefthook install`

---

## Linear Structure

`[Linear]` only. Skip this section in Local mode.

### Permanent Backlog Project
- Named e.g. `AppName — Backlog & Roadmap`
- Contains feature issues only
- Human-created and human-prioritised
- Never deleted, survives all plan lifecycles

### Transient Agent Projects
- Named: `[agent] {N} · {short-description}` e.g. `[agent] 07 · home-accounts-settings-bottom-tabs`
- Created per plan, archived after completion
- Project description must contain:
  - Link to the permanent backlog feature issue(s) it delivers
  - Permalink to the plan file using the tag-based commit hash URL (see below)
  - Brief plan narrative / design rationale

### Tag-based GitHub permalink format
```
https://github.com/{org}/{repo}/blob/{tag-commit-hash}/.agents/plans/plan-07.md
```

Resolve the commit hash for a tag:
```bash
git rev-list -n 1 plan-07-v1
```

Use this hash (not the tag name) in Linear links — it is immutable and survives branch deletion, rebases, and squash merges.

---

## Phase 1 — Plan, Calibrate, & Commit

**You do not write or run any implementation code during this phase.**

1. **Draft Plan:** Write or update the plan file at `.agents/plans/plan-{N}.md`.
2. **Risk Analysis:**
   - For every task in the plan, suggest a **Default Risk Level** (Low, Medium, or High) with a one-sentence justification.
3. **Collaborative Calibration:**
   - **Stop.** Ask the human: *"I've estimated these risk levels. Do you want to override any of them?"*
   - The human's choice becomes the **Actual Risk ($R$)**.
4. **Risk-Sorted Task Ordering:**
   - Re-order tasks in the plan file in **descending order of risk** ($High \to Med \to Low$).
   - *Exception:* If a Low-risk task is a hard technical dependency for a High-risk task, the dependency must come first.
5. **Commit & Tag:**
   ```bash
   git add .agents/plans/plan-07.md
   git commit -m "plan(07): risk-calibrated plan for [short-description]"
   git push origin t/{issue-id}-{short-description}
   # Lefthook auto-tags plan-07-v1 and pushes it
   ```
6. **Verify Preconditions:**
   ```bash
   git status                          # must be clean
   git log origin/t/{issue-id}-{short-description}..HEAD  # must be empty
   git tag -l "plan-07-v1"             # must exist
   git ls-remote origin "plan-07-v1"   # must be on remote
   ```
7. **Create Task Tracking Infrastructure:**
   - `[Linear]` Create the `[agent]` Linear project. Create Linear issues from the **risk-sorted** plan tasks. Each issue description must include the **Risk Level** ($L/M/H$) and the tag-based GitHub URL of the specific plan version that generated it.
   - `[Local]` Run `${model.cliBin()} init --plan {N} --tasks-from .agents/plans/plan-{N}.md` to generate `.agents/plans/plan-{N}-tasks.xml`. Commit the XML file immediately after creation. **Never hand-write this XML file — always generate it via the CLI. The format uses child elements, not attributes.** The CLI requires task headings in the form `### Task N: Name [Risk]` where `N` is a positive integer — alphanumeric IDs (e.g. `01-A`) are not supported. To express a dependency between tasks, add a `*Depends-on: P[,Q...]*` line anywhere in the task body before the next heading (e.g. `*Depends-on: 1,3*`). The CLI parses this line and writes `<depends-on>` into the XML automatically.
   - Organise tasks as **thin vertical slices** in both modes.
8. **Final Review & Go-ahead:**
   - `[Linear]` **Stop.** Post to the Linear project that the risk-sorted plan is ready for review.
   - `[Local]` **Stop.** Tell the human the XML task file has been committed and the plan is ready for review.
   - **Wait for explicit human go-ahead before proceeding to Phase 2.**

---

## Phase 2 — Execute

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
- Write at least one failing test that targets the core logic (preserving Core Invariant #6).
- Implement just enough to prove the approach works. Focus on the core complexity.
- Commit as `draft(N): de-risk [task name]`.
- `[Linear]` Post a comment on the Linear issue notifying the human the draft is ready.
- `[Local]` Run `${model.cliBin()} update-status --plan {N} --task {id} --status de-risked` and `${model.cliBin()} add-comment --plan {N} --task {id} --message "De-risk draft ready for review"`.
- **Wait for explicit approval of the approach.**

##### Step B: Hardening (Quality Phase)
- **Goal:** Achieve technical excellence, human readability, and coverage threshold.
- Refactor the de-risked code for readability, performance, and project patterns.
- Write full unit tests and ensure coverage meets the agreed threshold:
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
- `[Local]` Run `${model.cliBin()} update-status --plan {N} --task {id} --status agent-coded` and `${model.cliBin()} set-commit --plan {N} --task {id} --commit $(git rev-parse HEAD)`.

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
   - `[Local]` Run `${model.cliBin()} update-status --plan {N} --task {id} --status agent-coded` and `${model.cliBin()} set-commit --plan {N} --task {id} --commit $(git rev-parse HEAD)`. No draft review needed.

---

## Parallel Execution Protocol (optional)

The Lead Agent **may** delegate tasks to coding subagents instead of implementing them directly. This is opt-in — sequential single-agent execution is the default and always supported.

**When to use:** clearly-scoped, low-architectural-risk tasks where the scope is well-defined by the plan. Do **not** use for High-risk de-risk passes — those require human review after each iteration.

**Parallelism cap:** up to **3 subagents** in a single assistant turn (multiple `Agent` tool calls in one response).

### Dependency resolution

Tasks may carry a `<depends-on>` field in the XML (comma-separated parent task IDs). Before dispatching such a task:

1. Verify the parent task's `COMMIT_RECORDED` ledger event exists: `${model.cliBin()} worker-base --plan {N} --task {id}` — this prints the parent's commit SHA or exits 1 if the parent hasn't finished yet.
2. Pass that SHA as `--base` to `worker-init` so the worktree starts from the parent's commit, not repo HEAD.

A task with `<depends-on>` **must not** be dispatched in the same parallel batch as its parents — wait for the parent batch to complete first.

Tasks with no `<depends-on>` (or an empty value) fork from repo HEAD. **A task that has no `<depends-on>` but is itself a dependency of other tasks should be executed directly by the Lead Agent** (not dispatched as a subagent) — dispatch only starts once those prerequisite tasks are complete and their dependents can run in parallel.

### User consent (required before first parallel dispatch)

Before launching any subagents, ask the user:

> "ShipSmooth is about to launch parallel agents for Tasks {list} (prerequisite Tasks {prereq-list} will run first in this context). You will be asked for permission to read/write in `.agents/tasks/`. How would you like to proceed?
> 1. Yes, go ahead
> 2. No, don't use subagents"

If the user chooses **Yes**: patch `.claude/settings.json` in the target repo (see below), then proceed with the parallel dispatch.
If the user chooses **No**: execute all tasks sequentially in the main context window using the standard per-task loop instead. Do not dispatch any `Agent` tool calls.

**Worktree permission patch (required before first dispatch, reverted after last worker-cleanup):**

Subagents run in a fresh permission context and do not inherit the Lead Agent's session approvals. Without pre-approved paths, subagents will be blocked when they attempt to `Edit`/`Write` files in their worktree.

Before dispatching any subagent, patch `.claude/settings.json` in the **target repo** (not `~/.claude/settings.json`):

1. Read the file — if it does not exist, treat its current content as `{}`.
2. Merge the following entries into the `permissions.allow` array (do not overwrite unrelated keys):
   ```json
   "Edit(.agents/tasks/**)",
   "Write(.agents/tasks/**)",
   "Bash(cd .agents/tasks/** *)"
   ```
3. Write the file back. Tell the user: *"Adding temporary worktree permissions to .claude/settings.json so subagents can edit their worktree paths. These will be removed after integration completes."*

After **all** `worker-cleanup` calls have completed, revert:

1. Remove only the three entries added above from `permissions.allow`. If the array is now empty, remove it. If `permissions` is now empty, remove it. If the file was created from scratch (it did not exist before), delete it entirely.
2. Tell the user: *"Restored .claude/settings.json — temporary worktree permissions removed."*

### Per-task command sequence (run by the Lead Agent, not the subagent)

For tasks **without** `<depends-on>`:
```
1. ${model.cliBin()} claim --plan {N} --task {id}
2. WORKTREE=$(${model.cliBin()} worker-init --plan {N} --task {id})   # captures absolute worktree path
3. Agent tool call — fill {absolute-worktree-path} with $WORKTREE — see Worker Instruction Block below
4. ${model.cliBin()} worker-finish --plan {N} --task {id}             # captures diff, commits, records events
5. ${model.cliBin()} worker-cleanup --plan {N} --task {id}            # removes worktree dir, keeps branch
```

For tasks **with** `<depends-on>` (run after parent batch is complete):
```
1. ${model.cliBin()} claim --plan {N} --task {id}
2. BASE=$(${model.cliBin()} worker-base --plan {N} --task {id})       # resolve parent commit SHA
3. WORKTREE=$(${model.cliBin()} worker-init --plan {N} --task {id} --base "$BASE")
4. Agent tool call — fill {absolute-worktree-path} with $WORKTREE — see Worker Instruction Block below
5. ${model.cliBin()} worker-finish --plan {N} --task {id}
6. ${model.cliBin()} worker-cleanup --plan {N} --task {id}
```

**`$WORKTREE` is required input for the Agent call.** Never dispatch a worker without capturing this value first — the worker has no other way to know where its files are.

`worker-finish` aborts loudly if the subagent made any git commits inside the worktree (a contract violation). `worker-cleanup` removes the `.agents/tasks/{id}` directory but intentionally keeps the `agent-work/{id}` branch — that branch is the only input `integrate` needs. The disappearance of `.agents/tasks/{id}` before `integrate` runs is expected and correct.

### Integration step (mandatory after all worker-cleanup calls)

**When to run:** once every task in the batch has status `agent-coded` (confirmed via `${model.cliBin()} show --plan {N}`) and their `agent-work/{id}` branches exist (confirmed via `git branch -l 'agent-work/*'`).

**Before running integrate — probe the verify command:**

Run the verify command once manually in the repo root and confirm it exits 0 before passing it to `integrate`. This catches environment failures (missing Docker, missing credentials, pre-existing test failures) before they consume resolver iterations:

```bash
{your-test-command}   # must exit 0 before you proceed
```

If it fails for environment reasons (e.g. Docker not available), add the necessary exclusion flags now. Never pass a verify command that is already red.

**`-pl` flag warning (Maven projects):** The integration worktree (`.agents/integration/plan-{N}/`) is its own Maven reactor root — the command runs from inside it. Never use `-pl` with an absolute path (e.g. `-pl /abs/path/to/module`); Maven resolves `-pl` relative to the reactor root, so an absolute path breaks inside the worktree. Relative `-pl` submodule references (e.g. `-pl plugin-tasks-java`) work correctly. If your verify command currently uses an absolute `-pl`, drop the flag and run from the module directory instead, or use a relative path.

**File overlap warning:** Before running `integrate`, check which tasks touch the same files:

```bash
for branch in $(git branch -l 'agent-work/*' --format '%(refname:short)'); do
  echo "=== $branch ==="; git diff --name-only $(git merge-base HEAD $branch)..$branch
done
```

If two or more independent tasks (no `<depends-on>` between them) touch the same file, **expect a conflict** on that file. Brief the user before proceeding — the resolver will handle it, but manual resolution may be needed if the resolver exhausts its attempts.

**Conflict surface note:** Tasks that carry `<depends-on>` fork from their parent's commit rather than from HEAD, so they form a chain and integrate cleanly in dependency order — conflicts among them are rare by construction. Independent tasks (no `<depends-on>`, all forked from the same HEAD) are the conflict-prone set. The overlap-minimization ordering heuristic in `IntegrationOrder` applies primarily to these.

**Running integrate:**

`integrate` uses a stdin/stdout protocol: when a conflict or verify failure occurs it prints a JSON line to stdout and blocks waiting for `{"action":"continue"}` on stdin. This is incompatible with the blocking `Bash` tool — the command would run to completion before the Lead Agent can respond, closing stdin and aborting the resolver.

Use a **named pipe + background process + Monitor** instead:

```bash
# 1. Create a named pipe for replies and a log file for output
mkdir -p .agents/tmp
mkfifo .agents/tmp/integrate-stdin
touch .agents/tmp/integrate-stdout.log

# 2. Run integrate in the background, feeding stdin from the pipe
${model.cliBin()} integrate \
  --plan {N} \
  --task-branch $(git rev-parse --abbrev-ref HEAD) \
  --verify-cmd "{your-test-command}" \
  < .agents/tmp/integrate-stdin \
  >> .agents/tmp/integrate-stdout.log 2>&1 &
INTEGRATE_PID=$!

# 3. Open the write end of the pipe (keeps it open so integrate doesn't get EOF)
exec 9>.agents/tmp/integrate-stdin
```

Then arm a Monitor to watch for spawn-resolver events:

```bash
tail -f .agents/tmp/integrate-stdout.log | grep --line-buffered "spawn-resolver"
```

**When Monitor fires with a `spawn-resolver` line:**
1. Parse the JSON: extract `prompt` and `worktree`.
2. Perform an `Agent` tool call: `subagent_type: general-purpose`, prompt from the JSON, working directory = `worktree`. **Do not pass `isolation: worktree`.**
3. Write the continue reply to the pipe:
   ```bash
   echo '{"action":"continue"}' >&9
   ```
4. Resume watching Monitor for the next event or completion.

**When integrate exits** (Monitor goes quiet and `wait $INTEGRATE_PID` returns), close the pipe and clean up:

```bash
exec 9>&-
rm -f .agents/tmp/integrate-stdin
```

Check the exit code via `wait $INTEGRATE_PID` or by scanning `.agents/tmp/integrate-stdout.log` for the final line.

**On success:** `integrate` prints the integration tip SHA and fast-forward command to the log:

```bash
git merge --ff-only integration/plan-{N}
git push
```

Apply both, then proceed to Plan Closeout.

**On failure:** `integrate` exits non-zero and logs the failing task id. The integration branch is left in place for inspection. Report the task id and branch name to the human and stop — do not attempt manual merges.

**Do not manually merge `agent-work/*` branches or stash-pop changes** — that bypasses conflict detection and LLM-assisted resolution.

**Recovery — ledger wiped, worktrees gone:**

If `git reset --hard` was run before `integrate` and the ledger no longer contains `COMMIT_RECORDED` events for the tasks, `integrate` will find nothing to merge. To recover:

1. Detect the problem: `${model.cliBin()} ledger list` — if no `COMMIT_RECORDED` events appear for your tasks, the ledger was wiped.
2. For each affected task, find its commit SHA on the `agent-work/{id}` branch: `git rev-parse agent-work/{id}`.
3. Reconstruct the ledger event: `${model.cliBin()} ledger-record-commit --plan {N} --task {id} --commit {sha} --branch agent-work/{id}`
4. Repeat for all affected tasks, then re-run `integrate` normally.

Note: this recovery path requires the `agent-work/{id}` branches to still exist. If they were also deleted, restore them from the known commit SHAs via `git branch agent-work/{id} {sha}` before step 3.

### Worker Instruction Block

The Lead Agent pastes this verbatim into the `Agent` tool call's prompt, filling in the five `{...}` slots: `{task-id}`, `{task-name}`, `{absolute-worktree-path}`, `{N}` (plan number), `{task-markdown-slice}`, `{coverage-pct}`. **Do not pass `isolation: worktree` to the `Agent` tool** — `worker-init` already created a real git worktree; Claude Code's built-in isolation would create a second, hidden one and the subagent's edits would never be captured.

> **WORKER: Task {task-id} — {task-name}** (say this as your first output line so the user knows which task this agent is working on)
>
> You are a ShipSmooth coding worker. Your only job is to implement the task scope below and exit.
>
> **Pre-flight check (do this before anything else):**
> Run: `ls {absolute-worktree-path}` — if the directory is empty or does not exist, stop immediately with: `WORKER ABORT: worktree {absolute-worktree-path} is missing or empty — Lead Agent must run worker-init first.` Do not write any code.
>
> **Your working directory is `{absolute-worktree-path}`.** All file operations (Read/Edit/Write) must use absolute paths under that directory, and every Bash call must begin with `cd {absolute-worktree-path} &&`. Do not modify any file outside that directory.
>
> **If any tool call is denied by the permission system**, stop immediately and report: `WORKER BLOCKED: <tool> was denied — grant permission to proceed.` Do not retry the denied tool, do not attempt workarounds.
>
> **You are forbidden from running any git command.** No `git commit`, `git add`, `git checkout`, `git branch`, `git push`, `git worktree`, `git stash`, `git reset`. The Lead Agent handles all git operations via `${model.cliBin()}` after you exit. If you run any git command, the lifecycle will detect it and abort the task.
>
> **Task scope** (from `.agents/plans/plan-{N}.md`):
>
> {task-markdown-slice}
>
> **Coverage threshold:** {coverage-pct}%. Per Core Invariant #6, write at least one failing unit test before implementation, then implement until green and coverage passes.
>
> **When done, exit with a one-line summary** of files changed: `Modified: path/a, path/b. Added: path/c. Tests: path/test.`

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

## Plan Closeout

### Clean Completion
```bash
git tag plan-07-complete
git push origin plan-07-complete
```
- `[Linear]` Close all Linear issues in the `[agent]` project. Mark `[agent]` project complete and archive it. Update the permanent backlog feature issue to reflect delivery (link to completing PR, note what was delivered).
- `[Local]` Run `${model.cliBin()} project-update --plan {N} --status complete --message "Plan complete."`. Commit the final XML state. Update the permanent backlog feature issue (if tracked externally) or note delivery in the plan file.

### Completion with Loose Ends
- `[Linear]` Label unresolved issues `needs-triage`. Set `[agent]` project to **"In Review"**. Post a project update listing each open issue and why it's unresolved. Wait for human to review: they will promote worthy issues to the permanent backlog or discard them. Human marks the project complete and archives it.
- `[Local]` Run `${model.cliBin()} update-status --plan {N} --task {id} --status needs-triage` for each unresolved task. Run `${model.cliBin()} project-update --plan {N} --status in-review --message "..."`. Commit the XML. Wait for human to review.

### Abandonment
- Human commits a plan file deletion with a commit message referencing the superseding plan number
- You tag the deletion commit:
  ```bash
  git tag plan-07-abandoned
  git push origin plan-07-abandoned
  ```
- **Do not delete any earlier tags** (`plan-07-v1`, `plan-07-v2`, etc.) — they are the audit trail
- `[Linear]` Surface all open tasks for human triage. Migrate worthy tasks to the permanent backlog with a note: "Partial delivery — see plan-07-abandoned, superseded by plan-{M}". Archive the `[agent]` project with a closing note referencing the deletion commit hash and the superseding plan.
- `[Local]` Run `${model.cliBin()} project-update --plan {N} --status abandoned --message "Superseded by plan-{M}."`. Commit the final XML state.

---

## Audit Trail

`[Linear]` Record in every Linear issue:

| Event | What to store in the issue |
|---|---|
| Task created | `github.com/.../blob/{plan-07-v1-hash}/.agents/plans/plan-07.md` |
| Task closed / obsoleted | `github.com/.../blob/{plan-07-vN-hash}/.agents/plans/plan-07.md` + one-line reason |

`[Local]` The XML file is the audit trail. `<created-from>` and `<closed-at-version>` child elements on each `<task>` serve the same role. The XML is versioned in git, so `git diff` between two plan tags shows exactly what changed.

If the creation version equals the closeout version, the plan never changed during execution. If they differ, the git diff between the two tag hashes shows exactly what changed and why.

Feature issues in the permanent backlog should accumulate references to every plan that contributed to them — this gives a full delivery history across the feature's lifetime.

---

## What Lives Where — Quick Reference

| Content | Location | Reason |
|---|---|---|
| Plan narrative, design decisions, references | `.agents/plans/*.md` in git | Needs diffs, version history, co-evolution with code |
| Task state (done / not done) | `[Linear]` Linear `[agent]` project · `[Local]` `.agents/plans/plan-{N}-tasks.xml` | Needs status tracking and human review |
| Feature definitions | `[Linear]` Linear permanent backlog · `[Local]` Noted in plan file Context section | Permanent, human-curated |
| Link between plan version and tasks | `[Linear]` Tag-based GitHub permalink in Linear issue description · `[Local]` `<created-from>` child element in XML | Immutable, survives branch lifecycle |
| This workflow | `~/.claude/skills/start/SKILL.md` | Loaded by agent at task start |
| Repo-specific overrides | `CLAUDE.md` in repo root | Workspace name, project conventions, etc. |

---
