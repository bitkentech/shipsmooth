## shipsmooth - a simple, powerful coding workflow.

An AI assistant plugin that enables a plan-driven, risk-prioritised, checkpoint based, agentic coding workflow.

The below demo shows one example flow: **Plan ➔ Generate tasks ➔ Execute ➔ Something went wrong! ➔ Pause execution ➔ Update plan (and tasks) ➔ Resume execution ➔ Stop session ➔ Resume next day!**
![shipsmooth demo](docs/demo-small.gif)

## How to use the workflow

Load [the skill](https://github.com/bitkentech/shipsmooth/blob/releases/dist/skills/start/SKILL.md) as `/shipsmooth:start`. Discuss your work with the agent. The skill will guide you along these steps.

1. **Plan** - After discussion, a plan file is created and committed  (.agents/plans/plan-{N}.md). It will have a list of tasks in it.
2. **Calibrate** - You can override the default risk level (High/Medium/Low) for each task. The riskiest work will be executed first.
3. **Execute** - The agent works through tasks in order. High-risk tasks go through a de-risk/harden cycle (prove the approach first, then polish). Low-risk tasks are single-pass.
4. **Close out** - Tag the plan complete, archive the task state, and squash merge to main.

You can read the full workflow at [SKILL.md]().

## Features (aspirations?) of the workflow

The [workflow spec](https://github.com/bitkentech/shipsmooth/blob/releases/dist/skills/start/SKILL.md) borrows ideas from the [Spiral Model](https://en.wikipedia.org/wiki/Spiral_model) and [Agile](https://en.wikipedia.org/wiki/Agile_software_development) principles. You first de-risk any feature work by tackling the unknown parts - sometimes that's the end-user experience, at other times it could be technical components. Only after the approach has been validated by implementing the risky parts, you pick up the low risk tasks and focus on code quality, test coverage.

- **Plan-driven execution.** Every feature has a plan file checked into version control ([an example plan file](.agents/plans/plan-17.md)), and the agent executes against this plan. The plan is broken down into tasks ([an example tasks file](.agents/plans/plan-17-tasks.xml)). You can pause the execution any time, modify the plan and resume.
- **Risk-first ordering and vertical slices.** Planned tasks are ranked as High, Medium, or Low risk. High-risk tasks are tackled first so that you can validate important assumptions and fail fast. As far as possible, the tasks will represent vertical slices of functionality. Git commits are created as progress is made.
- **Code quality and test coverage.** These are increased only after the approach is proven and a basic implementation is ready.
- **Pause and resume.** Plan files and task state live in git, so you can stop your development session and restart from exactly where you left off.
- **Local task tracking.** Task management is via text files checked in alongside the plan (`.agents/plans/plan-{N}-tasks.xml`). No external services required (but [Linear](https://linear.app) integration is currently available).

## Current Limitations
- Support for concurrent "plans" doesn't exist. Each new plan relies on generating an incrementing serial number right now. That can be an issue if you choose to create multiple plans at one time.
- There are no team-specific features. Usage assumes it's a solo developer working on one feature at a time.

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

### Codex CLI

First, register the marketplace (one-time setup):
```bash
codex plugin marketplace add bitkentech/codex-plugins
```

Then install the plugin and restart Codex:
```bash
codex plugin add shipsmooth@bitkentech
```

The workflow loads as the `start` skill (run `/skills` to confirm it is enabled).

On first session start, a `SessionStart` hook downloads the self-contained Java
runtime into `~/.cache/shipsmooth/` (`%LOCALAPPDATA%\shipsmooth\` on Windows).

No Node.js or JDK is required: on macOS/Linux the bootstrap is a small POSIX shell
script (`install-shipsmooth.sh`) that uses only tools present on a stock system
(`sh`, `curl`, `unzip`), and on Windows it is a `.bat`. The downloaded runtime is a
jlink image, so there is nothing else to install.

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

## Development  
See [DEVELOPMENT.md](DEVELOPMENT.md) for build instructions, repo structure, and dev setup.

## Experimental features
Some features are experimental. See [EXPERIMENTAL.md](EXPERIMENTAL.md).

