# plan-02 · Add TDD workflow to SKILL.md

## Feature reference
Backlog issue: n/a (this repo is the skill itself; the change is to the skill's own documentation)

## Narrative
The skill currently describes how to plan and execute code changes but gives no guidance on testing. The README explicitly lists "Integrate testing as a fundamental part of the workflow" as a TODO. This plan fulfils that TODO by embedding a TDD structure into Phase 2:

- At the start of Phase 2, the agent confirms the test coverage threshold with the human (default: 95%).
- Before any task code is written, 1–2 integration tests are written and committed in a failing (red) state.
- For each task, the unit test is written and committed red before the implementation begins, then the task is implemented until green, and coverage is verified before closing the Linear issue.

## Design decisions

**Coverage threshold at Phase 2 start, not Phase 1.** Keeping Phase 1 focused on plan structure and architecture. Coverage is an execution parameter confirmed once before any code is written, at the natural entry point of Phase 2.

**1–2 integration tests only.** Capping at two prevents over-investment in integration tests before core implementation exists. The cap is stated explicitly in the skill text because agents will otherwise write many more.

**Commit failing tests before implementation.** Committing the red state is auditable — git history proves tests existed before code, consistent with the existing principle that git is the source of truth.

**Coverage checked per-task, not at closeout.** Catching coverage regressions per-task is cheaper than a single end-of-plan check.

**New Core Invariant.** "Tests precede implementation" is the same category of rule as the other invariants — silent violation corrupts the outcome. Phrased as "as far as possible" to acknowledge that migrations, config, and similar artefacts cannot always be TDD'd.

## Change

**File:** `SKILL.md` (in this repo at `/opt/workspace/code-flow/SKILL.md`)

### Change 1 — New Core Invariant

After existing invariant 5 (`5. **Tags are permanent.**...`), add:

```
6. **Tests precede implementation.** Write integration test(s) before any task code (Phase 2 preamble), then the unit test for each task before its implementation. Never implement without a failing test already committed. (Apply as far as possible — migrations and config may not be TDD-able.)
```

### Change 2 — Replace Phase 2 body

Replace the current Phase 2 section with:

```markdown
## Phase 2 — Execute

**Before writing any code**, confirm the test coverage threshold with the human (default: 95%). Record the agreed value before proceeding.

### Preamble: integration tests (once, before any task)

1. Write 1–2 integration tests that exercise the feature end-to-end. No more than two.
2. Commit and push them with no implementation — they must fail (red). Reference the Linear project in the commit message.
3. Confirm red state:
   ```bash
   # run your project's test command, e.g.:
   npm test          # or: pytest, go test ./..., etc.
   ```
   If a test passes at this point, it is testing the wrong thing. Fix or discard it before continuing.

### Per-task loop (repeat for every Linear issue in sequence)

1. Write the unit test(s) for this task. Commit them failing (red).
2. Implement the task. Run tests until green.
3. Check coverage meets the agreed threshold:
   ```bash
   # example — adjust to your toolchain:
   npm test -- --coverage --coverageThreshold='{"global":{"lines":95}}'
   ```
   Do not close the Linear issue until coverage passes.
4. Open a PR referencing the Linear issue (e.g. `fixes TF-42`). Mark the issue **In Review**.

---

- **Minor deviation** (task split, reorder, clarification): Update the Linear issue(s) only, add a deviation comment explaining why, continue.
- **Major deviation** (fundamental plan problem, architecture issue, blocked): Stop immediately. Post a Linear project update. Set project health to **"At Risk"**. Wait for the human to revise the plan file, commit, push, and give a new go-ahead.

Never autonomously modify the `.agents/plans/` file during execution. If a plan change is needed, surface it and wait.
```

## Open questions
None.
