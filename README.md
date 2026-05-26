## shipsmooth - Agentic energy, channelled with skill.

An AI assistant plugin that enables a plan-driven, risk-prioritised, checkpoint based, agentic coding workflow.

The below demo shows one workflow: **Plan ➔ Generate tasks ➔ Execute ➔ Something went wrong! ➔ Pause execution ➔ Update plan (and tasks) ➔ Resume execution ➔ Stop session ➔ Resume next day!**
![shipsmooth demo](docs/demo-small.gif)

## Features (aspirations?) of the workflow

The workflow borrows ideas from the [Spiral Model](https://en.wikipedia.org/wiki/Spiral_model) and [Agile](https://en.wikipedia.org/wiki/Agile_software_development) principles. You first de-risk any feature work by tackling the unknown parts - sometimes that's the end-user experience, at other times it could be technical components. Only after the approach has been validated by implementing the risky parts, you pick up the low risk tasks and focus on code quality, test coverage.

- **Plan-driven execution.** Every feature has a plan file checked into version control ([an example plan file](.agents/plans/plan-17.md)), and the agent executes against this plan. The plan is broken down into tasks ([an example tasks file](.agents/plans/plan-17-tasks.xml)). You can pause the execution any time, modify the plan and resume.
- **Risk-first ordering and vertical slices.** Planned tasks are ranked as High, Medium, or Low risk. High-risk tasks are tackled first so that you can validate important assumptions and fail fast. As far as possible, the tasks will represent vertical slices of functionality. Git commits are created as progress is made.
- **Code quality and test coverage.** These are increased only after the approach is proven and a basic implementation is ready.
- **Pause and resume.** Plan files and task state live in git, so you can stop your development session and restart from exactly where you left off.
- **Local task tracking.** Task management is via text files checked in alongside the plan (`.agents/plans/plan-{N}-tasks.xml`). No external services required (but [Linear](https://linear.app) integration is currently available).

## Installation

### Claude Code (Linux / macOS)

First, register the marketplace (one-time setup):
```
/plugin marketplace add bitkentech/claude-plugins
```

Then install the plugin:
```
/plugin install shipsmooth@bitkentech
```

### Claude Code (Windows)

See the [shipsmooth-windows README](https://github.com/bitkentech/shipsmooth-windows) for Windows-specific installation instructions.

### Gemini CLI

```bash
gemini extensions install https://github.com/bitkentech/shipsmooth-gemini
```

On first session start, the hook installs dependencies and copies scripts into `~/.cache/shipsmooth/`.

## How to use the workflow

Load the skill as `/shipsmooth:start`. Start discussing the feature with Claude. The workflow will take you along these steps.

1. **Plan** - After discussion, a plan file is created and committed  (.agents/plans/plan-{N}.md). It will have a list of tasks in it.
2. **Calibrate** - You can override the default risk level (High/Medium/Low) for each task. The riskiest work will be executed first.
3. **Execute** - Work through tasks in order. High-risk tasks go through a de-risk/harden cycle (prove the approach first, then polish). Low-risk tasks are single-pass.
4. **Close out** - Tag the plan complete, archive the task state, and squash merge to main.

The full workflow specification lives in [SKILL.md](plugin-skill/src/main/jte-src/skills/SKILL.jte.md).

## Uninstall

To remove all traces of shipsmooth from a system:

```bash
# 1. Uninstall the Claude plugin
#    In Claude Code: /plugin uninstall shipsmooth

# 2. Remove the Java runtime and JIT cache
rm -rf ~/.cache/shipsmooth
```

The plugin leaves nothing in `~/.config`, `~/.local`, or any other location.
Per-repo `.agents/` directories contain your plan history and are yours to keep or remove as you see fit.

## Experimental

These features are in active development and may change.

### Java runtime for `shipsmooth-tasks`

Task tracking commands (`update-status`, `add-comment`, `set-commit`, etc.) are now backed by a jlink-packaged Java runtime rather than Node.js scripts. The runtime bundles a minimal JRE and is installed to `~/.cache/shipsmooth/runtime-{version}/` by the session-start hook. It starts in ~150 ms via OpenJ9's shared class cache and requires no separate JDK installation.

The CLI entry point is `shipsmooth-tasks` and exposes subcommands for every task-tracking operation. The module is at `plugin-tasks-java/`.

### Ledger-backed task tracking

Every mutating `shipsmooth-tasks` command now records an append-only event to `.agents/ledger.jsonl`, backed by a content-addressed object store at `.agents/objects/` (same layout as git's loose objects, SHA-1 keyed). The XML file remains the human-readable source of truth; the ledger is a machine-readable execution trace.

Ten event types are recorded: `TASK_REGISTRATION`, `STATUS_UPDATED`, `COMMENT_ADDED`, `DEVIATION_ADDED`, `COMMIT_RECORDED`, `PROJECT_UPDATE`, `AGENT_START`, `WORKTREE_CREATED`, `PATCH_EMITTED`, `CLEANUP`. Ledger writes are non-fatal — if a write fails after the XML mutation succeeds, the error is surfaced as a warning and execution continues.

Inspect the ledger with:
```bash
shipsmooth-tasks ledger list              # all events, newest last
shipsmooth-tasks ledger list --task 3     # filter by task id
shipsmooth-tasks ledger verify            # reconstruct timeline, exit non-zero on corruption
shipsmooth-tasks ledger read <sha>        # print JSON event blob (full or abbreviated SHA)
```

Both `.agents/ledger.jsonl` and `.agents/objects/` are tracked in git. `shipsmooth-tasks init` appends the necessary `.gitignore` entries idempotently.

### Parallel coding subagents

The Lead Agent can delegate tasks to coding subagents running in isolated git worktrees. Each subagent works in `.agents/tasks/{id}/` on its own `agent-work/{id}` branch. Up to 3 subagents may run in parallel (one assistant turn, multiple `Agent` tool calls).

The worktree lifecycle is managed by four new commands:

```bash
shipsmooth-tasks claim --plan N --task id          # acquire gitGate, record AGENT_START
shipsmooth-tasks worker-init --plan N --task id    # create worktree + branch, print path
# ... subagent edits files inside the worktree ...
shipsmooth-tasks worker-finish --plan N --task id  # stage, diff, commit, record events
shipsmooth-tasks worker-cleanup --plan N --task id # remove worktree dir, keep branch ref
```

`worker-finish` aborts loudly if the subagent ran any git commands (detected by comparing HEAD SHA against the `WORKTREE_CREATED` event). After cleanup, the `agent-work/{id}` branch ref is preserved for a future integration step.

Tasks with dependencies can use `<depends-on>` in the XML and `worker-init --base <sha>` to fork from a parent task's commit rather than repo HEAD:

```bash
BASE=$(shipsmooth-tasks worker-base --plan N --task id)   # resolve parent commit SHA
shipsmooth-tasks worker-init --plan N --task id --base "$BASE"
```

Patch integration (cherry-picking `agent-work/*` branches back onto the task branch) is deferred to a future plan.

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for build instructions, repo structure, and dev setup.

