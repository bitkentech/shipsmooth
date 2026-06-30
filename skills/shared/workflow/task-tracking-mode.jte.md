@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Task Tracking

Task state is tracked in a local XML file at `.shipsmooth/plans/plan-{N}-tasks.xml`. No external services required. This requires the plugin's SessionStart hook to have run (downloads the Java CLI runtime to `~/.cache/shipsmooth/`).

Script invocations use the shipsmooth CLI. Throughout this skill it is abbreviated
as **`$SS`**, defined once here:

```bash
SS="${model.cliBin()}"
```

Every `$SS <subcommand>` below means that path — expand it when you actually run the
command. All scripts read/write `.shipsmooth/plans/plan-{N}-tasks.xml` relative to the
repo root.

---
