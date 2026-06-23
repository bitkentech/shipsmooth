# Plan 26: Rename devostat → shipsmooth

## Context

The project is being rebranded from **devostat** to **shipsmooth**, with domain `shipsmooth.net`. The current name is opaque — new users can't infer what it does. "ShipSmooth" communicates intent (smooth shipping for developers), pronounces and spells easily on first hearing, and positions the project as a methodology name (like "Agile" or "Lean") with the plugin being one implementation.

`.net` is an accepted compromise: `.com` is priced out of reach, `.io` is expensive and on ICANN's retirement track. If the project gains traction, `.com` can be acquired later. Until then, `.net` costs nothing real for an OSS solo-dev tool whose distribution runs through GitHub and plugin marketplaces rather than organic web search.

Rename cost today is near-zero: no users, no adoption, no inbound links. Every additional week of development adds artifacts to retrofit (demo gifs, marketplace listings, the upcoming JBake site). The right time is now, before the site launches.

### Decisions already made with the user

- **Product name:** `ShipSmooth` (display), `shipsmooth` (slug)
- **Domain:** `shipsmooth.net`
- **Skill invocation path:** `/shipsmooth:start` — clearer entry-point semantics than `/shipsmooth:ship`; `start` signals "kicks off the workflow" without requiring users to learn methodology vocabulary
- **Tagline:** defer — user will revise later; existing tagline carries over unchanged for now
- **`.agents/` directory convention:** leave alone (internal, name-neutral)
- **Historical `.agents/plans/plan-NN.md` and learnings files:** leave untouched (they are historical records)
- **GitHub repos:** rename via web UI — `bitkentech/devostat` → `bitkentech/shipsmooth`; `bitkentech/devostat-gemini` → `bitkentech/shipsmooth-gemini`

### Backlog reference

This rebrand is not tracked as a Linear feature issue (Local mode). The rebrand itself is the deliverable; downstream feature work will continue on whichever plans come after this one.

## Approach

Work in this order to keep each step independently verifiable:

1. **Source-of-truth config** — Maven POMs, plugin manifests, marketplace JSON, source skill directory rename. These are where the build derives from; getting them right makes step 2 succeed.
2. **Scripts and hooks** — release scripts, smoke tests, Gemini extension hooks (cache paths, log strings).
3. **Source docs** — README.md, DEVELOPMENT.md, gemini-extension README, demo `.cast` files (demo gifs themselves stay; only textual cast commands update).
4. **Rebuild and verify** — `mvn clean process-resources` (Claude) and `mvn -P gemini,'!dev','!claude' process-resources` (Gemini). Build outputs regenerate with new names.
5. **GitHub repo renames** — do via web UI after local commit lands. GitHub auto-redirects old URLs, so existing clones keep working until remotes are updated.
6. **Marketplace listing** — update the entry in `bitkentech/claude-plugins` that points to this plugin.

The skill invocation path change (`/devostat:devostat` → `/shipsmooth:start`) is two separate changes combined: the plugin name changes to `shipsmooth`, and the skill name changes from `devostat` to `start`. Both the source directory rename (`skills/devostat/` → `skills/start/`) and Maven `plugin.skillName` property updates are required.

## Tasks

### Task 1: Update Maven POMs — artifact, properties, profiles [High]

