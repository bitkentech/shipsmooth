@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Task Tracking Mode

This workflow supports two task tracking modes. Choose one at the start of each plan:

- **`[Linear]`** — Uses Linear issues and projects. Requires a Linear account and the Linear MCP server configured in Claude Code.
- **`[Local]`** — Uses a local XML file at `.agents/plans/plan-{N}-tasks.xml`. No external services required. Requires the plugin's SessionStart hook to have run (downloads the Java CLI runtime to `~/.cache/shipsmooth/`).

Throughout this skill, instructions marked `[Linear]` apply only in Linear mode; instructions marked `[Local]` apply only in Local mode. Unmarked instructions apply to both.

`[Local]` Script invocations use `${model.cliBin()} <subcommand>`. All scripts read/write `.agents/plans/plan-{N}-tasks.xml` relative to the repo root.

---
