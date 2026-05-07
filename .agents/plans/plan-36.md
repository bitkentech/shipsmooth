# Plan 36 — Integration of Parallel Subagent Branches (Phase 3)

## Context

Plan 35 landed parallel coding subagents. After it runs, the repo contains:

- One `agent-work/{id}` branch per delegated task, each pointing at a single commit forked from either repo HEAD or a parent task's commit (when `<depends-on>` was set).
- One `PATCH_EMITTED` blob per task in `.agents/objects/` (a textual diff, belt-and-braces backup).
- An XML task file with `<commit>` populated for each task and status `agent-coded`.
- A task branch `t/{issue-id}-...` that does **not** yet contain any of those commits.

Plan 35 explicitly deferred integration. Plan 36 picks it up.

The naive approach — `git merge agent-work/*` or sequential `git cherry-pick` — fails the moment two subagents edit the same file from the same base, which the ledger shows is common (e.g. plan-35's todo-1 run had Tasks 2 and 3 both editing `TaskStore.java` from the same SHA). Standard merge tools have no notion of *intent*; the ledger does. The strategy here is to put a small amount of LLM-mediated semantic merging behind a deterministic CLI surface, in a sandbox worktree that can be reset and retried.

Permanent backlog feature: *Parallel coding-subagent execution* (continuation; informal `<backlog-issue>`).

Mode: `[Local]`.

---

## Design summary

### Scope discipline (what this plan is NOT)

Per the critique that informed this plan, we deliberately avoid:

- **Premature CLI carving.** No `integration-start` / `-apply` / `-verify` triplet up front. One command does the loop end-to-end. Split only when we have observed which steps need to be re-runnable independently.
- **`git cherry-pick` as the integration primitive.** Subagent branches are designed as one logical patch per task. We use `git merge --squash` so the integration commit message can name the task and the commit graph stays linear on the integration branch.
- **XML "merge" logic.** The integration agent owns the final XML state and **regenerates** the `<commit>` field for each integrated task from the integration branch's actual commits. Per-agent ledger copies are advisory input only; we do not reconcile them.
- **Building the LLM-fix loop before we feel the failure modes.** Phase 3a (Tasks 1–6) ships the deterministic skeleton (sandbox worktree, ordering, sequential `merge --squash`, verify, give-up). Phase 3b (Tasks 7–9) layers on the LLM intervention for conflicts and post-merge breakage.

### The integration sandbox

A dedicated worktree at `.agents/integration/plan-{N}/` on a throwaway branch `integration/plan-{N}`. The integration agent operates entirely inside it. On give-up, the worktree is torn down and the throwaway branch is deleted; the task branch is never touched until success.

`integration/plan-{N}` is forked from the current tip of the task branch `t/{issue-id}-...`. That way, when integration succeeds, the task branch can fast-forward to the integration branch (or we cherry-pick the squashed commits over).

### Ordering strategy

Sequential `git merge --squash` of the `agent-work/*` branches in an order chosen up front. The order matters and is picked deterministically:

1. **Topological by `<depends-on>`** — a dependent task is always merged after its parents. (Hard constraint.)
2. **Within a topological layer, fewest-overlapping-files first.** Compute the file set each `agent-work/{id}` touches (via `git diff --name-only base..agent-work/{id}`) and prefer tasks that touch files no later task touches. This minimises late-arriving conflicts; the colliding tasks land last when the integration branch already has the most context.

The chosen order is recorded in a new `INTEGRATION_PLAN` ledger event before the first merge attempt, so a re-run can be audited or replayed.

### Per-task merge loop

For each task in the chosen order:

1. `git merge --squash agent-work/{id}` on the integration branch.
2. **If clean:** `git commit -m "task({id}): {task-name}"`. Run verify (compile + targeted test). On verify failure, jump to the LLM fix step (3b). On success, advance.
3. **If textual conflict:** invoke the LLM intervention block (3b) with the conflict markers, the task description from the plan markdown, and the `PATCH_EMITTED` event payload from the ledger. The LLM resolves the hunks, we stage and commit, then verify. If verify fails, the same LLM gets the test/compile output and is allowed to edit any file in the worktree — *not* just the conflicted hunks (the hard cases are semantic breakage, not textual). Up to **3 LLM iterations** per task before give-up.
4. **Give-up:** record `INTEGRATION_FAILURE` with task id, attempt count, and the final error. `git reset --hard` to the pre-merge SHA so the integration branch state is consistent. Stop the loop and surface to the human; do not silently skip.

### Verify step

The verify command is configurable per-plan via a `<verify-cmd>` element in the tasks XML (default: `mvn -pl plugin-tasks-java test` for this repo). The integration agent runs it in the worktree after every merge commit. Coverage threshold checks are intentionally deferred to the human review stage — they slow the loop without catching the failures that integration cares about (compile / test green).

### Failure budget

Hard caps, surfaced as flags on the integration command:

- `--max-llm-iterations` per task (default 3).
- `--max-total-failures` across the whole plan (default 1) — once exceeded, give up regardless of remaining tasks. Single failure = stop, because the more interesting signal is "show me the first hard one" not "grind through everything."
- No wall-clock cap in this plan; can add later if real runs prove pathological.

### LLM intervention as a subagent call

The integration agent **is** the Lead Agent driving the loop. For the LLM intervention step it spawns a `general-purpose` subagent with a carefully scoped prompt (conflict markers + task description + patch event + verify error, if any) and a working-directory restriction to the integration worktree. Same `Agent`-tool conventions as plan-35's worker block — no `isolation: worktree`, the worktree is real and pre-existing.

This keeps the resolver's context narrow and the Lead Agent's context clean. The resolver returns when it's edited the worktree to its satisfaction; the Lead Agent then runs verify and decides whether to iterate.

### XML ledger reconciliation: regenerate, don't merge

After all tasks integrate cleanly, the integration agent walks the integration branch's commit log, extracts the task id from each squash commit's message, and writes the resulting commit SHA into the task's `<commit>` field in the XML. Status remains `agent-coded` (integration is not a status change in this plan; the human PR review still happens). Any per-agent ledger copies in `agent-work/{id}` worktrees are ignored — they were already advisory.

A new `PATCH_INTEGRATED` event records `{ task_id, integration_commit_sha, agent_work_sha }` per integrated task. A new `INTEGRATION_COMPLETE` event wraps the run with the final tip SHA and the chosen order.

### CLI surface — one command, narrow flags

```
shipsmooth-tasks integrate --plan {N} \
    [--task-branch t/{name}] \
    [--max-llm-iterations 3] \
    [--max-total-failures 1] \
    [--verify-cmd "mvn -pl plugin-tasks-java test"]
```

- `--task-branch` defaults to the current branch.
- `--verify-cmd` defaults to the XML's `<verify-cmd>` element, else a per-repo fallback in a new `IntegrationDefaults` class.

That's it. No start/apply/verify split. If usage shows we need to resume mid-run, we'll add `--resume` later, backed by `INTEGRATION_PLAN` + `PATCH_INTEGRATED` events that are already written.

### Cleanup and outcome

- **Success:** the integration agent prints the integration branch's tip SHA. The Lead Agent (in the SKILL prose) is instructed to fast-forward the task branch to that SHA, then delete the integration worktree. The `agent-work/*` branches are deleted by the integration agent before exit.
- **Failure:** the integration worktree and `integration/plan-{N}` branch are left intact for human inspection. `agent-work/*` branches are also preserved. The human can `git checkout integration/plan-{N}` to see exactly where things stalled.

### What this plan does NOT do

- **Concurrent merge attempts.** Sequential only. Nothing about the failure modes here is parallel-friendly.
- **Order replanning on failure.** If task K fails, we don't reshuffle and retry — we stop. Replanning is a credible enhancement once we have data.
- **Rollback granularity finer than "abort the run."** No partial-success commits land on the task branch. Either the whole plan integrates or none of it does.
- **A `coding-subagent`-style plugin agent file.** The resolver subagent is invoked as `general-purpose` with an inline prompt, mirroring plan-35.

---

## Files affected

**Modified:**
- `plugin-tasks-java/.../tasks/ledger/EventType.java` — add `INTEGRATION_PLAN`, `PATCH_INTEGRATED`, `INTEGRATION_FAILURE`, `INTEGRATION_COMPLETE` (4 new entries).
- `plugin-tasks-java/.../tasks/TasksCli.java` — register `integrate` subcommand.
- `plugin-tasks-java/.../tasks/git/WorktreeService.java` — add `addWorktreeAt(String rel, String branch, String baseRef)` (no `-b` from HEAD, instead from a named ref) and `mergeSquash(File worktreeDir, String branch)` returning a `MergeResult { boolean clean, List<String> conflictedFiles }`. Also `resetHard(File worktreeDir, String sha)` for give-up rollback. **One new file** for the result record: `tasks/git/MergeResult.java`.
- `plugin-skill/src/main/jte-src/skills/SKILL.jte.md` — new "Integration" subsection in Phase 2.

**Added:**
- `plugin-tasks-java/.../tasks/integration/IntegrationOrder.java` — pure function: takes a list of tasks (id, depends-on, file-set) → returns ordered list. Topological + fewest-overlap.
- `plugin-tasks-java/.../tasks/integration/IntegrationDefaults.java` — small holder for verify-command default.
- `plugin-tasks-java/.../tasks/commands/IntegrateCommand.java` — picocli command; orchestrates the whole loop.

No automated tests in this plan. Verification is manual via a dev build (see Verification section).

**Out of scope, deferred:**
- `--resume` flag.
- Order replanning on failure.
- Parallel merge attempts.
- Coverage-threshold gating inside `integrate`.
- Any changes to the per-agent ledger copies (we keep ignoring them).

---

## Tasks (risk-sorted; dependency exceptions per skill Phase 1 step 4)

### Task 1: Extend EventType [Low]

Add `INTEGRATION_PLAN`, `PATCH_INTEGRATED`, `INTEGRATION_FAILURE`, `INTEGRATION_COMPLETE` to the enum. No behaviour change elsewhere.

*Order rationale:* hard dependency for Tasks 4, 5, 6.

### Task 2: WorktreeService merge primitives [Medium]

Add three methods:

- `addWorktreeAt(String relativePath, String branch, String baseRef)` — `git worktree add {rel} -b {branch} {baseRef}`. Differs from existing `addWorktree(String, String, String)` only in that `baseRef` is a named ref (e.g. a branch name like `t/pb-149-...`) rather than a commit SHA — internally identical, but the named entry point makes call sites read clearly.
- `mergeSquash(File worktreeDir, String branch) → MergeResult` — runs `git merge --squash {branch}`. On success returns `{clean=true, conflictedFiles=[]}`. On conflict, runs `git diff --name-only --diff-filter=U` to enumerate conflicted files, returns `{clean=false, conflictedFiles=...}`. Does **not** commit; the caller decides.
- `resetHard(File worktreeDir, String sha)` — `git reset --hard {sha}`. Used for per-task rollback on give-up.

### Task 3: IntegrationOrder pure function [Medium]

`IntegrationOrder.compute(List<TaskOrderInput>) → List<Integer>` where `TaskOrderInput` is `{ int id, List<Integer> dependsOn, Set<String> filesTouched }`.

Algorithm:
1. Topological sort by `dependsOn`. Cycle → throw with the cycle members.
2. Within each topological layer, sort by ascending count of `filesTouched ∩ unionOfLaterTasks`. (Tasks whose files no other task touches get merged first; the heaviest overlappers go last.)
3. Stable on ties, sorted by ascending `id` for determinism.

*Order rationale:* hard dependency for Task 6.

### Task 4: Resolver interface + stub [Low]

Add `tasks/integration/Resolver.java` interface:

```java
interface Resolver {
    void resolve(File worktreeDir, ResolverContext ctx) throws Exception;
}
record ResolverContext(int taskId, String taskName, String taskMarkdown,
                       String patchBlobSha, String diffText,
                       List<String> conflictedFiles, String verifyError) {}
```

One production implementation:
- `SubagentResolver` — **body intentionally minimal in this task**: assembles the prompt via `PromptBuilder`, invokes the `SubagentRunner` SPI with a single `run(String prompt) → void` method. The production binding writes a JSON-line "spawn payload" to stdout and blocks reading a `{"action":"continue"}` line from stdin. The harness driver (the Lead Agent) performs the `Agent` call and writes the reply. Mechanism details land in Task 7; this task only fixes the interface and the prompt builder.

No `StubResolver` — manual verification instead of automated tests.

Plus `tasks/integration/PromptBuilder.java`: pure function turning a `ResolverContext` into the full subagent prompt string (so we can unit-test the prompt without spawning anything). Includes the conflict markers, the task markdown slice from the plan, the diff, and (if present) the verify failure tail.

**Resolver scope in prompt:** The generated prompt must explicitly state that the resolver may rewrite any file in the integration worktree — not just the conflicted hunks — as long as the original intent of the task is preserved and all tests pass. Minimal edits are preferred but not required when the conflict is structural. This permission must appear in the prompt text, not be left implicit.

*Order rationale:* hard dependency for Task 6 and Task 7. Doing the interface alone is Low risk; the subagent-spawn mechanism is High and isolated to Task 7.

### Task 5: Ledger event payload helpers [Low]

In `LedgerService` (or wherever the existing event payload helpers live), add four narrow helpers:

- `recordIntegrationPlan(int planId, List<Integer> orderedTaskIds, String integrationBranch)`
- `recordPatchIntegrated(int planId, int taskId, String integrationCommitSha, String agentWorkSha)`
- `recordIntegrationFailure(int planId, int taskId, int attempts, String reason)`
- `recordIntegrationComplete(int planId, String tipSha, List<Integer> orderedTaskIds)`

Each one writes a typed `Event` with the matching `EventType`.

### Task 6: IntegrateCommand happy path — clean merges only [High]

`IntegrateCommand` with picocli flags from the design summary. Sequence:

1. Read XML for plan {N}; build `TaskOrderInput` list (id, depends-on, files touched per `git diff --name-only base..agent-work/{id}`).
2. Compute order via `IntegrationOrder.compute`.
3. Record `INTEGRATION_PLAN`.
4. Create integration worktree at `.agents/integration/plan-{N}/` on branch `integration/plan-{N}` forked from the task branch tip.
5. For each task in order: `mergeSquash`. If clean, commit with `task({id}): {task-name}`. Run verify-cmd. On clean+green, record `PATCH_INTEGRATED` and continue. On any failure in this task, record `INTEGRATION_FAILURE` and stop the loop (no LLM step yet — that's Task 7).
6. On loop end with all tasks integrated: walk commits, update XML `<commit>` per task, delete `agent-work/*` branches, record `INTEGRATION_COMPLETE`, print tip SHA. On loop abort: leave everything in place, exit non-zero.

De-risk pass: skip XML `<commit>` rewrite, skip `agent-work/*` deletion, skip `INTEGRATION_COMPLETE`. Just prove the worktree+merge+verify+ledger spine works on two non-overlapping tasks in a dev build.

Hardening pass: full XML rewrite, branch GC, `INTEGRATION_COMPLETE`, error messages, exit codes documented.

*Order rationale:* depends on Tasks 1, 2, 3, 5. Highest risk in the plan because it owns the orchestration logic and end-state correctness.

### Task 7: LLM resolver step — conflict path [High]

Wire `Resolver` into `IntegrateCommand`. On conflict from `mergeSquash`, build a `ResolverContext` (read the `PATCH_EMITTED` event's blob via `ObjectStore`, slice the task markdown from `.agents/plans/plan-{N}.md`), invoke the resolver, then `git add -A` + commit and run verify. If verify fails, build a new `ResolverContext` whose `verifyError` is populated and re-invoke. Up to `--max-llm-iterations` (default 3). On exhaustion, `resetHard` to pre-merge SHA, record `INTEGRATION_FAILURE`, exit per total-failure budget.

**Production `SubagentRunner` binding.** The integration command, when run by the Lead Agent, blocks on a single line of stdin per resolver invocation. The CLI prints a JSON line to stdout: `{"action":"spawn-resolver","prompt":"<full prompt>","worktree":"<absolute path>"}` and then reads a line from stdin. The Lead Agent (per SKILL prose) sees the line, performs the `Agent` tool call with that prompt and worktree, and writes `{"action":"continue"}\n` back. This keeps the JVM's role purely deterministic (state machine + git) and the LLM call exclusively in the harness.

*Order rationale:* depends on Tasks 4, 6.

### Task 8: SKILL prose update [Low]

In `SKILL.jte.md`, add a new "Integration" subsection after the "Parallel Execution Protocol" block in Phase 2. Covers:

- When to run integration: after all delegated tasks are `agent-coded` and their `agent-work/{id}` branches exist.
- The single command: `${model.cliBin()} integrate --plan {N}`.
- The Lead Agent's loop role: read JSON-line spawn requests on the command's stdout, perform `Agent` tool calls (default `general-purpose`, **no `isolation: worktree`**, working directory = the worktree path from the JSON), then write `{"action":"continue"}` back to stdin.
- Failure handling: `INTEGRATION_FAILURE` events are surfaced by exit code; the Lead Agent reports the failing task id and the integration branch name to the human and stops.
- Success closeout: fast-forward the task branch to the integration tip SHA printed on stdout, then `git push`, then proceed to Plan Closeout.
- **Conflict surface note:** Tasks that carry `<depends-on>` fork from their parent's commit rather than from HEAD, so they form a chain and integrate cleanly in dependency order — conflicts among them are rare by construction. Independent tasks (no `<depends-on>`, all forked from the same HEAD) are the conflict-prone set; the overlap-minimization ordering heuristic in `IntegrationOrder` applies primarily to these. The SKILL prose should make this explicit so the Lead Agent knows where to expect friction.

### Task 9: E2E manual verification run [Medium]

In a scratch repo (or reusing a plan-35 run), produce `agent-work/{1,2,3}` where at least two tasks conflict on the same file. Run `shipsmooth-tasks integrate --plan {N}` from a Claude Code session:

1. Observe the JSON-line spawn payload on stdout when the conflict hits.
2. Manually perform the `Agent` call with the payload's prompt and worktree path.
3. Write `{"action":"continue"}` back to stdin; watch the loop continue.
4. Confirm `INTEGRATION_COMPLETE` event in the ledger and XML `<commit>` values updated.

Document the run in a short note under `.agents/notes/plan-36-e2e.md` (committed).

*Order rationale:* last; depends on the entire stack landing.

### Task 10: Fix set-commit integration_mode for agent-work branches [Low]

`SetCommitCommand` unconditionally writes `integration_mode=direct` even when `--branch agent-work/{id}` is passed. `IntegrateCommand` reads this field and skips any task with `integration_mode=direct`, so manual ledger recovery via `set-commit` silently breaks integration.

**Fix:** in `SetCommitCommand.call()`, check if `branch` starts with `agent-work/`; if so, write `integration_mode=worktree`, otherwise `integration_mode=direct`.

File: `plugin-tasks-java/.../tasks/commands/SetCommitCommand.java`

### Task 11: Gitignore the ledger so git reset --hard cannot wipe it [Low]

`.agents/ledger.jsonl` has a `!` negation in both `.gitignore` and `plugin-tasks-java/.gitignore`, making it git-tracked. A `git reset --hard` restores the committed state and destroys uncommitted events (e.g. `COMMIT_RECORDED` written by `worker-finish`). This leaves `integrate` with nothing to merge.

**Fix:** remove the `!.agents/ledger.jsonl` negation from both `.gitignore` files so the ledger is never tracked or reset. The ledger is an append-only execution trace, not source of truth.

Files: `.gitignore`, `plugin-tasks-java/.gitignore`

### Task 12: Add ledger-record-commit recovery subcommand [Low]

When the ledger is wiped and `worker-cleanup` has already removed `.agents/tasks/{id}`, there is no way to reconstruct a `COMMIT_RECORDED` event. The only fallback (`set-commit`) is broken by Bug 1 (now fixed by Task 10), but even after that fix, a dedicated recovery command is safer and more explicit.

**Fix:** add `ledger-record-commit --plan {N} --task {id} --commit {sha} --branch agent-work/{id}` subcommand. It writes a `COMMIT_RECORDED` event with `integration_mode=worktree` directly to the ledger, bypassing XML. Register it in `TasksCli.java`.

Files: `plugin-tasks-java/.../tasks/commands/LedgerRecordCommitCommand.java` (new), `plugin-tasks-java/.../tasks/TasksCli.java`

### Task 13: SKILL — verify cmd -pl guidance + ledger recovery instructions [Low]

Two SKILL gaps exposed by the E2E run:

1. The "probe the verify command" block says to run it from repo root and add exclusion flags, but never says: **do not use `-pl` with an absolute path** — the integration worktree IS its own Maven root, so `-pl /abs/path` breaks inside it.
2. There are no recovery instructions for the "ledger wiped, worktrees gone" scenario.

**Fix:** in `SKILL.jte.md`, under the "Before running integrate — probe the verify command" block:
- Add an explicit warning: *"Never use `-pl` with an absolute path. The integration worktree is its own Maven reactor root; relative `-pl` submodule references work, absolute ones do not."*
- Add a **Recovery** subsection after the integration block covering: (a) how to detect a wiped ledger (`ledger list` shows no `COMMIT_RECORDED` events), (b) use `ledger-record-commit` per task to reconstruct, (c) re-run `integrate`.

File: `plugin-skill/src/main/jte-src/skills/SKILL.jte.md`

*Depends-on: 12*

### Task 14: Pre-approve worktree paths in target repo settings [Low]

Subagents launched via the `Agent` tool run in a fresh permission context and do not inherit the Lead Agent's session approvals. When worker subagents attempt to `Edit`/`Write` files under `.agents/tasks/{id}/` in the target repo, they are blocked and must stop.

**Fix:** update the SKILL's "User consent" block to instruct the Lead Agent to temporarily patch `.claude/settings.json` in the target repo before dispatching subagents, and revert it afterward. The patch:

1. **Before dispatch:** read the existing `.claude/settings.json` (create it as `{}` if absent). Merge in the worktree permission entries — do not overwrite unrelated keys. Tell the user: *"Adding temporary worktree permissions to .claude/settings.json so subagents can edit their worktree paths. These will be removed after integration completes."*
2. **After all `worker-cleanup` calls complete:** remove only the entries that were added (restore the file to its pre-patch state, or delete it if it was created from scratch). Tell the user: *"Restored .claude/settings.json — temporary worktree permissions removed."*

The permissions to add:

```json
{
  "permissions": {
    "allow": [
      "Edit(.agents/tasks/**)",
      "Write(.agents/tasks/**)",
      "Bash(cd .agents/tasks/**)"
    ]
  }
}
```

The SKILL must include:
- The exact JSON snippet.
- The read-merge-write pattern (not a blind overwrite).
- The create-if-absent step.
- The user-facing messages at both patch and restore points.
- A note that this is scoped to the target repo's `.claude/settings.json`, not `~/.claude/settings.json`.

File: `plugin-skill/src/main/jte-src/skills/SKILL.jte.md`

### Task 15: SKILL — integrate resolver via background Bash + Monitor [Low]

The stdin/stdout resolver protocol requires the Lead Agent to watch a running process and write back mid-execution. This is incompatible with the blocking `Bash` tool: the command runs to completion before the Lead Agent can act, so `stdin` closes and the runner throws.

**Fix:** update the SKILL's "Running integrate" block to use a named pipe + background process pattern:

```bash
# Create a named pipe for the Lead Agent to write responses into
mkfifo .agents/tmp/integrate-stdin

# Run integrate in background, feeding stdin from the pipe
~/.cache/shipsmooth-dev/runtime-0.2.0/bin/shipsmooth-tasks integrate \
  --plan {N} --task-branch $(git rev-parse --abbrev-ref HEAD) \
  --verify-cmd "{your-test-command}" \
  < .agents/tmp/integrate-stdin \
  > .agents/tmp/integrate-stdout.log 2>&1 &
INTEGRATE_PID=$!

# Open the write end of the pipe (keeps it open so integrate doesn't get EOF)
exec 3>.agents/tmp/integrate-stdin
```

Then use `Monitor` to watch `.agents/tmp/integrate-stdout.log` for the `spawn-resolver` JSON line:

```bash
tail -f .agents/tmp/integrate-stdout.log | grep --line-buffered "spawn-resolver"
```

When Monitor fires with a `spawn-resolver` line: parse the `prompt` and `worktree` fields, perform the `Agent` tool call, then write the continue reply to the pipe:

```bash
echo '{"action":"continue"}' >&3
```

When `integrate` exits (Monitor sees no more output, or `wait $INTEGRATE_PID` completes), close the pipe and clean up:

```bash
exec 3>&-
rm -f .agents/tmp/integrate-stdin
```

The SKILL must include the full snippet verbatim (with `{N}` and `{your-test-command}` as fill-in slots), and note that `.agents/tmp/` must exist (it is gitignored by default in shipsmooth-managed repos).

File: `plugin-skill/src/main/jte-src/skills/SKILL.jte.md`

*Depends-on: 14*

*Note: Task 15's named-pipe approach is Linux/macOS only. Superseded by Tasks 16–18 which use the ledger as the coordination channel instead — portable across all platforms and automatically audited.*

### Task 16: Add RESOLVER_REQUESTED and RESOLVER_COMPLETE EventTypes [Low]

Add two entries to `EventType.java`:
- `RESOLVER_REQUESTED` — emitted by `integrate` when it needs the Lead Agent to spawn a resolver subagent. Payload: the full resolver prompt string. Metadata: `task_id`, `worktree`, `attempt`.
- `RESOLVER_COMPLETE` — emitted by the Lead Agent (via CLI) after the resolver subagent returns. Metadata: `task_id`.

File: `plugin-tasks-java/.../tasks/ledger/EventType.java`

### Task 17: Replace StdinStdoutSubagentRunner with LedgerSubagentRunner + ledger-resolver-complete subcommand [Medium]

Replace the stdin/stdout blocking protocol with a ledger-poll protocol. No named pipes, no fd manipulation — pure file append, works on all platforms.

**`LedgerSubagentRunner`** (replaces `StdinStdoutSubagentRunner`):
- `run(String prompt)`: writes a `RESOLVER_REQUESTED` event to the ledger (payload = prompt, metadata includes `worktree`, `attempt` counter, `task_id`).
- Then polls `ledger.findLastEvent(taskId, RESOLVER_COMPLETE)` in a loop (100 ms sleep) until a matching event appears or `CONTINUE_TIMEOUT_MINUTES` (30) elapses.
- On timeout: throws `IllegalStateException` with a message explaining the Lead Agent must run `ledger-resolver-complete`.

`StdinStdoutSubagentRunner` can be deleted or kept as dead code — delete it.

**`LedgerResolverCompleteCommand`** (new CLI subcommand `ledger-resolver-complete`):
- Options: `--plan {N}`, `--task {id}`.
- Writes a `RESOLVER_COMPLETE` event to the ledger with `task_id` in metadata.
- Prints: `Resolver complete recorded for task {id}`.
- Register in `TasksCli.java`.

`IntegrateCommand` must pass `plan` and `taskId` into `LedgerSubagentRunner` so it can key the poll on the correct task.

Files:
- `plugin-tasks-java/.../tasks/integration/LedgerSubagentRunner.java` (new)
- `plugin-tasks-java/.../tasks/integration/StdinStdoutSubagentRunner.java` (delete)
- `plugin-tasks-java/.../tasks/commands/LedgerResolverCompleteCommand.java` (new)
- `plugin-tasks-java/.../tasks/TasksCli.java`
- `plugin-tasks-java/.../tasks/commands/IntegrateCommand.java`

*Depends-on: 16*

### Task 18: SKILL — rewrite integrate protocol to use ledger + Monitor [Low]

Replace the named-pipe block in Task 15 with the ledger-based protocol. No background process management needed — `integrate` can run as a normal blocking `Bash` call because it no longer blocks on stdin.

**Updated "Running integrate" SKILL block:**

Run integrate normally (blocking):
```bash
${model.cliBin()} integrate \
  --plan {N} \
  --task-branch $(git rev-parse --abbrev-ref HEAD) \
  --verify-cmd "{your-test-command}"
```

But first, arm a Monitor **before** running integrate, to watch for `RESOLVER_REQUESTED` events in the ledger:

```bash
tail -f .agents/ledger.jsonl | grep --line-buffered "RESOLVER_REQUESTED"
```

When Monitor fires with a `RESOLVER_REQUESTED` line:
1. Parse the JSON: extract `payload` (the prompt) and `metadata.worktree`.
2. Extract `metadata.task_id`.
3. Perform an `Agent` tool call: `subagent_type: general-purpose`, prompt from `payload`, working directory = `metadata.worktree`. **Do not pass `isolation: worktree`.**
4. After the Agent call returns, run: `${model.cliBin()} ledger-resolver-complete --plan {N} --task {metadata.task_id}`
5. Resume watching Monitor for the next event.

When `integrate` exits (the blocking Bash call returns), stop the Monitor.

On success: apply the fast-forward as printed by integrate. On failure: report task id and integration branch to the human.

The SKILL must note: arm Monitor **before** starting integrate so no event is missed in the window between process start and Monitor attach.

File: `plugin-skill/src/main/jte-src/skills/SKILL.jte.md`

*Depends-on: 17*

---

## Verification (manual, via dev build)

Build the CLI and run against a scratch scenario:

1. Produce `agent-work/*` branches per plan-35 (or manually): at least two that edit the same file from the same base commit.
2. Run `shipsmooth-tasks integrate --plan {N}`.
3. **Happy path (clean merges):** `git log integration/plan-{N}` shows one squash commit per task in order; `shipsmooth-tasks ledger list` shows `INTEGRATION_PLAN` + N × `PATCH_INTEGRATED` + `INTEGRATION_COMPLETE`; XML `<commit>` values updated; `git branch --list 'agent-work/*'` is empty.
4. **Conflict path:** observe JSON-line spawn payload on stdout; perform `Agent` call manually; write `{"action":"continue"}` back; confirm loop continues and `PATCH_INTEGRATED` is recorded.
5. **Failure path:** deliberately corrupt a file so verify-cmd fails; confirm `INTEGRATION_FAILURE` event and integration branch left intact for inspection.

---

### Task 19: SKILL — Monitor is single-shot; add re-arm loop [Low]

The Monitor tool emits one batch of output and exits — it does not stay running indefinitely after the first event fires. The SKILL's step 4 says "Resume watching Monitor for the next `RESOLVER_REQUESTED` event", but the Monitor tool itself has exited by then. The Lead Agent must make a new Monitor tool call for each resolver cycle.

**Fix:** rewrite the Monitor instruction in `SKILL.jte.md` to make this explicit:
- Label the Monitor call "**Cycle N — arm Monitor**" (where N increments each cycle).
- After step 4 ("resume watching Monitor"), clarify: *"Arm Monitor again (a new Monitor tool call) before waiting for the next event — Monitor is single-shot and exits after emitting."*
- Add a note: *"There is one Monitor call per resolver cycle. N cycles = N Monitor tool calls."*

The instruction block currently says "Step 1 — arm Monitor before starting integrate". That label implies it is a one-time setup. Change it to "**Before each resolver cycle — arm Monitor**" (or similar) so it is clear the call repeats.

File: `plugin-skill/src/main/jte-src/skills/SKILL.jte.md`

*Depends-on: 18*

### Task 20: SKILL — handle ledger non-existent at Monitor arm time [Low]

`tail -f .agents/ledger.jsonl` exits immediately (exit 1) if the file does not exist. When the Lead Agent arms Monitor before `integrate` has started (as instructed), the ledger may not yet exist — `integrate` creates it on first write. The Monitor exits before `integrate` writes anything, so the first `RESOLVER_REQUESTED` event is missed.

**Fix:** prepend `touch .agents/ledger.jsonl &&` to the Monitor command so the file exists before `tail -f` opens it:

```bash
touch .agents/ledger.jsonl && tail -f .agents/ledger.jsonl | while IFS= read -r sha; do
  dir=$(echo "$sha" | cut -c1-2)
  rest=$(echo "$sha" | cut -c3-)
  f=".agents/objects/$dir/$rest"
  [ -f "$f" ] && grep -q "RESOLVER_REQUESTED" "$f" && cat "$f"
done
```

File: `plugin-skill/src/main/jte-src/skills/SKILL.jte.md`

*Depends-on: 18*

## Bugs found during plan-11 E2E runs (2026-05-06)

Six issues surfaced during two E2E runs against plan-11 (XML/YAML TaskStore backends). Tracked as Tasks 10–15.

### Bug 1 — `set-commit --branch agent-work/*` always writes `integration_mode=direct`

`SetCommitCommand` unconditionally writes `integration_mode=direct` to the ledger even when `--branch agent-work/{id}` is passed. `IntegrateCommand` reads this field and skips any task with `integration_mode=direct`. Result: manually recovering a wiped ledger via `set-commit` causes `integrate` to silently skip those tasks.

**Fix:** when `--branch` starts with `agent-work/`, write `integration_mode=worktree` instead.

### Bug 2 — Ledger wiped by `git reset --hard`

`.agents/ledger.jsonl` is git-tracked (`!.agents/ledger.jsonl` in `.gitignore`). A `git reset --hard` restores it to the committed state, destroying any uncommitted events (e.g. `COMMIT_RECORDED` events written by `worker-finish` after the last commit). This leaves `integrate` with no `COMMIT_RECORDED` events and nothing to merge.

**Fix:** gitignore the ledger (remove the `!` negation) so it is never reset. The ledger is an append-only execution trace, not source of truth — it should survive resets.

### Bug 3 — `worker-finish` requires worktree to reconstruct ledger

If the ledger is wiped and `worker-cleanup` has already removed `.agents/tasks/{id}`, there is no way to re-run `worker-finish` to reconstruct the `COMMIT_RECORDED` event. The only recovery is manual `set-commit` calls, which are broken by Bug 1.

**Fix:** add a `ledger-record-commit --plan {N} --task {id} --commit {sha} --branch agent-work/{id}` subcommand for emergency recovery that writes a proper `COMMIT_RECORDED` event with `integration_mode=worktree`.

### Bug 4 — Verify command `-pl` flag breaks inside integration worktree

The integration worktree (`.agents/integration/plan-{N}/`) is itself the Maven project root. Passing `-pl /path/to/repo` to `mvn` inside this worktree fails because `-pl` is relative to the reactor root, not an absolute override. The resolver cannot fix this — it is a configuration error.

**Fix (SKILL):** document that the verify command must work when invoked from the integration worktree root. Never use `-pl` with an absolute path. Recommend testing the verify command from `.agents/integration/plan-{N}/` before the first `integrate` call (the integration worktree must be created first, or test from repo root without `-pl`).

## Bugs found during plan-36 E2E run (2026-05-07)

Two issues surfaced during the E2E run (Task 9) against plan-11 (XML/YAML TaskStore). Tracked as Tasks 19–20.

### Bug 5 — Monitor is single-shot; must be re-armed each resolver cycle

The Monitor tool emits output and then exits — it does not remain open. The SKILL step 4 says "resume watching Monitor" but does not clarify that this requires a new Monitor tool call. During the E2E run, three separate Monitor tool calls were needed across the session because each one exited after firing.

**Fix (Task 19):** update SKILL to explicitly label each Monitor call as per-cycle and state that re-arming = a new Monitor tool call.

### Bug 6 — `tail -f` on ledger exits if file doesn't exist yet

When Monitor is armed before `integrate` has started, `.agents/ledger.jsonl` may not yet exist. `tail -f` on a non-existent file exits immediately (exit 1), causing the Monitor to exit before `integrate` writes the first `RESOLVER_REQUESTED` event. The Lead Agent misses that first event.

**Fix (Task 20):** prepend `touch .agents/ledger.jsonl &&` to the Monitor command so the file always exists before `tail -f` opens it.

## Phase 4 preview (not in scope)

- `--resume` from the last `PATCH_INTEGRATED` event.
- Order replanning when a task fails (try a different position before giving up).
- Promoting integration into a SubagentStop hook so it runs without an explicit Lead Agent command.
- Coverage-threshold checks during integrate (currently human-review only).
- A first-class `coding-resolver` plugin agent definition with a tighter tool surface than `general-purpose`.
