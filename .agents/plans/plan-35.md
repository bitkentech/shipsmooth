# Plan 35 — Coding Subagents in Isolated Worktrees (Phase 2)

## Context

Plan 34 landed the ledger substrate (`.agents/ledger.jsonl` + `.agents/objects/`) inside `plugin-tasks-java`. The skill currently executes tasks sequentially: the *main* Claude Code agent (the "Lead Agent") writes the code itself for each task on the task branch `t/{issue-id}-...`.

Plan 35 lets the Lead Agent **delegate coding to subagents**. Each subagent runs in its own git worktree under `.agents/tasks/{taskId}/` on its own branch `agent-work/{taskId}`. The Lead Agent can dispatch up to **3 subagents in parallel** (one assistant turn, multiple `Agent` tool calls).

**Out of scope for this plan: integrating the subagents' work back into the task branch.** Each subagent's branch is left in place after the worktree is torn down. A later "integration service" plan will consume the ledger + the surviving `agent-work/*` branches and apply them to the task branch. This plan only needs to make sure everything that integration would later need is recorded.

The reference implementation is `agent-simulator`'s `CodingAgent.java` and the worktree half of `GitWorktreeService.java` (`addWorktree`, `removeWorktree`, `commitAll`, `diff`, `headSha`, `gitGate`). We lift the worktree slice; we skip `applyPatch3way` and the integration service entirely.

Permanent backlog feature: *Parallel coding-subagent execution* (informal — matches the recent convention of an empty `<backlog-issue>`).

Mode: `[Local]`.

---

## Design summary

### CLI surface — four new top-level commands

The Lead Agent uses these. Subagents do not — their tool surface is plain Read/Edit/Write/Bash/Grep/Glob.

| Step | Command | What it does |
|---|---|---|
| Claim | `shipsmooth-tasks claim --plan {N} --task {id}` | Ledger event `AGENT_START` + acquires-and-releases the `gitGate` semaphore as a liveness check. No XML change. |
| Start worker | `shipsmooth-tasks worker-init --plan {N} --task {id}` | `git worktree add .agents/tasks/{id} -b agent-work/{id}` + `WORKTREE_CREATED` event. Prints the worktree absolute path on stdout (Lead Agent passes it to the subagent). |
| Finish worker | `shipsmooth-tasks worker-finish --plan {N} --task {id}` | Inside the worktree: `git add -A`, capture `git diff --cached` as a blob in `.agents/objects/`, `commitAll` to commit on `agent-work/{id}`. Records `PATCH_EMITTED` (diff blob SHA + bytes) and `COMMIT_RECORDED` (worktree branch HEAD SHA). Updates the XML task's `<commit>` field with that SHA. |
| Cleanup | `shipsmooth-tasks worker-cleanup --plan {N} --task {id}` | `git worktree remove --force .agents/tasks/{id}`. **Does NOT delete the `agent-work/{id}` branch** — the branch ref is left behind for the future integration service. Records `CLEANUP`. |

### Worktree lifecycle, end to end

For each task the Lead Agent decides to delegate:

```
claim → worker-init → spawn subagent (Agent tool, working dir = worktree path)
                    → subagent edits files in the worktree, exits
        worker-finish → captures diff + commits on agent-work/{id} + updates XML
        worker-cleanup → removes worktree dir, leaves branch ref
```

After cleanup, the worktree directory is gone but `git branch -a` still shows `agent-work/{id}` pointing at the subagent's commit.

### Parallelism

Hard cap: **3 subagents at once**. The Lead Agent chooses which tasks to dispatch in parallel and groups up to 3 into a batch. The skill text recommends — but does not enforce — keeping a parallel batch to tasks that don't touch the same files. Enforcement is unnecessary because (a) the patches are not integrated in this plan, and (b) `gitGate` already serialises the underlying git commands.

The 3-cap is just a number in the skill prose for now; no env var, no per-plan config. Phase 3 can add knobs.

### Subagent invocation — no plugin agent file, just a skill-embedded prompt

We deliberately do **not** ship a `coding-subagent.md` agent definition with the plugin. The Lead Agent invokes Claude Code's standard `Agent` tool (default `general-purpose` subagent type) and pastes a **Worker Instruction Block** that the SKILL provides verbatim. Reasons:

