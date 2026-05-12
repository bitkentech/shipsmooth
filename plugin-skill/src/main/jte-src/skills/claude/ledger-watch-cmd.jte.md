@param io.bitken.shipsmooth.resources.PluginModel model

Use the Monitor tool with this command:
```bash
${model.cliBin()} ledger-watch --plan {N} --repo $(git rev-parse --show-toplevel) --after $LEDGER_SEQ
```