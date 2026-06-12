@param io.bitken.ss.resources.PluginModel model

**`$WORKTREE` is the directory you implement the task in.** Capture it from `worker init` before starting — it is where this task's files live and where `worker finish` looks for the diff. (Codex parallel subagent dispatch is not yet supported; the Lead Agent works in `$WORKTREE` directly.)
