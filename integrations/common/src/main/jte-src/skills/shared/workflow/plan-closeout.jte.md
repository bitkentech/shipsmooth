@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Plan Closeout

### Clean Completion
```bash
git tag plan-07-complete
git push origin plan-07-complete
```
- `[Linear]` Close all Linear issues in the `[agent]` project. Mark `[agent]` project complete and archive it. Update the permanent backlog feature issue to reflect delivery (link to completing PR, note what was delivered).
- `[Local]` Run `${model.cliBin()} project-update --plan {N} --status complete --message "Plan complete."`. Commit the final XML state. Update the permanent backlog feature issue (if tracked externally) or note delivery in the plan file.

### Completion with Loose Ends
- `[Linear]` Label unresolved issues `needs-triage`. Set `[agent]` project to **"In Review"**. Post a project update listing each open issue and why it's unresolved. Wait for human to review: they will promote worthy issues to the permanent backlog or discard them. Human marks the project complete and archives it.
- `[Local]` Run `${model.cliBin()} update-status --plan {N} --task {id} --status needs-triage` for each unresolved task. Run `${model.cliBin()} project-update --plan {N} --status in-review --message "..."`. Commit the XML. Wait for human to review.

### Abandonment
- Human commits a plan file deletion with a commit message referencing the superseding plan number
- You tag the deletion commit:
  ```bash
  git tag plan-07-abandoned
  git push origin plan-07-abandoned
  ```
- **Do not delete any earlier tags** (`plan-07-v1`, `plan-07-v2`, etc.) — they are the audit trail
- `[Linear]` Surface all open tasks for human triage. Migrate worthy tasks to the permanent backlog with a note: "Partial delivery — see plan-07-abandoned, superseded by plan-{M}". Archive the `[agent]` project with a closing note referencing the deletion commit hash and the superseding plan.
- `[Local]` Run `${model.cliBin()} project-update --plan {N} --status abandoned --message "Superseded by plan-{M}."`. Commit the final XML state.

---
