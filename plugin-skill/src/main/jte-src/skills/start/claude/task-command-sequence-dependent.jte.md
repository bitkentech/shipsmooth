@param io.bitken.shipsmooth.resources.PluginModel model

```
1. ${model.cliBin()} claim --plan {N} --task {id}
2. BASE=$(${model.cliBin()} worker-base --plan {N} --task {id})       # resolve parent commit SHA
3. WORKTREE=$(${model.cliBin()} worker-init --plan {N} --task {id} --base "$BASE")
4. Agent tool call — fill {absolute-worktree-path} with $WORKTREE — see Worker Instruction Block below
5. ${model.cliBin()} worker-finish --plan {N} --task {id}
6. ${model.cliBin()} worker-cleanup --plan {N} --task {id}
```