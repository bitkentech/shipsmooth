@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## What Lives Where — Quick Reference

| Content | Location | Reason |
|---|---|---|
| Plan narrative, design decisions, references | `.agents/plans/*.md` in git | Needs diffs, version history, co-evolution with code |
| Task state (done / not done) | `[Linear]` Linear `[agent]` project · `[Local]` `.agents/plans/plan-{N}-tasks.xml` | Needs status tracking and human review |
| Feature definitions | `[Linear]` Linear permanent backlog · `[Local]` Noted in plan file Context section | Permanent, human-curated |
| Link between plan version and tasks | `[Linear]` Tag-based GitHub permalink in Linear issue description · `[Local]` `<created-from>` child element in XML | Immutable, survives branch lifecycle |
| This workflow | `~/.claude/skills/start/SKILL.md` | Loaded by agent at task start |
| Repo-specific overrides | `CLAUDE.md` in repo root | Workspace name, project conventions, etc. |

---
