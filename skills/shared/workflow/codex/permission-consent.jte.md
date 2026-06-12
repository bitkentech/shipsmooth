@param io.bitken.ss.resources.PluginModel model

**Note for Codex:** parallel subagent dispatch is not yet supported on the Codex host. Regardless of the user's choice, execute all tasks **sequentially** in the main context using the standard per-task loop. There is no separate permission patch to apply — Codex governs file access through its own sandbox/approval model, not a settings file.
