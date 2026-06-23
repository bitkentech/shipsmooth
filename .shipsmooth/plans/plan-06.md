# plan-06 · Add branch creation step to Phase 2

## Feature reference
Backlog issue: TBD — create in code-flow Skill project before executing

## Narrative
SKILL.md references `{branch}` in git commands throughout Phase 1 and Phase 2 but never instructs the agent to create the branch. This causes agents to commit directly to `main`. The fix adds an explicit branch creation step at the start of Phase 2.

## Design decisions

**Where to add it:** First step of Phase 2, before the coverage threshold confirmation. This is the earliest possible point where code work begins.

**Branch naming convention:** `t/{issue-id}-{short-description}` — e.g. `t/pb-148-branch-creation-step`. The `t/` prefix stands for "task", a generic word covering features, bugs, chores, etc. Usernames are omitted; the task identity is what matters long-term.

**Also update `{branch}` placeholders:** Phase 1 (plan commit push) and Phase 2 (task commit push) both use a bare `{branch}` placeholder. Replace with the concrete `t/{issue-id}-{short-description}` pattern so the naming intent is clear.

**No tests required.** This is a documentation-only change to SKILL.md.

## Change

**File:** `/opt/workspace/code-flow/SKILL.md`

### 1. Add branch creation step at start of Phase 2

Insert before the current "Before writing any code, confirm the test coverage threshold..." line:

```
**Step 0: Create a branch**

Create and push a branch named after the primary Linear issue for this plan:
```bash
git checkout -b t/{issue-id}-{short-description}
# e.g. git checkout -b t/pb-148-branch-creation-step
git push -u origin t/{issue-id}-{short-description}
```
All task commits go on this branch. The `t/` prefix stands for "task" (covers features, bugs, chores, etc.). Usernames are intentionally omitted — the task identity is what matters long-term.
```

### 2. Update `{branch}` placeholders

- Phase 1, step 2 push command: replace `git push origin {branch}` with `git push origin t/{issue-id}-{short-description}`
- Phase 2, per-task loop step 4 push command: same replacement

## Open questions
None.
