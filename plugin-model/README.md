# plugin-model

A tiny **leaf** module holding the shared value types used when rendering and
packaging shipsmooth's plugins. It deliberately has **no dependencies** — no other
shipsmooth module, no third-party libraries — so anything that needs these types
can depend on it without pulling in the rendering or packaging machinery.

## The types

All four live in package `io.bitken.ss.resources`:

- **`Os`** — the target operating system (sealed: `Posix` / `Windows`), with the
  OS-specific facts the build needs (launcher file name, java exe, CLI bin path).
- **`Platform`** — the target agent host (sealed: `Claude` / `Gemini` / `Codex`).
- **`Env`** — the build environment (dev / prod).
- **`PluginModel`** — the record bundling the values that describe a plugin payload
  to render.

## Who depends on it

```
plugin-model
   ^   ^   ^
   |   |   `-- packaging      (PackageRuntime needs Os alone)
   |   `------ skills:pkg     (SkillRenderer)
   `---------- harness:shared (Target + the renderers)
```

Because it's the shared leaf, `packaging` can reach `Os` without depending on the
whole skills/harness rendering stack — that decoupling is the reason this module
exists as its own thing (see the `plan-79` plan file).

## See also

- [`../harness/`](../harness) — the renderers that consume these types
- [`../DEVELOPMENT.md`](../DEVELOPMENT.md) — repo structure and build instructions
