@param io.bitken.ss.resources.PluginModel model

- Run `$SS task status --plan {N} --task {id} --status agent-coded`. Then run `git rev-parse HEAD` and use that SHA in: `$SS task set-commit --plan {N} --task {id} --commit {HEAD-SHA}`.