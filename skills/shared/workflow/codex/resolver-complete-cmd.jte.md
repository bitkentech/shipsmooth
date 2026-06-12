@param io.bitken.ss.resources.PluginModel model

Not used on Codex (this cut): conflict resolution is part of the parallel `integrate` step, which does not run under sequential execution. If a future Codex build enables parallel dispatch, after the resolver finishes, signal integrate to continue:
```bash
${model.cliBin()} --enable-experimental ledger resolver-complete --plan {N} --task {metadata.task_id} --repo $(git rev-parse --show-toplevel)
```
