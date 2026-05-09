@param io.bitken.shipsmooth.resources.PluginModel model

2. Perform an `invoke_agent` tool call: `agent_name: generalist`, prompt = `payload`, `cwd` = `metadata.worktree` (if supported, otherwise ensure instructions restrict file edits to the worktree directory).
