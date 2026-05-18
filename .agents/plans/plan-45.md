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

### What changes

**`session-start-config.json` (prod build)**
- Drop `cacheDir` field entirely. The prod build always uses the XDG-derived default.

**`session-start-config.json` (dev build)**
- Keep `cacheDir` as the tilde form `~/.cache/shipsmooth-dev` — dev uses a separate
  directory to stay isolated from prod. The Node.js runtime expands it.

**`session-start.ts`**
- Replace the `expandHome(config.cacheDir)` call with a new `resolveCache(config)` function:
  - If `config.cacheDir` is present and non-empty → expand tilde as today (dev path).
  - If `config.cacheDir` is absent or empty → apply XDG:
    `(process.env.XDG_CACHE_HOME ?? path.join(os.homedir(), '.cache')) + '/shipsmooth'`

**`ResourceBuilder.java`**
- Read `shipsmooth.cache.dir` (tilde form) via system property in addition to `.resolved`.
- Pass tilde form to `PluginModel.cacheDir` (used in `writeSessionStartConfig`).
- Keep resolved form for `cliBin` construction (used in SKILL.md templates).
- For prod: `shipsmooth.cache.dir` is empty string → `writeSessionStartConfig` omits `cacheDir`
  field (or writes empty string, which `session-start.ts` treats as absent).

**`pom.xml` (prod profile)**
- `shipsmooth.cache.dir` is already defined as `~/.cache/shipsmooth` — no change needed.
- `shipsmooth.cache.dir.resolved` stays for `cliBin` construction in templates.

**`plugin-skill/pom.xml`**
- Pass `shipsmooth.cache.dir` (tilde form) as an additional system property to `ResourceBuilder`.

### XDG resolution logic (Node.js)

```typescript
function resolveCache(config: { cacheDir?: string }): string {
  if (config.cacheDir) return expandHome(config.cacheDir);  // dev: ~/. cache/shipsmooth-dev
  const xdgCache = process.env['XDG_CACHE_HOME'] ?? path.join(os.homedir(), '.cache');
  return path.join(xdgCache, 'shipsmooth');
}
```

### What does NOT change

- The tilde `expandHome` function stays (still needed for dev builds).
- The `cliBin` path in SKILL.md templates still uses the resolved form — these templates are
  only used in dev/gemini-dev builds where the path is local and correct.
- No change to prod SKILL.md or hooks.json — these don't reference `cacheDir`.

## Tasks

### Task 1: Add XDG cache resolution in session-start.ts [Low]

Replace `expandHome(config.cacheDir)` with `resolveCache(config)` that applies XDG fallback
when `cacheDir` is absent or empty. Update the unit tests.

### Task 2: Pass shipsmooth.cache.dir tilde form to ResourceBuilder [Low]

In `plugin-skill/pom.xml`, add `shipsmooth.cache.dir` as a system property alongside the
existing `shipsmooth.cache.dir.resolved`. In `ResourceBuilder.java`, use the tilde form for
`PluginModel.cacheDir` and keep resolved form only for `cliBin`. Omit `cacheDir` from the
config JSON when it is empty (prod build).

### Task 3: Integration test — verify prod config has no hardcoded home [Low]

Add or update `ResourceBuilderIntegrationTest` to assert that a prod-profile build produces
a `session-start-config.json` with no `cacheDir` field (or empty), and that `session-start.ts`
resolves the correct XDG path at runtime.
