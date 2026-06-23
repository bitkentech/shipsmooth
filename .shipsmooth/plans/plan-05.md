# plan-05 · Update README to reflect completed TODO items

## Feature reference
Backlog issue: [PB-146](https://linear.app/pb-default/issue/PB-146/update-readme-to-reflect-completed-todo-items)

## Narrative
The README.md uses `TODO:` prefixes to track workflow assumptions that are aspirational vs. implemented. As plans land, the README should be kept current. This plan addresses the one TODO that is now done: testing integration (delivered by plan-02).

Two TODOs remain open and are intentionally left as-is:
- "Enable the dev to choose the sequence of tasks..." — not yet designed
- "Rename plan to spec, all across the skill?" — still undecided

## Design decisions

**Only mark what is verifiably done.** Plan-02 added TDD to SKILL.md (Core Invariant 6, Phase 2 preamble, per-task red/green loop). The README TODO for testing is unambiguously complete.

**Match style of line 8.** Line 8 was already converted from a TODO to a plain statement by plan-03 ("Each task is committed after tests pass..."). The updated line 6 should follow the same style.

**No tests required.** This is a one-line doc change. TDD does not apply.

## Change

**File:** `README.md`

Line 6 — before:
```
- TODO: Integrate testing as a fundamental part of the workflow
```

Line 6 — after:
```
- Testing is integrated as a fundamental part of the workflow — each task requires a failing test before implementation, and coverage is verified before committing
```

Lines 7 and 9 remain unchanged.

## Open questions
None.
