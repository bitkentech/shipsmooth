# harness/

This gradle module contains agent harness specific integrations (Claude, Codex, Gemini etc).

| Module    | Gradle path       | What it does                                                |
|-----------|-------------------|-------------------------------------------------------------|
| `shared/` | `:harness:shared` | The renderer that produces everything except the SKILL.md.  |
| `claude/` | `:harness:claude` | Claude Code plugin metadata                                 |
| `gemini/` | `:harness:gemini` | Gemini CLI extension metadata                               |
| `codex/`  | `:harness:codex`  | Codex CLI plugin metadata                                   |

## How the modules relate

```
plugin-model     <-  skills:pkg     <-  harness:shared     <-  harness:{claude,gemini,codex}
(Os, Platform,       (renders            (renders the rest      (per-host plugin metadata;
 Env, PluginModel)    SKILL.md)           of the payload +        each owns its assemble*)
                                          runs Target)
```

- `harness:shared` depends on `:plugin-model` (shared value types) and
  `:skills:pkg` (the `SkillRenderer` + precompiled JTE classes `Target` needs on
  its classpath). It also reads `:cli`'s jlink image to wire the dev `jlinkDir`.
- Each per-host module depends on `:harness:shared` for the render/copy
  producer tasks, and adds only its own host-specific manifest filtering. A host
  build = `harness:shared`'s rendered payload **+** that host's metadata, composed
  into the final plugin/extension tree by the host's `assemble*` task.

See [`../DEVELOPMENT.md`](../DEVELOPMENT.md) for per-harness build & local-install
instructions.
