@param io.bitken.ss.resources.PluginModel model

Use the `run_shell_command` tool (blocking) with this command:
```bash
${model.cliBin()} --enable-experimental ledger watch --plan {N} --repo {repo-root} --after $LEDGER_SEQ
```
where `{repo-root}` is the absolute path to the repo root (run `pwd` from the repo root if unsure).