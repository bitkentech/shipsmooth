@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Linear Structure

`[Linear]` only. Skip this section in Local mode.

### Permanent Backlog Project
- Named e.g. `AppName — Backlog & Roadmap`
- Contains feature issues only
- Human-created and human-prioritised
- Never deleted, survives all plan lifecycles

### Transient Agent Projects
- Named: `[agent] {N} · {short-description}` e.g. `[agent] 07 · home-accounts-settings-bottom-tabs`
- Created per plan, archived after completion
- Project description must contain:
  - Link to the permanent backlog feature issue(s) it delivers
  - Permalink to the plan file using the tag-based commit hash URL (see below)
  - Brief plan narrative / design rationale

### Tag-based GitHub permalink format
```
https://github.com/{org}/{repo}/blob/{tag-commit-hash}/.agents/plans/plan-07.md
```

Resolve the commit hash for a tag:
```bash
git rev-list -n 1 plan-07-v1
```

Use this hash (not the tag name) in Linear links — it is immutable and survives branch deletion, rebases, and squash merges.

---
