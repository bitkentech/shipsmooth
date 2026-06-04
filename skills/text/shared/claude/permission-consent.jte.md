@param io.bitken.ss.resources.PluginModel model

If the user chooses **Yes**: patch `.claude/settings.json` in the target repo (see below), then proceed with the parallel dispatch.
If the user chooses **No**: execute all tasks sequentially in the main context window using the standard per-task loop instead. Do not dispatch any `Agent` tool calls.

**Worktree permission patch (required before first dispatch, reverted after last worker cleanup):**

Subagents run in a fresh permission context and do not inherit the Lead Agent's session approvals. Without pre-approved paths, subagents will be blocked when they attempt to `Edit`/`Write` files in their worktree.

Before dispatching any subagent, patch `.claude/settings.json` in the **target repo** (not `~/.claude/settings.json`):

1. Read the file — if it does not exist, treat its current content as `{}`.
2. Merge the following entries into the `permissions.allow` array (do not overwrite unrelated keys):
   ```json
   "Edit(.agents/tasks/**)",
   "Write(.agents/tasks/**)",
   "Bash(cd .agents/tasks/** *)"
   ```
3. Write the file back. Tell the user: *"Adding temporary worktree permissions to .claude/settings.json so subagents can edit their worktree paths. These will be removed after integration completes."*

After **all** `worker cleanup` calls have completed, revert:

1. Remove only the three entries added above from `permissions.allow`. If the array is now empty, remove it. If `permissions` is now empty, remove it. If the file was created from scratch (it did not exist before), delete it entirely.
2. Tell the user: *"Restored .claude/settings.json — temporary worktree permissions removed."*

