@param io.bitken.shipsmooth.resources.PluginModel model

   - `[Local]` Run `${model.cliBin()} update-status --plan {N} --task {id} --status agent-coded`. Then run `git rev-parse HEAD` and use that SHA in: `${model.cliBin()} set-commit --plan {N} --task {id} --commit {HEAD-SHA}`. No draft review needed.