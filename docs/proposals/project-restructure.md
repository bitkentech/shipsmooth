# Proposal: Project folder restructure

## Goal

Make it easy for developers to understand and contribute to the codebase.

Three guiding principles:

- **Smooth developer experience.** A contributor should be able to orient themselves quickly,
  find what they need to change without hunting across unrelated folders, and add a new target or
  skill without being forced to understand the whole system first. The development experience 
  should enable quick iterations.

- **Lightweight and evolvable taxonomy.** Folder names should reflect how contributors think
  about their work, not impose an architectural vocabulary they have to learn. The structure
  should grow naturally as new targets and skills are added, without requiring reorganisation.

- **Pragmatism.** Make some concessions (like moving `skills` to the top-level) to faciliate
  common use cases, rather than rigid taxonomy. 

## Proposed layout

```
shipsmooth/
  core/             ← Pure domain logic: ledger, workflow, git ops, plan service
  skills/
    text/           ← All JTE markdown content (the bulk of skill authoring)
      shared/       ← Base workflow partials + start/SKILL.jte.md (base skill body)
      experimental/ ← Feature-flagged skills
      claude/       ← Claude-specific JTE overrides
      gemini/       ← Gemini-specific JTE overrides
    other/          ← Everything that renders text/ into output: Java renderers, TS hook scripts
    pom.xml         ← Parent module: registers text/ as resources, declares other/ as submodule
  cli/              ← CLI: picocli commands, jlink image build
  web/              ← Web UI (future)
  desktop/
    win/            ← Windows desktop (future)
    linux/          ← Linux desktop (future)
    mac/            ← macOS desktop (future)
  claude/           ← Claude Code plugin: hooks, metadata, assembly
  gemini/           ← Gemini CLI extension: hooks, metadata, assembly
  opencode/         ← (future)
  shared/           ← Non-skill shared utilities: TS hook utilities, desktop libs etc
  packaging/        ← Assembly + release orchestration (name unchanged)
  devtools/         ← Dev-time helper scripts (rename from devel/)
  exp/
    model/          ← TLA+ formal verification specs (moved from model/)
```

## Key design decisions

### `skills/` is a first-class top-level concern

Skills are the primary product — the workflow knowledge that makes shipsmooth valuable. They
change most frequently and are the first thing most contributors will touch. Giving them a
dedicated top-level folder means a contributor working only on skill content never needs to
navigate elsewhere.

`skills/` is split into two subfolders, mirroring the webapp analogy of content vs scripts:

- **`text/`** — all the JTE markdown content. A non-Java contributor editing skill prose only
  ever touches this subtree. `text/shared/` holds base workflow partials; `text/claude/` and
  `text/gemini/` hold per-target JTE overrides.
- **`other/`** — everything that renders `text/` into output: `Target.java`, `SkillRenderer`,
  `HooksRenderer`, `SessionStartConfigRenderer`, and the TypeScript hook scripts. These are
  tightly coupled to the JTE precompilation model and must share a Maven module with the
  templates they render.

`skills/pom.xml` is the parent module: it registers `text/` as a resource directory (making
`.jte.md` files available on the classpath) and declares `other/` as a submodule.

Adding support for a new target means adding `text/opencode/` alongside a top-level `opencode/`
folder for its hooks and metadata.

### All targets are peers at the top level

`cli/`, `web/`, `desktop/`, `claude/`, `gemini/` are all siblings. There is no grouping taxonomy
to learn — a contributor wanting to add Opencode support sees `claude/` and `gemini/` and
immediately knows where to create `opencode/`. Each target folder holds only the non-skill
parts: hooks, metadata, and assembly.

### `core/` has no target knowledge

`core/` depends on nothing in `cli/`, `web/`, `desktop/`, or any AI platform target. It exposes
Java APIs that all targets consume. This is the single rule that keeps the layout stable as new
targets are added.

### `desktop/` is pre-split by OS

Desktop packaging is OS-specific by nature (installers, JRE bundling, signing). Pre-splitting
avoids a future reorganisation when the first desktop target is built. Note: the current
`integrations/claude/windows/` (Claude plugin packaged for Windows users) and a future
`desktop/win/` (Windows desktop app) are distinct concerns — do not conflate them.

### `shared/` mirrors the top level as things get factored out

`shared/` holds utilities that are genuinely used across multiple targets but are not skills:
currently the TypeScript hook utilities. Over time it may grow `shared/hooks/`,
`shared/desktop/`, `shared/cli/` as common logic is factored out. The structure of `shared/`
naturally tracks the top level — it never needs a taxonomy of its own.

The Java build tooling (`Target.java`, `SkillRenderer.java`, `HooksRenderer.java`) stays with
the skill templates in `skills/other/` — it is coupled to the JTE precompilation model and must
share a classpath with the templates it renders.

### `packaging/` name is preserved

`packaging/` is not renamed to `dist/` because `dist/` conventionally refers to build output
directories. `packaging/` holds the assembly and release orchestration code, not the output.

### `exp/` contains exploratory work, not feature-flagged code

`exp/` is for work with no build wiring — things not yet incorporated into the product at all.
The TLA+ model is the current example: it verifies invariants but is not compiled or shipped.

This is distinct from feature-flagged experimental skills (`skills/text/experimental/refine`,
`start-parallel`). Those are gated by the `experimental.enabled` flag but are fully wired into
the build and deployed to users who opt in. They stay in `skills/text/experimental/` alongside
the rest of the skill content.

`exp/` grows when something has no Maven module, no feature flag, and no shipping path yet.
`EXPERIMENTAL.md` moves to `exp/README.md` so documentation is co-located with the artifacts
it describes.

