@param io.bitken.shipsmooth.resources.PluginModel model

3. After the `invoke_agent` call returns, signal integrate to continue:
   ```bash
   ${model.cliBin()} --enable-experimental ledger-resolver-complete --plan {N} --task {metadata.task_id} --repo {repo-root}
   ```
   where `{repo-root}` is the absolute path to the repo root.