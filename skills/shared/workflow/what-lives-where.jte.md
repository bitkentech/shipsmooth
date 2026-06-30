@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## What Lives Where — Quick Reference

| Content | Location | Reason |
|---|---|---|
| Plan narrative, design decisions, references | `<plansDir>/plan-*.md` in git | Needs diffs, version history, co-evolution with code |
| Task state (done / not done) | `<plansDir>/plan-{N}-tasks.xml` | Needs status tracking and human review |
| Feature definitions | Noted in plan file Context section | Permanent, human-curated |
| Link between plan version and tasks | `<created-from>` child element in XML | Immutable, survives branch lifecycle |
| Repo-specific overrides | `CLAUDE.md` in repo root | Workspace name, project conventions, etc. |

---
