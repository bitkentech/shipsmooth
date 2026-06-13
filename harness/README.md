# harness/

This is where **agent-harness-specific** plugin integrations live. A "harness" is
an agent CLI/runtime that shipsmooth ships a plugin for — today **Claude Code**,
the **Gemini CLI**, and **Codex** (with **opencode** and **pi** planned). Add a new
harness as `harness/<name>/`.

> **Scope:** this folder is for *agent harnesses only*. IDE/editor extensions (e.g.
> a future Cursor integration) are a different category and would get their own
> top-level folder rather than living here — so `harness/` always means "an agent
> harness," nothing else. (See `plan-79` for the naming rationale.)

## What's in here

| Module | Gradle path | What it does |
|---|---|---|
| `shared/` | `:harness:shared` | The renderer that produces **everything except the SKILL.md** — `Target` (orchestrator), `HooksRenderer`/`HookCommandRenderer` (hooks.json + the SessionStart command and its `install-shipsmooth.sh`/`install-runtime.bat` companion), `SessionStartConfigRenderer`, the TypeScript SessionStart hook (`scripts/`), the POSIX bootstrap installer (`install-shipsmooth.sh`), and the `render*`/`copyDist*` tasks the per-host builds consume. |
| `claude/` | `:harness:claude` | Claude Code plugin metadata (`.claude-plugin/`, Windows variant). Assembles via `assembleClaudeDev` / `assembleClaudeProd` / `assembleWindows`. |
| `gemini/` | `:harness:gemini` | Gemini CLI extension metadata (`gemini-extension.json`, TOML commands). Assembles via `assembleGeminiDev` / `assembleGeminiProd`. |
| `codex/` | `:harness:codex` | Codex CLI plugin metadata (`.codex-plugin/`), with Codex's nested `plugins/<name>/` + `.agents/plugins/marketplace.json` layout. Assembles via `assembleCodexDev` / `assembleCodexProd`. |

## How the modules relate

```
plugin-model  <-  skills:pkg  <-  harness:shared  <-  harness:{claude,gemini,codex}
   (leaf types:        (renders          (renders the rest         (per-host plugin
    Os, Platform,       SKILL.md)         of the payload +          metadata; each
    Env, PluginModel)                     runs Target)              owns its assemble*)
```

- **`harness:shared` depends on `:plugin-model`** (shared value types) **and
  `:skills:pkg`** (the `SkillRenderer` + precompiled JTE classes `Target` needs on
  its classpath). It also reads `:cli`'s jlink image to wire the dev `jlinkDir`.
- **Each per-host module depends on `:harness:shared`** for the render/copy
  producer tasks, and adds only its own host-specific manifest filtering. A host
  build = `harness:shared`'s rendered payload **+** that host's metadata, composed
  into the final plugin/extension tree by the host's `assemble*` task.
- These modules **do not** touch `packaging/` — `packaging` is the separate
  runtime/release path and depends on `:plugin-model` directly (for `Os` only).

## Adding a new harness

1. Create `harness/<name>/` with its own `build.gradle.kts` (resource-filtering
   only — the `base` plugin, no Java; mirror `claude`/`gemini`/`codex`).
2. Depend on `:harness:shared`'s render producers and add host-specific manifest
   tasks.
3. Register it in `settings.gradle.kts` as `include("harness:<name>")`.
4. Add a `<name>` dev/prod render spec in `harness/shared/build.gradle.kts` if the
   host needs a distinct hook command / frontmatter / output shape.

See [`../DEVELOPMENT.md`](../DEVELOPMENT.md) for per-harness build & local-install
instructions (Claude / Gemini / Codex), and the `plan-79` plan file for the design
rationale behind this layout.
