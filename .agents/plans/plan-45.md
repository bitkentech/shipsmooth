# Plan 45: XDG-Compliant Cache Directory Resolution

## Context

`session-start-config.json` currently contains a hardcoded absolute path for `cacheDir`
(e.g. `/home/pramod/.cache/shipsmooth`) because Maven expands `${user.home}` at build time
and bakes it into the distributed artifact. This means every released plugin ships with the
build machine's home directory — wrong for all users.

The runtime download cache is non-essential and re-downloadable, which maps exactly to
`$XDG_CACHE_HOME` per the XDG Base Directory Specification.

Backlog issue: none yet (tracked inline here as feature context).

## Design

### Prod vs dev split

- **Prod build** (`-Pprod`): `cacheDir` is omitted from `session-start-config.json` entirely.
  `session-start.ts` derives the path at runtime via XDG:
  `($XDG_CACHE_HOME ?? ~/.cache)/shipsmooth`
- **Dev build** (`-Pdev`): `cacheDir` is kept in the config as the tilde form
  `~/.cache/shipsmooth-dev`. Dev is never shipped; isolation from prod is the goal.
  `session-start.ts` expands the tilde at runtime as today.

Dev is local-only and never released, so baking a dev-machine path into the dev config is
acceptable. Only the prod artifact matters for correctness.

### What changes

**`session-start-config.json` (prod build)**
- Drop `cacheDir` field entirely.

**`session-start-config.json` (dev build)**
- Keep `cacheDir` as the tilde form `~/.cache/shipsmooth-dev`.

**`session-start.ts`**
- Replace `expandHome(config.cacheDir)` with `resolveCache(config)`:
  - If `config.cacheDir` is present and non-empty → expand tilde (dev path).
  - If absent or empty → XDG: `($XDG_CACHE_HOME ?? ~/.cache)/shipsmooth`

**`plugin-skill/pom.xml`**
- Replace `shipsmooth.cache.dir.resolved` system property with `shipsmooth.cache.dir`
  (the tilde form). `ResourceBuilder` no longer needs the expanded form for the config.

**`ResourceBuilder.java`**
- Read `shipsmooth.cache.dir` (tilde form) instead of `.resolved` for `PluginModel.cacheDir`.
- Keep `shipsmooth.cache.dir.resolved` only for `cliBin` construction (SKILL.md templates).
- In `writeSessionStartConfig`: omit `cacheDir` field when it is empty (prod build).

**`pom.xml`**
- No change needed. Both profiles already define `shipsmooth.cache.dir` in tilde form.
  `shipsmooth.cache.dir.resolved` stays for `cliBin` construction in templates (dev/gemini-dev).

### XDG resolution logic (Node.js)

```typescript
export function resolveCache(config: { cacheDir?: string }): string {
  if (config.cacheDir) return expandHome(config.cacheDir);  // dev: ~/.cache/shipsmooth-dev
  const xdgCache = process.env['XDG_CACHE_HOME'] ?? path.join(os.homedir(), '.cache');
  return path.join(xdgCache, 'shipsmooth');
}
```

### What does NOT change

- `expandHome` stays — still needed for dev tilde expansion.
- No change to prod hooks.json — it doesn't reference `cacheDir`.

### Post-task-2 discovery: cliBin broken in prod SKILL.md

Task 2 removed `shipsmooth.cache.dir.resolved` from `plugin-skill/pom.xml`, so
`cacheDirResolved` is always empty in `ResourceBuilder`. `cliBin` falls back to the bare
relative path `runtime-0.3.5/bin/shipsmooth-tasks` — useless without a prefix.

Using the tilde form for `cliBin` would break for users with a custom `XDG_CACHE_HOME`.

Final approach: use the shell expression
`${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-{version}/bin/shipsmooth-tasks` as `cliBin`.
This is valid shell, correct for all users, and mirrors the logic in `session-start.ts`
exactly. Drop `cacheDirResolved` from `ResourceBuilder` entirely. Add a comment in
`session-start.ts` pointing to the JTE template so the two stay in sync manually.

## Tasks

### Task 1: Add XDG cache resolution in session-start.ts [Low]

Replace `expandHome(config.cacheDir)` with `resolveCache(config)` that applies XDG fallback
when `cacheDir` is absent or empty. Export `resolveCache` for testing.

### Task 2: Stop passing resolved path to ResourceBuilder; omit cacheDir from prod config [Low]

In `plugin-skill/pom.xml`, replace `shipsmooth.cache.dir.resolved` with `shipsmooth.cache.dir`
(tilde form) as the system property passed to `ResourceBuilder`. In `ResourceBuilder.java`,
read `shipsmooth.cache.dir` for `PluginModel.cacheDir`; keep `.resolved` only for `cliBin`.
In `writeSessionStartConfig`, omit the `cacheDir` field when it is empty (prod build writes
no `cacheDir`; dev build writes the tilde form).

### Task 3: Integration tests — prod config has no cacheDir; Node resolves XDG [Low]

`ResourceBuilderIntegrationTest`: assert prod config has no `cacheDir` field; assert dev
config has `~/.cache/shipsmooth-dev`. Node tests: `resolveCache` uses `XDG_CACHE_HOME` when
set, falls back to `~/.cache/shipsmooth`, and expands tilde for dev.

### Task 4: Fix cliBin to use XDG shell expression; add cross-reference comment [Low]

In `ResourceBuilder.java`, replace `cacheDirResolved`-based `cliBin` construction with the
shell expression `${XDG_CACHE_HOME:-~/.cache}/shipsmooth/runtime-{version}/bin/shipsmooth-tasks`.
Drop `cacheDirResolved` entirely. Add a one-line comment above `resolveCache` in
`session-start.ts` pointing to `base-workflow.jte.md` so the two stay in sync. Update the
integration test assertion for `cliBin` in SKILL.md to match the new expression.
