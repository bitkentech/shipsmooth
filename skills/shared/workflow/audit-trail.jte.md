@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Audit Trail

`[Linear]` Record in every Linear issue:

| Event | What to store in the issue |
|---|---|
| Task created | `github.com/.../blob/{plan-07-v1-hash}/.agents/plans/plan-07.md` |
| Task closed / obsoleted | `github.com/.../blob/{plan-07-vN-hash}/.agents/plans/plan-07.md` + one-line reason |

`[Local]` The XML file is the audit trail. `<created-from>` and `<closed-at-version>` child elements on each `<task>` serve the same role. The XML is versioned in git, so `git diff` between two plan tags shows exactly what changed.

If the creation version equals the closeout version, the plan never changed during execution. If they differ, the git diff between the two tag hashes shows exactly what changed and why.

Feature issues in the permanent backlog should accumulate references to every plan that contributed to them — this gives a full delivery history across the feature's lifetime.

---
