# shipsmooth — Agentic energy, channelled with skill.

A Gemini CLI extension that enables a plan-driven, risk-prioritised, checkpoint based, agentic coding workflow.

## Features

The workflow borrows ideas from the [Spiral Model](https://en.wikipedia.org/wiki/Spiral_model) and [Agile](https://en.wikipedia.org/wiki/Agile_software_development) principles. You first de-risk any feature work by tackling the unknown parts - sometimes that's the end-user experience, at other times it could be technical components. Only after the approach has been validated by implementing the risky parts, you pick up the low risk tasks and focus on code quality, test coverage.

- **Plan-driven execution.** Every feature has a plan file checked into version control, and the agent executes against this plan. The plan is broken down into tasks. You can pause the execution any time, modify the plan and resume.
- **Risk-first ordering and vertical slices.** Planned tasks are ranked as High, Medium, or Low risk. High-risk tasks are tackled first so that you can validate important assumptions and fail fast. As far as possible, the tasks will represent vertical slices of functionality. Git commits are created as progress is made.
- **Code quality and test coverage.** These are increased only after the approach is proven and a basic implementation is ready.
- **Pause and resume.** Plan files and task state live in git, so you can stop your development session and restart from exactly where you left off.
- **Local task tracking.** Task management is via text files checked in alongside the plan (`.agents/plans/plan-{N}-tasks.xml`). No external services required (but [Linear](https://linear.app) integration is currently available).

## Installation

```bash
gemini extensions install https://github.com/bitkentech/shipsmooth-gemini
```

On first session start, the hook installs dependencies and copies scripts into `~/.cache/shipsmooth/`.

## How to use the workflow

Load the skill as `/shipsmooth:start`. Start discussing the feature with Gemini. The workflow will take you along these steps.

1. **Plan** - After discussion, a plan file is created and committed (`.agents/plans/plan-{N}.md`). It will have a list of tasks in it.
2. **Calibrate** - You can override the default risk level (High/Medium/Low) for each task. The riskiest work will be executed first.
3. **Execute** - Work through tasks in order. High-risk tasks go through a de-risk/harden cycle (prove the approach first, then polish). Low-risk tasks are single-pass.
4. **Close out** - Tag the plan complete, archive the task state, and squash merge to main.

## Development

Development for this extension happens at [bitkentech/shipsmooth](https://github.com/bitkentech/shipsmooth). See that repo's [DEVELOPMENT.md](https://github.com/bitkentech/shipsmooth/blob/main/DEVELOPMENT.md) for build instructions, the Gemini release process, and dev setup.

This repo (`bitkentech/shipsmooth-gemini`) is a release artifact — its contents are fully replaced on each release by `scripts/release-gemini.sh`.
