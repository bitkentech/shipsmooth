@param io.bitken.ss.resources.PluginModel model

Not used on Codex (this cut): the ledger-watch / resolver cycle is part of the parallel `integrate` step, which does not run under sequential execution. If a future Codex build enables parallel dispatch, run this as a normal blocking shell command (Codex has no `Monitor` tool):
```bash
${model.cliBin()} --enable-experimental ledger watch --plan {N} --repo $(git rev-parse --show-toplevel) --after $LEDGER_SEQ
```
