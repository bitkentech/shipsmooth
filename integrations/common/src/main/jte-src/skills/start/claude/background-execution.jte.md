@param io.bitken.ss.resources.PluginModel model

**Step 2 — run integrate in the background** (Bash tool with `run_in_background: true`):

```bash
${model.cliBin()} --enable-experimental integrate \
  --plan {N} \
  --task-branch $(git rev-parse --abbrev-ref HEAD) \
  --verify-cmd "{your-test-command}"
```

You will be notified when integrate finishes. While it runs, watch for Monitor events.

