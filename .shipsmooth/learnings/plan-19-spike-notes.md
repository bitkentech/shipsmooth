# Plan 19 — Gemini Extension Spike Notes

Findings from the hand-authored Gemini CLI extension spike (`gemini-extension-spike/`, gitignored). These findings inform Tasks 1–7 of plan-19.

## How the spike was built

All files were hand-authored or directly copied from `build/` with the following adaptations:

- `gemini-extension.json` — new file; Gemini manifest schema (no `contextFileName`, no `mcpServers`)
- `commands/devostat.toml` — new file; Gemini doesn't auto-register commands per skill
- `skills/devostat/SKILL.md` — copy from `build/skills/devostat/SKILL.md`, with frontmatter added and paths patched
- `hooks/hooks.json` — adapted from `build/hooks/hooks.json`:
  - `${CLAUDE_PLUGIN_ROOT}` → `${extensionPath}`
  - `${CLAUDE_PLUGIN_DATA}` → `${HOME}/.cache/devostat-spike`
  - Added `mkdir -p` as first operation
- `package.json` — straight copy from `build/package.json`
- `scripts/tasks/*.ts` — straight copies from `build/scripts/tasks/*.ts`

## Verification results

- [x] Extension listed in `/extensions` as `devostat (v0.1.6) - active`
- [x] SessionStart hook ran: `~/.cache/devostat-spike/dist/` populated with all 9 compiled JS files
- [x] Skill discovered: `/skills` shows devostat with correct description
- [x] Slash command registered: `/devo` autocomplete shows both `devostat:devostat` (skill) and `devostat` (command)
- [x] Skill activated and ran the full devostat workflow on a real task (plan-08 in todo-1 repo)
- [x] `init.js` generated correct node command path after SKILL.md was patched

## Findings

### 1. SKILL.md requires Gemini frontmatter

Gemini's `loadSkillsFromDir` (in `chunk-XGCOPSUV.js`) requires a `---` YAML frontmatter block with both `name` and `description` fields. Without it, the skill is silently ignored — no error, just not discovered.

**Source code evidence:**
```js
// chunk-XGCOPSUV.js
var FRONTMATTER_REGEX = /^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n([\s\S]*))?/;
function parseFrontmatter(content) {
  const { name, description } = parsed;
  if (typeof name === "string" && typeof description === "string") {
    return { name, description };
  }
  // returns null if either is missing → skill not loaded
}
```

**Impact:** The source `SKILL.md` in `plugin-resources/` has no frontmatter (Claude Code discovers skills via `plugin.json`, not frontmatter). The Gemini build must prepend frontmatter. Use Maven resource filtering with a profile-specific property.

### 2. Hook env vars don't persist into the session

`export DEVOSTAT_DIST=...` in the SessionStart hook's shell command runs in a subshell. The variable does not survive into the Gemini session's tool execution environment. When the model reads the SKILL.md and runs `node $DEVOSTAT_DIST/init.js`, the variable is unset.

**Implication:** The `$DEVOSTAT_DIST` env var approach planned in Task 1 does **not work for Gemini**. It may work for Claude Code (whose hook mechanism differs), but for portability:
- The Gemini SKILL.md must hardcode `~/.cache/devostat/dist/` directly in script invocation examples.
- The Claude SKILL.md keeps `${CLAUDE_PLUGIN_DATA}/dist/` (Maven-filtered at build time).
- Use a Maven property `${devostat.dist.path}` in the SKILL.md template, with profile-specific values.

### 3. `init.js` fails to parse non-standard plan markdown formats

`init.js` (line 15) uses regex `### Task N: Name [Risk]` to extract tasks from plan markdown. When Gemini wrote plan-08 in the todo-1 repo, it used numbered lists and checkboxes instead of the expected heading format. The user had to create the tasks XML manually.

**Implication:** Not a plan-19 blocker — the heading format is the contract defined in SKILL.md, and the model should follow it. But if Gemini consistently ignores the format instruction, a more lenient parser may be needed as a future backlog item.

### 4. `gemini extensions link` creates a metadata-only dir, not a symlink

`~/.gemini/extensions/devostat/` is intentionally an empty directory containing only `.gemini-extension-install.json`:
```json
{"source": "/opt/workspace/devostat/gemini-extension-spike/", "type": "link"}
```

At load time, `_buildExtension` reads `installMetadata.type === "link"` and sets `effectiveExtensionPath = installMetadata.source`. All files (skills, hooks, manifest) are read from the source dir, not the extensions dir.

**Implication:** The dev workflow works as expected: edit source files, restart gemini, changes reflected immediately. No re-link needed.

### 5. Gemini shows two entries for the extension command

`/devo` autocomplete shows:
- `devostat:devostat` — the skill (from `skills/devostat/SKILL.md`)
- `devostat` — the slash command (from `commands/devostat.toml`)

This is expected and matches the superpowers extension pattern. The command triggers a prompt that tells the model to activate the skill.

### 6. Extension skill loading path confirmed

```js
// chunk-M5Q72DDY.js, inside _buildExtension:
let skills = await loadSkillsFromDir(
  path15.join(effectiveExtensionPath, "skills")
);
```

Skills are loaded from `<extensionPath>/skills/` using glob patterns `["SKILL.md", "*/SKILL.md"]`. Standard `skills/<name>/SKILL.md` structure works.

## Impact on plan-19 tasks

| Task | Status | Change |
|------|--------|--------|
| 0 | Done | Spike verified |
| 1 | Revise | Drop `$DEVOSTAT_DIST` env var. Use Maven property `${devostat.dist.path}` with profile-specific values. Add frontmatter for Gemini. |
| 2 | No change | — |
| 3 | No change | Spike confirmed manifest and TOML schemas |
| 4 | Simplify | No `export DEVOSTAT_DIST`. Just `${extensionPath}` and `${HOME}/.cache/devostat` substitutions. |
| 5 | Expand | Must also handle SKILL.md filtering and frontmatter injection |
| 6 | No change | — |
| 7 | No change | — |
