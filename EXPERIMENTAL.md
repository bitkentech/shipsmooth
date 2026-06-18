## Experimental

These features are in active development and may change.

### Java runtime for `shipsmooth`

Task tracking commands (`update-status`, `add-comment`, `set-commit`, etc.) are now backed by a jlink-packaged Java runtime rather than Node.js scripts. The runtime bundles a minimal JRE and is installed to `~/.cache/shipsmooth/{version}/` by the session-start hook. It starts in ~150 ms via OpenJ9's shared class cache and requires no separate JDK installation.

The CLI entry point is `shipsmooth` and exposes subcommands for every task-tracking operation. The module is at `plugin-tasks-java/`.

> **Removed (plan-82):** the experimental ledger-backed execution trace and the
> parallel coding-subagent subsystem (worker/integrate/worktree commands and the
> content-addressed object store) have been removed to reduce surface area. They
> may be re-introduced in a different form later; the formal model lives at
> `exp/model/`.