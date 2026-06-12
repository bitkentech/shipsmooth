@param io.bitken.ss.resources.PluginModel model

2. Not used on Codex (this cut): the resolver runs inside the parallel `integrate` step, which does not run under sequential execution. If a future Codex build enables parallel dispatch, resolve the conflict in the worktree directly (the `payload` includes the absolute worktree path and instructs you to use absolute paths for all file operations), then continue.
