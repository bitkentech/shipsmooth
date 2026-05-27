@param io.bitken.ss.resources.PluginModel model

3. After the Agent call returns, signal integrate to continue:
   ```bash
   ${model.cliBin()} --enable-experimental ledger-resolver-complete --plan {N} --task {metadata.task_id} --repo $(git rev-parse --show-toplevel)
   ```