@param io.bitken.shipsmooth.resources.PluginModel model

2. Perform an `Agent` tool call: `subagent_type: general-purpose`, prompt = `payload`, `cwd` = `metadata.worktree`. **Do not pass `isolation: worktree`.** The `cwd` parameter pins the resolver agent's working directory to the integration worktree — omitting it causes the agent to write files into the main repo instead.
