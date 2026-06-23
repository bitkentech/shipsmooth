# plan-01 · Add thin vertical slice guidance to SKILL.md

## Feature reference
Backlog issue: n/a (this repo is the skill itself; the change is to the skill's own documentation)

## Narrative
The current SKILL.md instructs the planning agent to create Linear issues from plan tasks, but gives no guidance on *how* to slice those tasks. Teams default to horizontal slicing (all DB work in one task, all API work in another, all UI work in another), which produces tasks that are hard to review, test, and ship incrementally.

This plan adds explicit guidance in Phase 1, step 5 to structure tasks as thin vertical slices — each covering UI, backend, business logic, and data model changes end-to-end — so every task delivers a small but independently testable piece of working behaviour.

## Design decision
Insert the guidance inline at Phase 1, step 5 rather than as a separate section, so it is read at the exact point the agent is creating issues.

## Change

**File:** `SKILL.md`

**Target:** Phase 1, step 5 (currently lines 143–144)

Replace:
```
5. Create Linear issues from plan tasks. Each issue description must include:
   - The tag-based GitHub URL that generated it (so it's traceable to the exact plan version)
```

With:
```
5. Create Linear issues from plan tasks. Organise tasks as **thin vertical slices** wherever possible — each task should have an end-to-end feel, covering a bit of UI, backend, business logic, and any corresponding data model changes together in one slice. Prefer multiple thin slices over splitting work by layer (e.g. avoid "all DB migrations" as one task and "all API endpoints" as another). Each issue description must include:
   - The tag-based GitHub URL that generated it (so it's traceable to the exact plan version)
```

## Open questions
None.
