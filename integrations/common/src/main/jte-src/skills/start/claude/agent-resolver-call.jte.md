@param io.bitken.ss.resources.PluginModel model

2. Perform an `Agent` tool call: `subagent_type: general-purpose`, prompt = `payload`. **Do not pass `isolation: worktree`.** The resolver prompt includes the absolute worktree path and instructs the agent to use absolute paths for all file operations. No `cwd` parameter is needed or supported.
