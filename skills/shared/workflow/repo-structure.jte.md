@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Repository Structure

Plan files and task state live in the **plans directory** — written `<plansDir>`
throughout this skill. Ask the CLI for its real location with `$SS store info --json`
(the `plansDir` field); it is the source of truth. In **separate-dir** mode (the
default) `<plansDir>` is a folder in a separate state directory, leaving the project
repo untouched. In **same-repo** mode it resolves to `.shipsmooth/plans/` inside the
repo.

```
<plansDir>/
  plan-07.md            # plan files live here, versioned in git
  plan-07-tasks.xml     # task state (sibling to plan file)
```

Plans are markdown files. They contain: narrative, design decisions, architecture notes, open questions, and references. Code never goes here.

---
