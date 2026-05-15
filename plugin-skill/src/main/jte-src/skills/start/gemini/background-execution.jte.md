@param io.bitken.shipsmooth.resources.PluginModel model

**Step 2 — run integrate in the background** (`run_shell_command` tool with `is_background: true`):

First, get the current branch name (run this as a normal blocking command):
```bash
git rev-parse --abbrev-ref HEAD
```

Then start integrate in the background, substituting the branch name you just captured for `{current-branch}`:
```bash
${model.cliBin()} integrate \
  --plan {N} \
  --task-branch {current-branch} \
  --verify-cmd "{your-test-command}"
```

You will be notified when integrate finishes. While it runs, watch for Monitor events. *Note: You can use the `read_background_output` tool with the returned PID to inspect stdout/stderr if needed.*
