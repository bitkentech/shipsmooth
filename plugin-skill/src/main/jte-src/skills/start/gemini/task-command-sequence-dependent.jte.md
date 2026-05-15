@param io.bitken.shipsmooth.resources.PluginModel model

```
1. ${model.cliBin()} claim --plan {N} --task {id}
2. Run: ${model.cliBin()} worker-base --plan {N} --task {id}
   Capture the printed SHA as BASE.
3. Run: ${model.cliBin()} worker-init --plan {N} --task {id} --base {BASE}
   Capture the printed path as WORKTREE.
4. invoke_agent tool call — invoke generalist subagent, fill {absolute-worktree-path} with WORKTREE — see Worker Instruction Block below
5. ${model.cliBin()} worker-finish --plan {N} --task {id}
6. ${model.cliBin()} worker-cleanup --plan {N} --task {id}
```