## Development workflow after restructure

### Contributor entry points

| "I want to..." | Start here |
|---|---|
| Change the core workflow or add a skill | `skills/text/shared/` |
| Add a Claude-specific skill variant | `skills/text/claude/` |
| Add a Gemini-specific skill variant | `skills/text/gemini/` |
| Add a new experimental (feature-flagged) skill | `skills/text/experimental/` |
| Change the skill renderer or hook scripts | `skills/other/` |
| Change the Claude hook or plugin metadata | `claude/` |
| Change the Gemini hook or extension metadata | `gemini/` |
| Add a new target (e.g. Opencode) | create `opencode/` + `skills/text/opencode/` |
| Change the Java engine | `core/` |
| Change CLI commands | `cli/` |
| Change shared TS hook utilities | `shared/` |

### Building a specific target (example: Gemini dev build)

```bash
mvn compile -pl gemini -am -Pgemini-dev
```

- `-pl gemini` — targets the Gemini module only
- `-am` — also builds upstream dependencies (`core/`, `skills/`, `shared/`)
- `-Pgemini-dev` — dev profile: sets `build.outputDir=build-gemini-dev/`, `build.env=dev`

### Editing skill files

| Change scope | Edit location |
|---|---|
| Shared workflow logic (all targets) | `skills/text/shared/` |
| Claude-specific skill content | `skills/text/claude/` |
| Gemini-specific skill content | `skills/text/gemini/` |

Edit `skills/text/shared/` when the change should propagate to all targets; edit the
target-specific folder when intentionally diverging.

### Full dev loop (Gemini example)

```bash
# 1. Edit JTE files in skills/text/shared/ or skills/text/gemini/
# 2. Rebuild
mvn compile -pl gemini -am -Pgemini-dev
# 3. Link to local Gemini CLI (one-time setup)
gemini extensions link --consent build-gemini-dev/
# 4. Changes are picked up on next mvn compile — no re-link needed
```

## What does not change

- Maven profiles (`dev`, `prod`, `gemini`, `gemini-dev`, `windows`) — same names, same semantics.
- `build/`, `build-gemini/`, `build-windows/` output directories — gitignored derived artifacts.
- Java package names (`io.bitken.ss.*`) — source-level rename is out of scope.
- `packaging/` assembly logic — module paths update, but the orchestration is unchanged.
- `.agents/` plan and task files — unaffected.


## Module-by-module mapping from current layout

| Current | Proposed | Notes |
|---|---|---|
| `app/src/main/java/io/bitken/ss/` (non-cli packages) | `core/` | `workflow`, `ledger`, `git`, `gw`, `svc`, `conf` |
| `app/src/main/java/io/bitken/ss/cli/` | `cli/` | picocli commands only |
| `app/pom.xml` (jlink profile) | `cli/pom.xml` | jlink build machinery moves with CLI |
| `integrations/common/src/main/jte-src/skills/shared/` | `skills/text/shared/` | Base workflow partials |
| `integrations/common/src/main/jte-src/skills/start/SKILL.jte.md` | `skills/text/shared/` | Base skill body; co-located with `base-workflow.jte.md` it imports |
| `integrations/common/src/main/jte-src/skills/experimental/` | `skills/text/experimental/` | Feature-flagged skills; stay in skills, not exp/ |
| `integrations/common/src/main/jte-src/skills/start/claude/` | `skills/text/claude/` | Claude-specific JTE overrides |
| `integrations/common/src/main/jte-src/skills/start/gemini/` | `skills/text/gemini/` | Gemini-specific JTE overrides |
| `integrations/common/src/main/java/` (Target, SkillRenderer etc) | `skills/other/` | Coupled to JTE precompilation; stays with templates |
| `integrations/common/scripts/` | `skills/other/` | TS hook scripts; rendered by HooksRenderer |
| `integrations/claude/` | `claude/` | Plugin metadata, hooks, assembly |
| `integrations/gemini/` | `gemini/` | Extension metadata, hooks, assembly |
| `packaging/` | `packaging/` | Unchanged |
| `devel/` | `devtools/` | Rename only |
| `model/` | `exp/model/` | Signals exploratory status |
| `EXPERIMENTAL.md` | `exp/README.md` | Co-locate docs with artifacts |



## Migration steps (when actioned)

1. Create new top-level directories.
2. Move `app/` non-cli source into `core/`; move `app/cli/` source into `cli/`.
3. Move `integrations/common/src/main/jte-src/skills/shared/` into `skills/text/shared/`.
4. Move `integrations/common/src/main/jte-src/skills/start/SKILL.jte.md` into `skills/text/shared/`.
5. Move `integrations/common/src/main/jte-src/skills/experimental/` into `skills/text/experimental/`.
6. Move `integrations/common/src/main/jte-src/skills/start/claude/` into `skills/text/claude/`.
7. Move `integrations/common/src/main/jte-src/skills/start/gemini/` into `skills/text/gemini/`.
8. Move `integrations/common/src/main/java/` (Target, SkillRenderer etc) into `skills/other/`.
9. Move `integrations/common/scripts/` into `skills/other/`.
10. Create `skills/pom.xml`: registers `text/` as a resource directory, declares `other/` as submodule.
11. Move `integrations/claude/` content into `claude/`.
12. Move `integrations/gemini/` content into `gemini/`.
13. Rename `devel/` to `devtools/`; update root `pom.xml` module list.
14. Move `model/` to `exp/model/`; move `EXPERIMENTAL.md` to `exp/README.md`.
15. Update all `pom.xml` `<relativePath>` and `<module>` references.
16. Update `DEVELOPMENT.md` with new paths and build commands.
