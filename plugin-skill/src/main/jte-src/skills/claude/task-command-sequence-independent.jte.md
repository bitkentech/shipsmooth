@param io.bitken.shipsmooth.resources.PluginModel model

```
1. ${model.cliBin()} claim --plan {N} --task {id}
2. WORKTREE=$(${model.cliBin()} worker-init --plan {N} --task {id})   # captures absolute worktree path
3. Agent tool call — fill {absolute-worktree-path} with $WORKTREE — see Worker Instruction Block below
4. ${model.cliBin()} worker-finish --plan {N} --task {id}             # captures diff, commits, records events
5. ${model.cliBin()} worker-cleanup --plan {N} --task {id}            # removes worktree dir, keeps branch
```