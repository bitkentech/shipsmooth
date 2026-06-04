@param io.bitken.ss.resources.PluginModel model

```
1. ${model.cliBin()} --enable-experimental claim --plan {N} --task {id}
2. Run: ${model.cliBin()} --enable-experimental worker base --plan {N} --task {id}
   Capture the printed SHA as BASE.
3. Run: ${model.cliBin()} --enable-experimental worker init --plan {N} --task {id} --base {BASE}
   Capture the printed path as WORKTREE.
4. invoke_agent tool call — invoke generalist subagent, fill {absolute-worktree-path} with WORKTREE — see Worker Instruction Block below
5. ${model.cliBin()} --enable-experimental worker finish --plan {N} --task {id}
6. ${model.cliBin()} --enable-experimental worker cleanup --plan {N} --task {id}
```