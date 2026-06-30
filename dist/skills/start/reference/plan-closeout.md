# Plan Closeout (reference)

## Clean Completion
```bash
git tag plan-07-complete
git push origin plan-07-complete
```
- Close out the tasks: each finished task sits at `agent-coded` (coded and
  awaiting the human's final review). Once reviewed and accepted, mark it
  `closed` — the terminal status:
  ```bash
  $SS task status --plan {N} --task {id} --status closed
  ```
  This sweep is what moves a plan's tasks to their terminal state; a task the
  human wants to keep open for further review may be left at `agent-coded`.
- Run `$SS plan update --plan {N} --status complete --message "Plan complete."`. Commit the final XML state. Update the permanent backlog feature (if tracked externally) or note delivery in the plan file.

## Completion with Loose Ends
- Run `$SS task status --plan {N} --task {id} --status needs-triage` for each unresolved task. Run `$SS plan update --plan {N} --status in-review --message "..."`. Commit the XML. Wait for human to review: they will promote worthy items to the permanent backlog or discard them.

## Abandonment
- Human commits a plan file deletion with a commit message referencing the superseding plan number
- You tag the deletion commit:
  ```bash
  git tag plan-07-abandoned
  git push origin plan-07-abandoned
  ```
- **Do not delete any earlier tags** (`plan-07-v1`, `plan-07-v2`, etc.) — they are the audit trail
- Run `$SS plan update --plan {N} --status abandoned --message "Superseded by plan-{M}."`. Commit the final XML state.
