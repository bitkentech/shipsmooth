@import io.bitken.ss.resources.PluginModel
@param PluginModel model

### Session-resume recovery

If `git worktree list` shows an `integration/plan-{N}` worktree from a prior session and `integrate` is not running, use this decision tree:

- **A stale `RESOLVER_REQUESTED` exists in the ledger with no matching `RESOLVER_COMPLETE`:**
  Check with: `${model.cliBin()} ledger list | grep RESOLVER_REQUESTED`. If found, integrate is dead and `ledger resolver-complete` has no process to unblock — do **not** call it. Instead use this 5-step manual recovery:
  1. Read the payload: find the event index in `${model.cliBin()} ledger list`, then read the blob from `.agents/objects/<prefix>/<rest>` (the blob SHA is in the event's payload field).
  2. Dispatch a resolver `Agent` call with that payload — it fixes the conflict markers in the integration worktree.
  3. Commit in the worktree: `cd .agents/integration/plan-{N} && git add -A && git commit -m "task({task_id}): {name} [resolved]"`.
  4. Record the integration from the **repo root**: `cd $(git rev-parse --show-toplevel) && ${model.cliBin()} --enable-experimental ledger record-patch-integrated --plan {N} --task {task_id} --commit $(git -C .agents/integration/plan-{N} rev-parse HEAD) --agent-work-sha $(git rev-parse agent-work/{task_id})`.
  5. Re-run `integrate` (background + Monitor) — the resume logic sees the `PATCH_INTEGRATED` event and skips the resolved task, continuing from the next one. **Note:** re-running integrate does not write a new `INTEGRATION_PLAN` event when the worktree is still present, so the recovery event remains visible to the resume check.

- **No pending resolver, but the integration branch is ahead of the task branch:**
  The prior session completed integration. Just fast-forward: `git merge --ff-only integration/plan-{N}` then `git push`. You're done.


---

## Parallel Execution Protocol (optional)

The Lead Agent **may** delegate tasks to coding subagents instead of implementing them directly. This is opt-in — sequential single-agent execution is the default and always supported.

**When to use:** clearly-scoped, low-architectural-risk tasks where the scope is well-defined by the plan. Do **not** use for High-risk de-risk passes — those require human review after each iteration.

**Parallelism cap:** up to **3 subagents** in a single assistant turn (multiple `Agent` tool calls in one response).

### Dependency resolution

Tasks may carry a `<depends-on>` field in the XML (comma-separated parent task IDs). Before dispatching such a task:

1. Verify the parent task's `COMMIT_RECORDED` ledger event exists: `${model.cliBin()} --enable-experimental worker base --plan {N} --task {id}` — this prints the parent's commit SHA or exits 1 if the parent hasn't finished yet.
2. Pass that SHA as `--base` to `worker init` so the worktree starts from the parent's commit, not repo HEAD.

A task with `<depends-on>` **must not** be dispatched in the same parallel batch as its parents — wait for the parent batch to complete first.

Tasks with no `<depends-on>` (or an empty value) fork from repo HEAD. **A task that has no `<depends-on>` but is itself a dependency of other tasks should be executed directly by the Lead Agent** (not dispatched as a subagent) — dispatch only starts once those prerequisite tasks are complete and their dependents can run in parallel.

### User consent (required before first parallel dispatch)

Before launching any subagents, ask the user:

> "ShipSmooth is about to launch parallel agents for Tasks {list} (prerequisite Tasks {prereq-list} will run first in this context). You will be asked for permission to read/write in `.agents/tasks/`. How would you like to proceed?
> 1. Yes, go ahead
> 2. No, don't use subagents"

@if(model.isCodex())
@template.shared.workflow.codex.permission-consent(model = model)
@elseif(model.isGemini())
@template.shared.workflow.gemini.permission-consent(model = model)
@else
@template.shared.workflow.claude.permission-consent(model = model)
@endif

### Per-task command sequence (run by the Lead Agent, not the subagent)

For tasks **without** `<depends-on>`:
@if(model.isCodex())
@template.shared.workflow.codex.task-command-sequence-independent(model = model)
@elseif(model.isGemini())
@template.shared.workflow.gemini.task-command-sequence-independent(model = model)
@else
@template.shared.workflow.claude.task-command-sequence-independent(model = model)
@endif

For tasks **with** `<depends-on>` (run after parent batch is complete):
@if(model.isCodex())
@template.shared.workflow.codex.task-command-sequence-dependent(model = model)
@elseif(model.isGemini())
@template.shared.workflow.gemini.task-command-sequence-dependent(model = model)
@else
@template.shared.workflow.claude.task-command-sequence-dependent(model = model)
@endif

@if(model.isGemini() || model.isCodex())
@template.shared.workflow.gemini.agent-instruction(model = model)
@else
@template.shared.workflow.claude.agent-instruction(model = model)
@endif 

`worker finish` aborts loudly if the subagent made any git commits inside the worktree (a contract violation). `worker cleanup` removes the `.agents/tasks/{id}` directory but intentionally keeps the `agent-work/{id}` branch — that branch is the only input `integrate` needs. The disappearance of `.agents/tasks/{id}` before `integrate` runs is expected and correct.

### Integration step (mandatory after all worker cleanup calls)

**When to run:** once every task in the batch has status `agent-coded` (confirmed via `${model.cliBin()} plan show --plan {N}`) and their `agent-work/{id}` branches exist (confirmed via `git branch -l 'agent-work/*'`).

**Before running integrate — probe the verify command:**

Run the verify command once manually in the repo root and confirm it exits 0 before passing it to `integrate`. This catches environment failures (missing Docker, missing credentials, pre-existing test failures) before they consume resolver iterations:

```bash
{your-test-command}   # must exit 0 before you proceed
```

If it fails for environment reasons (e.g. Docker not available), add the necessary exclusion flags now. Never pass a verify command that is already red.

**`-pl` flag warning (Maven projects):** The integration worktree (`.agents/integration/plan-{N}/`) is its own Maven reactor root — the command runs from inside it. Never use `-pl` with an absolute path (e.g. `-pl /abs/path/to/module`); Maven resolves `-pl` relative to the reactor root, so an absolute path breaks inside the worktree. Relative `-pl` submodule references (e.g. `-pl plugin-tasks-java`) work correctly. If your verify command currently uses an absolute `-pl`, drop the flag and run from the module directory instead, or use a relative path.

**File overlap warning:** Before running `integrate`, check which tasks touch the same files:

@if(model.isGemini() || model.isCodex())
@template.shared.workflow.gemini.file-overlap-check(model = model)
@else
@template.shared.workflow.claude.file-overlap-check(model = model)
@endif

If two or more independent tasks (no `<depends-on>` between them) touch the same file, **expect a conflict** on that file. Brief the user before proceeding — the resolver will handle it, but manual resolution may be needed if the resolver exhausts its attempts.

**Conflict surface note:** Tasks that carry `<depends-on>` fork from their parent's commit rather than from HEAD, so they form a chain and integrate cleanly in dependency order — conflicts among them are rare by construction. Independent tasks (no `<depends-on>`, all forked from the same HEAD) are the conflict-prone set. The overlap-minimization ordering heuristic in `IntegrationOrder` applies primarily to these.

**Verify scope:** The `--verify-cmd` you pass to `integrate` is the baseline. For each task's merge step, the integration agent will narrow the command to tests relevant to that task before running the full baseline. If `--verify-cmd` runs tests for features not yet integrated (e.g. YAML tests when only XML has landed), the narrowed command avoids spurious resolver cycles. The resolver prompt instructs the LLM to apply this narrowing — you do not need to pre-scope the command at plan-write time.

**Running integrate:**

**Never use `tail -f`, `sleep`, or polling loops to wait for integrate.** These are either blocked by the harness or leave orphaned background processes. The correct pattern is: arm Monitor (Step 1), then launch integrate in the background (Step 2). You will be notified by Monitor when a resolver cycle is needed and by the Bash background-complete notification when integrate finishes.

`integrate` coordinates with the Lead Agent via the ledger: when a conflict or verify failure occurs it writes a `RESOLVER_REQUESTED` event to `.agents/ledger.jsonl` and polls for a `RESOLVER_COMPLETE` event.

**Critical:** `integrate` must be run with `run_in_background: true` in the Bash tool. If run as a blocking Bash call, the Lead Agent cannot act on Monitor events while waiting for the command to finish — integrate will time out waiting for `ledger resolver-complete` that never comes.

**Monitor protocol — one call per resolver cycle:**

> **Important:** The Monitor tool is single-shot — it emits output once and exits. You need one Monitor tool call per resolver cycle. If integrate requests two resolver passes, you arm Monitor twice (once before each expected event).

**Step 1 — arm Monitor (Cycle 1) before starting integrate** (so no event is missed in the startup window):

First, snapshot the current event count so `ledger watch` ignores stale events from prior runs:
```bash
LEDGER_SEQ=$(${model.cliBin()} ledger list --count)
```

Then arm Monitor, passing `--after $LEDGER_SEQ`:

@if(model.isGemini() || model.isCodex())
@template.shared.workflow.gemini.ledger-watch-cmd(model = model)
@else
@template.shared.workflow.claude.ledger-watch-cmd(model = model)
@endif

`ledger watch` blocks until a `RESOLVER_REQUESTED` event appears in `.agents/ledger.jsonl`, prints its full JSON payload to stdout, and exits 0. It creates the ledger file if it does not yet exist, so it is safe to arm before `integrate` has started. Exit 1 means it timed out (default 30 minutes) without seeing an event.

@if(model.isCodex())
@template.shared.workflow.codex.background-execution(model = model)
@elseif(model.isGemini())
@template.shared.workflow.gemini.background-execution(model = model)
@else
@template.shared.workflow.claude.background-execution(model = model)
@endif

**When Monitor fires with a `RESOLVER_REQUESTED` line:**
1. Parse the JSON blob from the ledger entry — it contains `payload` (the resolver prompt) and `metadata` fields including `worktree` and `task_id`.
@if(model.isGemini() || model.isCodex())
@template.shared.workflow.gemini.agent-resolver-call(model = model)
@else
@template.shared.workflow.claude.agent-resolver-call(model = model)
@endif
@if(model.isGemini() || model.isCodex())
@template.shared.workflow.gemini.resolver-complete-cmd(model = model)
@else
@template.shared.workflow.claude.resolver-complete-cmd(model = model)
@endif
4. **Arm Monitor again (Cycle N+1)** — make a new Monitor tool call with the same command above (same `--after $LEDGER_SEQ` value) before waiting for the next event. Monitor has exited; you must re-arm it for each additional resolver cycle.

@if(!model.isGemini() && !model.isCodex())
**When the Bash background-complete notification for `integrate` arrives with exit code 0:** stop the active Monitor tool call immediately (via TaskStop) — do not wait for it to time out. `ledger watch` will eventually exit on its own when it sees the `INTEGRATION_COMPLETE` event, but only if Monitor was armed at that moment; if it was not re-armed yet, it will hang for the full 30-minute timeout. Stopping it explicitly on integrate exit-0 is the reliable fix.
@endif

Integrate will unblock within 500 ms of the `ledger resolver-complete` call and continue to the next task.

**On success:** `integrate` prints the integration tip SHA and the fast-forward command:

```bash
git merge --ff-only integration/plan-{N}
git push
```

Apply both immediately, then proceed to Plan Closeout.

**If `--ff-only` fails** ("not possible to fast-forward") — this happens when `integrate` committed a `chore(plan-{N}): update task commit SHAs` directly onto the task branch while building the integration branch independently from `agent-work/*` branches. Rebase instead:

```bash
git rebase integration/plan-{N}
git push
```

**On failure:** `integrate` exits non-zero and prints the failing task id. The integration branch is left in place for inspection. Report the task id and branch name to the human and stop — do not attempt manual merges.

**Do not manually merge `agent-work/*` branches or stash-pop changes** — that bypasses conflict detection and LLM-assisted resolution.

**Recovery — ledger wiped, worktrees gone:**

If `git reset --hard` was run before `integrate` and the ledger no longer contains `COMMIT_RECORDED` events for the tasks, `integrate` will find nothing to merge. To recover:

1. Detect the problem: `${model.cliBin()} ledger list` — if no `COMMIT_RECORDED` events appear for your tasks, the ledger was wiped.
2. For each affected task, find its commit SHA on the `agent-work/{id}` branch: `git rev-parse agent-work/{id}`.
3. Reconstruct the ledger event: `${model.cliBin()} --enable-experimental ledger record-commit --plan {N} --task {id} --commit {sha} --branch agent-work/{id}`
4. Repeat for all affected tasks, then re-run `integrate` normally.

Note: this recovery path requires the `agent-work/{id}` branches to still exist. If they were also deleted, restore them from the known commit SHAs via `git branch agent-work/{id} {sha}` before step 3.

### Worker Instruction Block

The Lead Agent pastes this verbatim into the agent tool call's prompt, filling in the five `{...}` slots: `{task-id}`, `{task-name}`, `{absolute-worktree-path}`, `{N}` (plan number), `{task-markdown-slice}`, `{coverage-pct}`. **Do not pass `isolation: worktree` if using Claude** — `worker init` already created a real git worktree; Claude Code's built-in isolation would create a second, hidden one and the subagent's edits would never be captured.

> **WORKER: Task {task-id} — {task-name}** (say this as your first output line so the user knows which task this agent is working on)
>
> You are a ShipSmooth coding worker. Your only job is to implement the task scope below and exit.
>
> **Pre-flight check (do this before anything else):**
> Run: `ls {absolute-worktree-path}` — if the directory is empty or does not exist, stop immediately with: `WORKER ABORT: worktree {absolute-worktree-path} is missing or empty — Lead Agent must run worker init first.` Do not write any code.
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
