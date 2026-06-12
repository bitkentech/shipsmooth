## Experimental

These features are in active development and may change.

### Java runtime for `shipsmooth`

Task tracking commands (`update-status`, `add-comment`, `set-commit`, etc.) are now backed by a jlink-packaged Java runtime rather than Node.js scripts. The runtime bundles a minimal JRE and is installed to `~/.cache/shipsmooth/{version}/` by the session-start hook. It starts in ~150 ms via OpenJ9's shared class cache and requires no separate JDK installation.

The CLI entry point is `shipsmooth` and exposes subcommands for every task-tracking operation. The module is at `plugin-tasks-java/`.

### Ledger-backed task tracking

Every mutating `shipsmooth` command now records an append-only event to `.agents/ledger.jsonl`, backed by a content-addressed object store at `.agents/objects/` (same layout as git's loose objects, SHA-1 keyed). The XML file remains the human-readable source of truth; the ledger is a machine-readable execution trace.

Ten event types are recorded: `TASK_REGISTRATION`, `STATUS_UPDATED`, `COMMENT_ADDED`, `DEVIATION_ADDED`, `COMMIT_RECORDED`, `PROJECT_UPDATE`, `AGENT_START`, `WORKTREE_CREATED`, `PATCH_EMITTED`, `CLEANUP`. Ledger writes are non-fatal — if a write fails after the XML mutation succeeds, the error is surfaced as a warning and execution continues.

Inspect the ledger with:
```bash
shipsmooth ledger list              # all events, newest last
shipsmooth ledger list --task 3     # filter by task id
shipsmooth ledger verify            # reconstruct timeline, exit non-zero on corruption
shipsmooth ledger read <sha>        # print JSON event blob (full or abbreviated SHA)
```

Both `.agents/ledger.jsonl` and `.agents/objects/` are tracked in git. `shipsmooth init` appends the necessary `.gitignore` entries idempotently.

### Parallel coding subagents

The Lead Agent can delegate tasks to coding subagents running in isolated git worktrees. Each subagent works in `.agents/tasks/{id}/` on its own `agent-work/{id}` branch. Up to 3 subagents may run in parallel (one assistant turn, multiple `Agent` tool calls).

The worktree lifecycle is managed by four new commands:

```bash
shipsmooth claim --plan N --task id          # acquire gitGate, record AGENT_START
shipsmooth worker-init --plan N --task id    # create worktree + branch, print path
# ... subagent edits files inside the worktree ...
shipsmooth worker-finish --plan N --task id  # stage, diff, commit, record events
shipsmooth worker-cleanup --plan N --task id # remove worktree dir, keep branch ref
```

`worker-finish` aborts loudly if the subagent ran any git commands (detected by comparing HEAD SHA against the `WORKTREE_CREATED` event). After cleanup, the `agent-work/{id}` branch ref is preserved for a future integration step.

Tasks with dependencies can use `<depends-on>` in the XML and `worker-init --base <sha>` to fork from a parent task's commit rather than repo HEAD:

```bash
BASE=$(shipsmooth worker-base --plan N --task id)   # resolve parent commit SHA
shipsmooth worker-init --plan N --task id --base "$BASE"
```

Patch integration (cherry-picking `agent-work/*` branches back onto the task branch) is deferred to a future plan.