Update the root `pom.xml` and all module POMs. This is highest-risk because every downstream artifact (plugin.json, marketplace.json, SKILL.md, commands/*.toml) derives from properties defined here. If these are wrong, subsequent tasks multiply the error.

Changes in `/opt/workspace/devostat/pom.xml`:
- `<artifactId>devostat</artifactId>` → `<artifactId>shipsmooth</artifactId>`
- All 3 profiles: `<plugin.name>` values (`devostat-dev` → `shipsmooth-dev`, `devostat` → `shipsmooth`)
- All 3 profiles: `<plugin.skillName>` values (`devostat-dev` → `start-dev` in dev profile; `devostat` → `start` in prod and gemini profiles)
- All 3 profiles: property name `<devostat.dist.path>` → `<shipsmooth.dist.path>`
- Gemini profile: cache path `~/.cache/devostat/dist` → `~/.cache/shipsmooth/dist`
- Gemini profile: YAML frontmatter `name: devostat` → `name: start`; description text references to "devostat" → "shipsmooth"
- `<plugin.description>` text: mentions of "devostat" → "shipsmooth" (if any; current value may be name-free)

Module POMs (`plugin-resources/pom.xml`, `plugin-node/pom.xml`, `plugin-devel/pom.xml`, `plugin-dist/pom.xml`): update `<parent>` artifactId references and any own `<artifactId>` that contains "devostat".

### Task 2: Rename source skill directory and update SKILL.md [High]

- Rename `plugin-resources/src/main/resources/skills/devostat/` → `plugin-resources/src/main/resources/skills/start/`
- Inside the renamed directory's `SKILL.md`: replace all `${devostat.dist.path}` → `${shipsmooth.dist.path}` (11+ occurrences), update any product-name references from "devostat" to "shipsmooth", and update skill invocation examples from `/devostat:devostat` to `/shipsmooth:start`

Risk: High because SKILL.md is the runtime contract — a broken reference here means the skill fails at invocation time, not build time.

### Task 3: Update plugin manifests and marketplace [High]

- `/opt/workspace/devostat/.claude-plugin/plugin.json`: `"name": "devostat"` → `"shipsmooth"`; `homepage` and `repository` URLs → `bitkentech/shipsmooth`
- `plugin-resources/src/main/resources/claude-plugin/plugin.json`: confirm variables (`${plugin.name}` etc.) resolve correctly; update any hardcoded strings
- `plugin-resources/src/main/resources/claude-plugin/marketplace.json`: `"name": "devostat-dev"` → `"shipsmooth-dev"`; description text; owner URL

### Task 4: Rename commands/devostat.toml and update Gemini hooks [Medium]

- Rename `plugin-resources/src/main/resources/gemini-extension/commands/devostat.toml` → `commands/start.toml`
- Update internal references inside the file
- `plugin-resources/src/main/resources/gemini-extension/hooks/hooks.json`: update `${HOME}/.cache/devostat` → `${HOME}/.cache/shipsmooth` and log string `devostat: deps installed` → `shipsmooth: deps installed`

### Task 5: Update release and smoke-test scripts [Medium]

- `scripts/release.sh`: plugin name references
- `scripts/release-gemini.sh`: hardcoded `bitkentech/devostat-gemini` → `bitkentech/shipsmooth-gemini` (multiple lines)
- `scripts/smoke-gemini.sh`: extension name, cache paths (`~/.cache/devostat`, `~/.gemini/extensions/devostat`)
- `scripts/test-gemini-hook.sh`: cache paths and log strings

### Task 6: Update package.json files [Low]

- `plugin-node/package.json`: `name` and `description`
- `plugin-devel/package.json`: `devostat-devel` → `shipsmooth-devel`

### Task 7: Update user-facing docs [Low]

- `README.md`: title (`## devostat - ...` → `## shipsmooth - ...`), install/invocation commands, repo URLs. Keep existing tagline unchanged per user's direction.
- `DEVELOPMENT.md`: all occurrences (build dir descriptions, skill dir names, cache paths, release script description, Gemini release repo URL)
- `plugin-resources/src/main/resources/gemini-extension/README.md`: all textual references
- `docs/demo.cast`, `docs/demo-1/demo.cast`, `docs/demo-30s.cast`: literal `/devostat:devostat` → `/shipsmooth:start` where they appear as typed commands. Demo gif files stay as-is (regenerating is out of scope).

### Task 8: Rebuild, smoke-test, and verify no lingering `devostat` strings [Medium]

- `mvn clean process-resources` (Claude profile) — verify `build/.claude-plugin/plugin.json` shows `"name": "shipsmooth"` and `build/skills/start/SKILL.md` exists
- `mvn -P gemini,'!dev','!claude' process-resources` (Gemini profile) — verify `build-gemini/gemini-extension.json`, `build-gemini/skills/start/SKILL.md`, `build-gemini/commands/start.toml`
- `./scripts/smoke-gemini.sh` passes
- Case-insensitive grep for "devostat" across the repo (excluding `.git/`, `.agents/`, `build/`, `build-gemini/`) returns zero matches outside intentional history
- Manual end-to-end install-and-invoke in Claude Code dev mode: update `~/.claude/settings.json` to use `shipsmooth-dev`, restart Claude Code, invoke `/shipsmooth-dev:start` and confirm the skill loads

### Task 9: Rename GitHub repos and update marketplace listing [Low]

- Rename `github.com/bitkentech/devostat` → `github.com/bitkentech/shipsmooth` via GitHub web UI
- Rename `github.com/bitkentech/devostat-gemini` → `github.com/bitkentech/shipsmooth-gemini` via GitHub web UI
- Update local remote: `git remote set-url origin git@github.com:bitkentech/shipsmooth.git`
- Update the `bitkentech/claude-plugins` marketplace entry that references this plugin (separate repo — human decides whether to do in this session or later)

Risk: Low because it's mechanical and GitHub auto-redirects old URLs. Done after the local work so any mistakes can be fixed before pushing to renamed repos.

## Verification

End-to-end verification after all tasks:

1. **Claude Code build:** `mvn clean process-resources` produces `build/skills/start/SKILL.md` and `build/.claude-plugin/plugin.json` with `"name": "shipsmooth"`.
2. **Gemini build:** `mvn -P gemini,'!dev','!claude' process-resources` produces `build-gemini/skills/start/SKILL.md`, `build-gemini/commands/start.toml`, `build-gemini/gemini-extension.json` with `"name": "shipsmooth"`.
3. **Smoke test:** `./scripts/smoke-gemini.sh` exits 0.
4. **No lingering references:** Grep tool, case-insensitive, pattern `devostat`, excluding `.agents/`, `.git/`, `build/`, `build-gemini/`, `plugin-resources/build-gemini/`, `plugin-dist/build/`, `plugin-dist/build-gemini/`, `gemini-extension-spike/` → zero matches.
5. **End-to-end skill invocation:** in Claude Code dev mode with updated settings.json, `/shipsmooth-dev:start` loads the skill.
6. **GitHub rename verified:** `git push` to new remote URL succeeds; old URL redirects.
