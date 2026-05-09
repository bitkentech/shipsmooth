@param io.bitken.shipsmooth.resources.PluginModel model

**Step 2 — run integrate in the background** (`run_shell_command` tool with `is_background: true`):

```bash
${model.cliBin()} integrate \
  --plan {N} \
  --task-branch $(git rev-parse --abbrev-ref HEAD) \
  --verify-cmd "{your-test-command}"
```

You will be notified when integrate finishes. While it runs, watch for Monitor events. *Note: You can use the `read_background_output` tool with the returned PID to inspect stdout/stderr if needed.*
