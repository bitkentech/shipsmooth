@param io.bitken.ss.resources.PluginModel model

   - Run `${model.cliBin()} task status --plan {N} --task {id} --status agent-coded`. Then run `git rev-parse HEAD` and use that SHA in: `${model.cliBin()} task set-commit --plan {N} --task {id} --commit {HEAD-SHA}`. No draft review needed.