@param io.bitken.ss.resources.PluginModel model

```
1. ${model.cliBin()} --enable-experimental claim --plan {N} --task {id}
2. WORKTREE=$(${model.cliBin()} --enable-experimental worker init --plan {N} --task {id})   # captures absolute worktree path
3. Implement the task yourself in $WORKTREE (Codex parallel subagent dispatch is not yet supported — run the per-task loop sequentially in the main context; see Worker Instruction Block below)
4. ${model.cliBin()} --enable-experimental worker finish --plan {N} --task {id}             # captures diff, commits, records events
5. ${model.cliBin()} --enable-experimental worker cleanup --plan {N} --task {id}            # removes worktree dir, keeps branch
```
