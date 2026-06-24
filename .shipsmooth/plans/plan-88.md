# plan-88 — OpenCode skill install + scope-aware staging (v2)

> **v2 scope change.** v1 was a surgical OpenCode-only skill fix. v2 additionally
> de-couples the render-spec definitions so one host's spec can no longer leak into
> another's (the `.copy()` inheritance chain — `codexSpec = geminiSpec.copy(...)` etc.
> — made Codex/OpenCode silently inherit Gemini's frontmatter and Claude's skill
> basename). Folded in at the user's request; re-versioned per the plan-version-bump
> convention. New Task 1 (the refactor) precedes the OpenCode changes because they now
> live on the de-coupled structure.

## Context

**Backlog feature:** OpenCode host parity — the `start` skill must be discoverable
in OpenCode the same way it is in Claude/Codex/Gemini. (plan-86 shipped the OpenCode
plugin host; this plan closes the skill-discovery gap it left.)

## Problem (diagnosed in-session, evidence from a live 1.17.9 install)

The shipsmooth-opencode npm package ships `plugin/` + `skills/start/SKILL.md`, but
**the skill is never detected**. The plugin itself loads fine (logs show
`shipsmooth: runtime 0.3.27 ready` and `command=shipsmooth:start` registered).

Root cause: **OpenCode discovers `SKILL.md` only from filesystem dirs it scans** —
`~/.config/opencode/skills/`, `.opencode/skills/`, `~/.claude/skills/`,
`.claude/skills/`, `~/.agents/skills/`, `.agents/skills/` — and **never from inside
a node_modules package**. The bundled `skills/start/SKILL.md` sits in
`node_modules/@bitkentech/shipsmooth-opencode/skills/`, which is not scanned. So the
`shipsmooth:start` command fires, its template says "invoke the `start` skill", and
no `start` skill exists in any discovered location → "skill not detected." Confirmed:
`~/.config/opencode/skills/` was empty and OpenCode's skill-discovery log never lists
`start`. A manual copy into `~/.config/opencode/skills/start/` fixed it immediately.

This is unlike Claude, where the host manages a versioned per-plugin cache
(`~/.claude/plugins/cache/bitkentech/shipsmooth/<version>/skills/`) and discovers
skills from inside the installed plugin. OpenCode's skill model is **flat, global (or
project), and unversioned** — so the plugin must stage the skill into a scanned dir
itself.

## Approach

### Pre-work: de-couple the render specs (new in v2)

Today `harness/shared/build.gradle.kts` builds the 9 render variants through a
host→host `.copy()` chain:

```
claudeDevSpec → geminiDevSpec → codexDevSpec / opencodeDevSpec
claudeDevSpec → claudeProdSpec → geminiProdSpec → codexProdSpec / opencodeProdSpec / windowsSpec
```

This conflates two independent axes: **env deltas** (dev→prod: `buildEnv`,
`pluginDescription`, `jlinkDir` — host-agnostic) and **host identity**
(`buildPlatform`, `pluginHookCommand`, `skillFrontmatter`, `pluginSkillStartBasename`,
`outputDir` — must never cross hosts). Because the chain entangles them, OpenCode
inherits Gemini's `skillFrontmatter` (`name: start`) and Claude's basename — so an edit
to one host can silently change another, the exact trap this plan otherwise has to
tiptoe around.

Replace it with a host-agnostic base + per-host overrides, **no host→host edges**:

- `baseSpec(env: BuildEnv)` — holds only env-keyed, host-blind defaults
  (`buildOs`, `pluginBaseName`, `pluginVersion`, env-derived `pluginDescription` and
  `jlinkDir`, default `pluginSkillStartBasename="start"`, empty `skillFrontmatter`,
  `pluginRepoName`, `objects`). Sets `buildPlatform`/`outputDir`/`pluginHookCommand`
  to empty — each host supplies its own.
- `claudeSpec(env)`, `geminiSpec(env)`, `codexSpec(env)`, `opencodeSpec(env)`,
  and the windows variant — each `baseSpec(env).copy(...)` overriding **only its own**
  host fields. Each host function is the single home of that host's identity.
- Derive the 9 named specs from these (`claudeDevSpec = claudeSpec(DEV)`, …).

