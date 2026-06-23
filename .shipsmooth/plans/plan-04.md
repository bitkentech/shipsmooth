# plan-04 · Mark Linear issue "Agent Coded" after task commit

## Feature reference
Backlog issue: n/a (this repo is the skill itself; the change is to the skill's own documentation)

## Narrative
Phase 2's per-task loop commits the task and immediately opens a PR, with no intermediate status signal. A human reviewer has no way to tell from Linear alone that the agent has finished coding a task and it is awaiting review. A new "Agent Coded" status (type: started) has been added to the Linear workspace to mark this boundary.

This plan adds a step to set the Linear issue to "Agent Coded" immediately after the task commit and push, before the PR is opened. The PR open remains step 5, triggered (or confirmed) after human review.

## Design decisions

**Status set after push, not after coverage.** The "Agent Coded" signal is specifically about the Git state being ready — code committed and pushed. Setting it before push would be misleading.

**"Agent Coded" is a started-type status, not completed.** The task is not done until a human has reviewed and the PR is merged. This keeps the issue in an active state visible to the human.

**PR open stays as step 5.** The human may want to inspect the commit before a PR is opened. Keeping the PR open as a separate step preserves that optionality.

## Change

**File:** `SKILL.md` at `/opt/workspace/code-flow/SKILL.md`

In the per-task loop, append to step 4:

```
   Mark the Linear issue **Agent Coded**.
```

## Open questions
None.