- Keeps all orchestration logic in one place (the SKILL), so the Lead Agent never has to choose between "use this agent type" vs. "follow these instructions."
- No plugin discovery / namespacing questions to debug.
- The `shipsmooth-tasks` CLI is already the source of truth for state; the subagent doesn't need a custom tool surface.

**Important:** the SKILL must instruct the Lead Agent **not** to pass `isolation: worktree` to the `Agent` tool. Claude Code's built-in `isolation: worktree` would create a second, hidden worktree — but we already created the real one via `worker-init`, and the subagent's edits must land there so `worker-finish` can capture the diff. The Worker Instruction Block tells the subagent the absolute path of *our* worktree and instructs it to operate inside it.

The Worker Instruction Block forbids git operations entirely. If a subagent ignores that and commits anyway, the `worker-finish` guard (see Task 5) catches it loudly.

### New event types

Add to `EventType` enum (currently 6 entries from plan-34): `AGENT_START`, `WORKTREE_CREATED`, `PATCH_EMITTED`, `CLEANUP`. Four new entries.

`COMMIT_RECORDED` already exists from plan-34 — `worker-finish` reuses it. We do **not** add `PATCH_INTEGRATED`, `INTEGRATION_FAILURE`, or `agent-failed` — those belong to the future integration plan.

### What the future integration service needs from this plan (so we don't paint it into a corner)