Invariant: every host's rendered output (skills tree, frontmatter,
session-start-config.json, hooks) must be **byte-identical** to pre-refactor — the
refactor is pure restructuring. (`RenderSpec`'s own doc calls render-variable drift
"the #1 correctness risk", so this is proven, not assumed.)

This makes the OpenCode skill-name change (Task 3) a clean per-host override that
provably cannot touch claude/gemini/codex.

### The skill fix (from v1)

The plugin already shells out to install the jlink runtime on `session.created`
(idempotent, non-fatal). Extend that bootstrap to **also stage the bundled SKILL.md
into an OpenCode-scanned `skills/` dir**, with three properties OpenCode won't provide:

1. **Scope-aware destination.** Mirror how the plugin was installed:
   - plugin in the **project** `opencode.json` (`<worktree>/opencode.json`) →
     stage to `<worktree>/.opencode/skills/<name>/`
   - plugin in the **global** config (`<config>/opencode.json`) →
     stage to `<config>/skills/<name>/`
   - **fallback = global** when scope can't be inferred (plugins-dir install,
     unreadable config, listed in neither).
   The authoritative config dir comes from `client.path.get().config` (SDK `Path`
   type, verified present in 1.17.9); the project root from `PluginInput.worktree`.

2. **Collision-proof name.** OpenCode's `skills/` namespace is shared host-wide;
   a bare `start` collides with any other `start` skill (first discovered wins +
   warns). Namespace it: **`shipsmooth-start`** (prod) / **`shipsmooth-start-dev`**
   (dev). One source — the rendered skill basename — drives the bundle dir name, the
   frontmatter `name:`, the install dir, and the plugin's `skillName(cfg.name)` so the
   command template's backticked name always matches the staged dir.

3. **Version-stamped freshness.** OpenCode has no per-version skill dirs. Write a
   version marker beside the staged skill; on `session.created`, re-stage only when
   the bundled `cfg.version` differs from the marker. Claude-like "active version's
   skill is staged," without clobbering on every session.

Contract matches the runtime bootstrap: idempotent, non-fatal (a failure logs via
`safeLog` and degrades; never crashes the session).

## Key facts (verified in-session)

- `PluginInput` (1.17.9): `{ client, project, directory, worktree, serverUrl, $ }`.
- `client.path.get()` → `Path { state, config, worktree, directory }` — authoritative
  config dir; no need to guess `XDG_CONFIG_HOME`/`~/.config/opencode`.
- `cfg.name` (from `dist/session-start-config.json`) = `shipsmooth` / `shipsmooth-dev`
  (NOT the scoped npm name). `skillName(cfg.name)` must map these →
  `shipsmooth-start` / `shipsmooth-start-dev`.
- Render pipeline: `buildSrc/RenderSpec.pluginSkillStartBasename` (default `start`) +
  `env.decorate()` (appends `-dev` for dev) name the rendered `skills/<basename>/` dir;
  `skillFrontmatter` carries the `name:` line. Only the **opencode** dev/prod specs in
  `harness/shared/build.gradle.kts` change — claude/gemini/codex keep `start`.
- `harness/opencode/build.gradle.kts` Sync copies `skills/**` from the render stage to
  the payload root unchanged (no per-name reference), so the rename flows through.

## Out of scope

