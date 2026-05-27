@param io.bitken.ss.resources.PluginModel model

```
1. ${model.cliBin()} --enable-experimental claim --plan {N} --task {id}
2. BASE=$(${model.cliBin()} --enable-experimental worker-base --plan {N} --task {id})       # resolve parent commit SHA
3. WORKTREE=$(${model.cliBin()} --enable-experimental worker-init --plan {N} --task {id} --base "$BASE")
4. Agent tool call — fill {absolute-worktree-path} with $WORKTREE — see Worker Instruction Block below
5. ${model.cliBin()} --enable-experimental worker-finish --plan {N} --task {id}
6. ${model.cliBin()} --enable-experimental worker-cleanup --plan {N} --task {id}
```