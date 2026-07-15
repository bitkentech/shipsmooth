## shipsmooth - a simple, powerful coding workflow.

> Make plans, but with the freedom to change them. Tackle risky bits first. Build vertical slices.

An AI assistant plugin that enables a plan-driven, risk-prioritised, checkpoint based, agentic coding workflow. Technically, it is a skill file and a CLI tool, packaged as a plugin for Claude Code, Codex, Gemini CLI and OpenCode. Learn more at [shipsmooth.net](https://www.shipsmooth.net).

The below demo shows one example flow: **Plan ➔ Generate tasks ➔ Execute ➔ Something went wrong! ➔ Pause execution ➔ Update plan (and tasks) ➔ Resume execution ➔ Stop session ➔ Resume next day!**
![shipsmooth demo](docs/demo-small.gif)

## Features of the workflow

The [workflow spec](https://github.com/bitkentech/shipsmooth/blob/releases/dist/skills/start/SKILL.md) borrows ideas from the [Spiral Model](https://en.wikipedia.org/wiki/Spiral_model) and [Agile](https://en.wikipedia.org/wiki/Agile_software_development) principles. You first de-risk any feature work by tackling the unknown parts - sometimes that's the end-user experience, at other times it could be technical components. Only after the approach has been validated by implementing the risky parts, you pick up the low risk tasks and focus on code quality, test coverage. The website explores the three core concepts in depth: [Iterate](https://www.shipsmooth.net/concepts/iterate/), [Risky bits](https://www.shipsmooth.net/concepts/risky-bits/) and [Vertical slices](https://www.shipsmooth.net/concepts/vertical-slices/).

- **Plan-driven execution:** Every feature has a plan file checked into version control ([an example plan file](.shipsmooth/plans/plan-17.md)), and the agent executes against this plan. The plan is broken down into tasks ([an example tasks file](.shipsmooth/plans/plan-17-tasks.xml)). You can pause the execution any time, modify the plan and resume.
- **Risk-first ordering and vertical slices:** Planned tasks are ranked as High, Medium, or Low risk. High-risk tasks are tackled first so that you can validate important assumptions and fail fast. As far as possible, the tasks will represent vertical slices of functionality. Git commits are created as progress is made.
- **Code quality and test coverage:** These are increased only after the approach is proven and a basic implementation is ready.
- **Pause and resume:** Plan files and task state live in git, so you can stop your development session anytime and restart from exactly where you left off.
- **Non Intrusive:** By default, plans and tasks sit in a separate git folder/repository from your codebase and don't affect your codebase. But you can choose to keep them in a  `.shipsmooth` directory within your codebase.

## How to start using the workflow

First install shipsmooth (see **Installation** section below). Launch [the skill](https://github.com/bitkentech/shipsmooth/blob/releases/dist/skills/start/SKILL.md) as `/shipsmooth:start`. Discuss your work with the agent. The skill will guide you along these steps.

1. **Plan:** After discussion, a plan file is created and committed  (.shipsmooth/plans/plan-{N}.md). The plan file will contain all the high level design of your work. It will also have a list of tasks in it.
2. **Calibrate:** You can override the default risk level (High/Medium/Low) for each task. The riskiest work will be executed first.
3. **Execute:** The agent works through tasks in order. High-risk tasks go through a de-risk/harden cycle (prove the approach first, then polish). Low-risk tasks are single-pass.
4. **Close out:** Tag the plan complete, archive the task state, and squash merge to main.

Plan and task state is managed by a bundled CLI, documented at [shipsmooth.net/docs/cli](https://www.shipsmooth.net/docs/cli/).

## Current Limitations
- Support for concurrent "plans" doesn't exist. Each new plan relies on generating an incrementing serial number right now. That can be an issue if you choose to create multiple plans at one time.
- There are no team-specific features. Usage assumes it's a solo developer working on one feature at a time. You _can_ use it for multiple parallel features, you just have to tell it to create newer plan numbers (or just rename the plan files later).

## Installation

#### 1. Claude Code

First, register the marketplace (one-time setup):
```
/plugin marketplace add bitkentech/claude-plugins
```

Then install the plugin:
```
/plugin install shipsmooth@bitkentech
```

Restart Claude Code, then run `/shipsmooth:start` in the new session.

On Windows, see the [shipsmooth-windows README](https://github.com/bitkentech/shipsmooth-windows) for Windows-specific installation instructions instead.

#### 2. Gemini CLI

```bash
gemini extensions install https://github.com/bitkentech/shipsmooth-gemini
```

#### 3. Codex CLI

First, register the marketplace (one-time setup):
```bash
codex plugin marketplace add bitkentech/codex-plugins
```

Then install the plugin and restart Codex:
```bash
codex plugin add shipsmooth@bitkentech
```

The workflow loads as the `start` skill (run `/skills` to confirm it is enabled).

#### 4. OpenCode

Add the plugin to your `opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "plugin": ["@bitkentech/shipsmooth-opencode"]
}
```

Restart OpenCode. In the new session, type:

```
/shipsmooth:start
```

#### What gets installed

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

# 3. (optional) Remove the config directory holding shipsmooth.toml
rm -rf ~/.config/shipsmooth
```

It is recommended to retain your plan history (the `.shipsmooth/` directory within your codebase, or in a separate state directory if you have chosen that option) since that serves as documentation of the project.

## About

shipsmooth is in its early stages and is developed by [Pramod Biligiri](https://www.pramodb.com). The tech stack is mostly Java (a mini-runtime is bundled with the install), with a little bit of TypeScript and shell. Licensed under [Apache 2.0](LICENSE). More questions? See the [FAQ](https://www.shipsmooth.net/faq/).

## Development  
See [DEVELOPMENT.md](DEVELOPMENT.md) for build instructions, repo structure, and dev setup.

## Experimental features
Some features are experimental. See [EXPERIMENTAL.md](EXPERIMENTAL.md).