- `agent-work/{id}` branch refs survive cleanup. ✅
- Per-task ledger trace ends with `COMMIT_RECORDED { commit_sha: <worktree branch HEAD> }`. ✅
- Patch is also stored as a blob (`PATCH_EMITTED`'s `patch_blob_sha1`) as a belt-and-braces backup if the branch is later lost. ✅
- The XML `<commit>` field reflects the worktree branch HEAD so humans can `git checkout {sha}` to inspect. ✅

If all four hold, integration is just "for each task in plan order, `git cherry-pick agent-work/{id}` (or `git apply` from the blob) on the task branch." That logic does not need to exist now.

---

## Files affected

**Modified:**
- `plugin-tasks-java/.../tasks/ledger/EventType.java` — add four enum entries
- `plugin-tasks-java/.../tasks/TasksCli.java` — register four new subcommands
- `plugin-skill/src/main/jte-src/skills/SKILL.jte.md` — new block in Phase 2 Execute section explaining when/how to use subagents

**Added:**
- `plugin-tasks-java/.../tasks/git/WorktreeService.java` — lifted worktree slice of agent-simulator's `GitWorktreeService`, Spring-stripped
- `plugin-tasks-java/.../tasks/commands/ClaimCommand.java`
- `plugin-tasks-java/.../tasks/commands/WorkerInitCommand.java`
- `plugin-tasks-java/.../tasks/commands/WorkerFinishCommand.java`
- `plugin-tasks-java/.../tasks/commands/WorkerCleanupCommand.java`
- `plugin-tasks-java/src/test/java/.../git/WorktreeServiceTest.java`
- `plugin-tasks-java/src/test/java/.../commands/WorkerLifecycleTest.java` — end-to-end smoke

(No new plugin agent definition file — see "Subagent invocation" above.)

**Out of scope, deferred:**
- Patch integration into the task branch (`applyPatch3way`, conflict handling, `INTEGRATION_FAILURE`, `agent-failed` status).
- Headless integration worktree (`addWorktreeAt`, `resetToBaseline`).
- Configurable parallelism cap; backpressure beyond what `gitGate` already provides.
- A subagent that can spawn its own subagents.
- Branch garbage collection (the leftover `agent-work/*` refs accumulate until integration runs; manual `git branch -D` is fine).

---

## Tasks (risk-sorted, with dependency exceptions per skill Phase 1 step 4)

### Task 1: Extend EventType [Low]

Add `AGENT_START`, `WORKTREE_CREATED`, `PATCH_EMITTED`, `CLEANUP` to the enum. Trivial; no behaviour change. Existing tests should still pass.

*Order rationale:* hard dependency for Tasks 2, 3, 4, 6 — they emit these events.

### Task 2: Lift WorktreeService into plugin-tasks-java [Medium]

Copy the worktree slice of `agent-simulator`'s `GitWorktreeService` into `plugin-tasks-java/.../tasks/git/WorktreeService.java`. Strip `@Service`, `@Slf4j`, `@Qualifier`. Keep: `gitGate` (Semaphore(4)), `repoRoot`, `headSha`, `addWorktree`, `removeWorktree`, `commitAll`, `diff`. Drop everything else (`ensureRepo`, `ensureGitignore`, `addWorktreeAt`, `applyPatch3way`, `resetToBaseline`, `prune`, `writeObject`/`readObject` — the latter two already live in `ObjectStore`). Replace `@Slf4j` with `java.util.logging` or stderr. Unit test: round-trip on a temp git repo (add worktree → write file → diff → commitAll → remove worktree → assert branch ref still exists).

### Task 3: `worker-init` command [Low]

`WorkerInitCommand`: `--plan {N} --task {id}`. Calls `WorktreeService.addWorktree(".agents/tasks/{id}", "agent-work/{id}")`. Records `WORKTREE_CREATED` with `worktree_rel` and `branch` attributes. Prints the worktree's absolute path to stdout (Lead Agent captures this and passes it to the subagent as the working directory). Errors if the worktree dir or branch already exists.

*Order rationale:* hard dependency for Task 4's guard, which reads the `WORKTREE_CREATED` event.

### Task 4: `worker-finish` command (with anti-rogue-commit guard) [High]

`WorkerFinishCommand`: `--plan {N} --task {id}`.

**Guard first.** Before doing anything, compare the worktree's current `agent-work/{id}` HEAD SHA against the SHA recorded in this task's `WORKTREE_CREATED` ledger event. If they differ, the subagent has run git commands it was forbidden from running. Abort with a loud error:

```
worker-finish: subagent for task {id} created N commits in the worktree.
This violates the contract: subagents must not run git.
Recorded commits: <git log --oneline base..HEAD>
Aborting; no PATCH_EMITTED or COMMIT_RECORDED event written.
```

Exit non-zero. (No ledger event for the failure in this plan — Phase 3's integration plan owns failure events.)

**Happy path.** Inside `.agents/tasks/{id}`: run `git add -A`, capture `git diff --cached` (text), write the bytes to `ObjectStore` to get a blob SHA-1, then call `WorktreeService.commitAll(worktreeDir, "agent: task {id} - {task name}")` to commit on `agent-work/{id}`. Records `PATCH_EMITTED` (payload = diff text, attrs = `patch_blob_sha1` + `bytes`) and `COMMIT_RECORDED` (attrs = `commit_sha` = the worktree branch HEAD after commit). Updates the XML task's `<commit>` field with that SHA — this reuses the same XML mutation logic as the existing `set-commit` command (refactor it into a shared helper if it currently lives only in `SetCommitCommand`). Empty diff → exit non-zero with "subagent produced no changes".

### Task 5: `claim` command [Low]

`ClaimCommand` (picocli): `--plan {N} --task {id}`. Validates the task exists in the XML, acquires-and-releases `gitGate` (purely as a liveness check that nothing else in this process holds it indefinitely), records `AGENT_START` with `taskId` and the current HEAD SHA. No XML mutation. Exits 0 on success, non-zero if the task ID is unknown.

### Task 6: `worker-cleanup` command [Low]

`WorkerCleanupCommand`: `--plan {N} --task {id}`. Calls `git worktree remove --force .agents/tasks/{id}` (via the `WorktreeService` — add a `removeWorktreeKeepBranch(relativePath)` variant that drops the `git branch -D` step from the lifted `removeWorktree`). Records `CLEANUP` with the branch name still attached. Idempotent: missing worktree dir → log a warning, still record CLEANUP, exit 0.

### Task 7: Skill changes — Parallel Execution Protocol + Worker Instruction Block [Low]

In `SKILL.jte.md` Phase 2 Execute section, add a new subsection titled **"Parallel Execution Protocol (optional)"** between the per-task loop and the deviation handling. The subsection covers:

- **When to use.** The Lead Agent *may* delegate a task to a coding subagent instead of writing the code itself. Recommended for clearly-scoped, low-architectural-risk tasks. Not recommended for High-risk de-risk passes where the human is in the loop after each iteration. Sequential single-agent execution remains the default and is fully supported.
- **Parallelism cap.** Up to **3 subagents** may run in parallel — multiple `Agent` tool calls in a single assistant turn.
- **Per-task command sequence**, run by the Lead Agent (not the subagent):
  1. `${model.cliBin()} claim --plan {N} --task {id}`
  2. `${model.cliBin()} worker-init --plan {N} --task {id}` — capture stdout: the worktree's absolute path
  3. `Agent` tool call (default `general-purpose` subagent type), prompt = the Worker Instruction Block (below) with slots filled in. **Do not pass `isolation: worktree`** — we already provide a real worktree via step 2; Claude Code's built-in isolation would create a second, hidden one and the subagent's edits would never reach our worktree.
  4. `${model.cliBin()} worker-finish --plan {N} --task {id}` — captures the diff, commits on `agent-work/{id}`, records ledger events. Aborts loudly if the subagent ran git.
  5. `${model.cliBin()} worker-cleanup --plan {N} --task {id}` — removes the worktree dir; the `agent-work/{id}` branch ref is intentionally preserved for a future integration step.
- **Worker Instruction Block** (the Lead Agent pastes this verbatim into the `Agent` call's prompt, filling the four `{...}` slots):

  > You are a ShipSmooth coding worker for task {N}: {task-name}.
  >
  > **Your working directory is `{absolute-worktree-path}`.** All file operations (Read/Edit/Write) must use absolute paths under that directory, and every Bash call must `cd {absolute-worktree-path} && ...`. Do not modify any file outside that directory.
  >
  > **You are forbidden from running any git command.** No `git commit`, `git add`, `git checkout`, `git branch`, `git push`, `git worktree`, `git stash`, `git reset`. The Lead Agent handles all git operations via `shipsmooth-tasks` after you exit. If you run any git command, the lifecycle will detect it and abort the task.
  >
  > **Task scope** (verbatim slice of `.agents/plans/plan-{N}.md`):
  >
  > {task-markdown-slice}
  >
  > **Coverage threshold:** {coverage-pct}%. Per Core Invariant #6, write at least one failing unit test before implementation, then implement until green and coverage passes.
  >
  > **When done, exit with a one-line summary** of files changed in the form: `Modified: path/a, path/b. Added: path/c. Tests: path/test.`

### Task 8: End-to-end lifecycle test [Medium]

`WorkerLifecycleTest` in `plugin-tasks-java`: on a temp git repo, simulate the full sequence by invoking the four commands directly (`claim`, `worker-init`, then write a file inside the worktree path to mimic the subagent, then `worker-finish`, then `worker-cleanup`). Assert: ledger has the four events in order (`AGENT_START`, `WORKTREE_CREATED`, `PATCH_EMITTED`, `COMMIT_RECORDED`, `CLEANUP`), the XML task's `<commit>` field is populated, the worktree directory is gone, and `git branch --list agent-work/*` still shows the branch.

---

## Verification (end-to-end, after all tasks done)

1. `mvn -pl plugin-tasks-java test` passes.
2. On a scratch branch in this repo:
   - Build the new CLI; manually run `claim → worker-init → (touch a file in the worktree) → worker-finish → worker-cleanup` for two task IDs.
   - `shipsmooth-tasks ledger list` shows 10 events (5 per task) in interleaved order if run in parallel, or sequential order if not.
   - `git branch --list 'agent-work/*'` shows both branches.
   - `git worktree list` shows neither worktree.
   - `cat .agents/plans/plan-XX-tasks.xml` shows `<commit>` populated for both tasks.
3. Spawn an actual `coding-subagent` from a Claude Code session against a one-task scratch plan; verify the same end-state.

---

## Phase 3 preview (not in scope)

Integration service: consumes the ledger + the surviving `agent-work/*` branches, runs `git cherry-pick` (or `applyPatch3way` from the patch blob if the branch is gone) on the task branch in plan order, records `PATCH_INTEGRATED` / `INTEGRATION_FAILURE`, deletes integrated branches. Triggered either by a SubagentStop hook or a `shipsmooth-tasks integrate --once` command. Adds the `agent-failed` status for tasks that failed to integrate. Optional: configurable parallelism cap, per-task model selection, retry policy.