- Changing the slash-command id (`shipsmooth:start` stays — it's already namespaced).
- Per-version skill dirs (OpenCode can't support them; one active version is staged).
- Other hosts' skill naming (claude/codex/gemini keep bare `start`).

## Tasks

Risk-sorted (High → Medium). Task 1 (the de-coupling refactor) lands first because the
OpenCode skill-name change (Task 3) lives on the de-coupled structure. Task 2 (staging
logic) is independent of the refactor but shares the namespaced name Task 3 sets, so
Task 3's name decision is fixed at the start of Task 2.

### Task 1: De-couple render specs into base + per-host overrides [High]

Refactor `harness/shared/build.gradle.kts`'s render-variant section:

- Add `baseSpec(env: BuildEnv)` with host-agnostic, env-keyed defaults only
  (no `buildPlatform`/`outputDir`/`pluginHookCommand` identity — those default empty).
- Add `claudeSpec(env)`, `geminiSpec(env)`, `codexSpec(env)`, `opencodeSpec(env)`
  (+ the windows variant), each `baseSpec(env).copy(...)` overriding only its own host
  fields. **No host function references another host's spec.**
- Re-derive the 9 named specs (`claudeDevSpec = claudeSpec(DEV)`, `claudeProdSpec =
  claudeSpec(PROD)`, …) and leave `registerRender(...)` wiring unchanged.
- **Regression proof:** render all 9 variants before (baseline) and after; assert the
  output trees (skills/, hooks/, dist/session-start-config.json) are byte-identical.
  No host's payload may change in this task.

### Task 2: Scope-aware skill staging in the plugin [High]

*Depends-on: 1*

The core fix. Add a pure `installSkill(...)` helper to
`harness/opencode/src/main/ts/src/lib/internal.ts` and call it from the
`session.created` bootstrap in `src/index.ts` (alongside the runtime install).

Behaviour:
- Resolve destinations from runtime signals: `client.path.get()` → `Path.config`
  (authoritative global config dir) and `PluginInput.worktree` (project root).
- **Infer install scope** by reading the `"plugin"` arrays of
  `<worktree>/opencode.json` (project) and `<config>/opencode.json` (global) and
  checking which lists the package (`cfg.name`’s npm identity). Project match →
  `<worktree>/.opencode/skills/<skillName>/`; global match → `<config>/skills/<skillName>/`.
- **Fallback = global** when neither config lists it or a config can’t be read
  (plugins-dir install, unreadable file).
- `<skillName>` = `skillName(cfg.name)` (defined to return `shipsmooth-start` /
  `shipsmooth-start-dev` — see Task 3). Source SKILL.md = the bundled
  `skills/<skillName>/SKILL.md` resolved relative to the plugin module dir.
- **Version-stamped freshness:** write `cfg.version` to a marker beside the staged
  skill; re-stage only when the bundled version differs from the marker (or the
  skill is absent). Idempotent.
- **Non-fatal:** any failure logs via `safeLog` and returns; never throws.

Tests (node:test, in `test/index.test.ts`): scope inference (project-listed →
project dir; global-listed → global dir; neither → global fallback); version-marker
re-stage vs skip; non-fatal on unreadable/missing source. Keep `internal.ts` helpers
out of the plugin entry’s exports (the OpenCode every-export-is-a-factory constraint).

### Task 3: Rename the rendered skill to `shipsmooth-start` [Medium]

*Depends-on: 1,2*

Make the rendered skill dir, its frontmatter `name:`, and the plugin’s
`skillName()` all resolve to the namespaced name, **for OpenCode only**. With Task 1's
de-coupling done, this is a clean per-host override that cannot affect other hosts.

- `harness/shared/build.gradle.kts`: in the new `opencodeSpec(env)` function set
  `pluginSkillStartBasename = "shipsmooth-start"` and write its own `skillFrontmatter`
  with `name: shipsmooth-start` (the dev variant's frontmatter → `shipsmooth-start-dev`).
  Because `opencodeSpec` no longer descends from `geminiSpec`, the frontmatter is
  authored here, not inherited. claude/gemini/codex specs are untouched (still `start`).
- `lib/internal.ts`: change `skillName(pluginName)` to return
  `shipsmooth-start` / `shipsmooth-start-dev` (was `start` / `start-dev`); the command
  template (`startCommandTemplate`) already derives from it, so the backticked name in
  the launcher prose updates automatically and matches the staged dir.
- Confirm `harness/opencode/build.gradle.kts` needs no change (it copies `skills/**`
  generically). Confirm the rendered prod payload now contains
  `skills/shipsmooth-start/SKILL.md` with `name: shipsmooth-start`.

### Task 4: End-to-end verification in a real OpenCode [Medium]

*Depends-on: 1,2,3*

Prove detection works and nothing regressed.

- Build the prod payload (`assembleOpencodeProd`) and confirm
  `skills/shipsmooth-start/SKILL.md` is present with correct frontmatter.
- Exercise the staging against a real `client.path.get()` shape (or a faithful test
  double): confirm a project-scoped install stages into `<worktree>/.opencode/skills/`,
  a global-scoped install into `<config>/skills/`, and the fallback into global.
- Confirm OpenCode’s `skill` tool now resolves `shipsmooth-start` (skill-discovery log
  lists it; `shipsmooth:start` command → skill invocation succeeds end-to-end).
- Regression check: claude/codex/gemini/windows renders still emit `skills/start/`
  unchanged, and (covering Task 1) all 9 render outputs match the pre-refactor baseline
  except the intended OpenCode `shipsmooth-start` delta from Task 3.

