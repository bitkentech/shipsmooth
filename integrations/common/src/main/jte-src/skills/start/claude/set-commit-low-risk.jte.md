@param io.bitken.ss.resources.PluginModel model

   - `[Local]` Run `${model.cliBin()} update-status --plan {N} --task {id} --status agent-coded` and `${model.cliBin()} set-commit --plan {N} --task {id} --commit $(git rev-parse HEAD)`. No draft review needed.