# Plan 19 — Port devostat plugin to Gemini CLI

## Context

The devostat plugin currently ships as a Claude Code plugin (sources in `plugin-resources/src/main/resources/`, `plugin-node/`, `plugin-dist/`; Maven assembles into gitignored `build/`). When a long Claude Code session hits its token ceiling mid-workflow, the user wants to continue the same plan on Gemini CLI on the same repo. That requires the same skill, same slash command, same task scripts, and the same `.agents/plans/*.xml` format to be available there.

Gemini CLI extensions (stable as of the 2025 release) support every Claude Code primitive devostat uses: JSON manifest, `skills/NAME/SKILL.md`, `commands/*.toml` slash commands, `hooks/hooks.json` with `SessionStart`, and `mcpServers`. The skill-activation mechanism differs in one key way: Gemini requires YAML frontmatter (`name` + `description`) in each SKILL.md for discovery; Claude Code uses `plugin.json` instead.

**Permanent backlog feature issue:** [PB-268 — *Multi-host plugin packaging (Claude Code + Gemini CLI)*](https://linear.app/pb-default/issue/PB-268/multi-host-plugin-packaging-claude-code-gemini-cli) in project *devostat — Backlog & Roadmap*. plan-19 is the first slice — the Gemini CLI target. Future hosts (Copilot CLI, Codex) can be added under the same backlog issue as separate plans.

### Build model

Existing Maven graph:

- `plugin-resources` — copies/filters `SKILL.md`, `plugin.json`, `marketplace.json`, `hooks.json` into `build/`.
- `plugin-node` — `npm install` + `tsc` to `plugin-node/dist/`.
- `plugin-dist` — aggregator; copies compiled JS + TS sources into `build/scripts/tasks/`.

Most module poms hardcode `${project.parent.basedir}/build/...` as their output directory. To emit a Gemini tree without duplicating Maven config, we parameterise the output directory (the root pom already declares `${build.outputDir}` — the modules just don't use it yet) and add a `gemini` profile that redirects to `build-gemini/` with a different resource set.

### Compatibility mapping (Claude Code → Gemini CLI)

| Claude Code | Gemini CLI | Change needed |
|---|---|---|
| `.claude-plugin/plugin.json` | `gemini-extension.json` at extension root | New file, new schema |
| `.claude-plugin/marketplace.json` | n/a | Skip — Gemini installs via git URL or local path |
| `skills/devostat/SKILL.md` | `skills/devostat/SKILL.md` | Gemini version needs YAML frontmatter + different script path |
| `hooks/hooks.json` SessionStart | `hooks/hooks.json` SessionStart | Same schema; replace `${CLAUDE_PLUGIN_ROOT}` with `${extensionPath}` and `${CLAUDE_PLUGIN_DATA}` with `${HOME}/.cache/devostat` |
| Auto-generated `/devostat:devostat` command | Explicit `commands/devostat.toml` | Gemini doesn't auto-register a command per skill — author one |
| Linear MCP via user's `~/.claude/settings.json` | Linear MCP via `~/.gemini/settings.json` | User-level concern, out of scope for this plan — document in README only |

### Resolved decisions

- **Skill activation on Gemini:** On-demand only. Omit `contextFileName` from the manifest so SKILL.md is only injected via `activate_skill` when the model deems it relevant. Matches Claude Code; avoids burning context on every session.
- **Build output location:** `build-gemini/` is gitignored and produced by `mvn -P gemini package`. Not committed. Same pattern as `build/`.
- **Task tracking:** `[Local]` XML at `.agents/plans/plan-19-tasks.xml`, matching plans 17 and 18.
- **Coverage threshold:** 95% where tests exist. Most tasks are config/docs with no TDD surface — deviations will be recorded in the XML per task, same pattern as plan-18 task 1.

### Key risks (updated after spike)

1. **Script path differs per host.** Claude Code uses `${CLAUDE_PLUGIN_DATA}/dist/` (Maven-filtered). Gemini has no equivalent env var, and hook env vars don't persist into the session (spike finding #2). Solution: introduce Maven property `${devostat.dist.path}` — Claude profiles set it to `\${CLAUDE_PLUGIN_DATA}/dist` (escaped so Maven doesn't resolve it), Gemini profile sets it to `~/.cache/devostat/dist`.
2. **SKILL.md frontmatter.** Gemini requires `---` frontmatter with `name` and `description` for skill discovery. Claude Code doesn't. Solution: the Gemini SKILL.md template includes a frontmatter block gated by a Maven property or placed in a Gemini-specific source overlay.
3. **Skill-body portability.** Beyond the cache path, the skill body says "Requires the plugin's SessionStart hook to have run (installs `fast-xml-parser` and compiles…)". This is still true on Gemini, but the wording currently implies Claude Code specifics.

### Spike findings reference

Full details in `.agents/learnings/plan-19-spike-notes.md`. Summary of what changed vs. v1:
- `$DEVOSTAT_DIST` env var approach abandoned for Gemini (finding #2)
- SKILL.md frontmatter requirement discovered (finding #1)
- `init.js` plan parser may need future hardening for Gemini-authored plans (finding #3, not in scope)
- `gemini extensions link` metadata-only dir behavior confirmed correct (finding #4)

---

## Risk-sorted tasks

Eight thin vertical slices, ordered High → Low. Task 0 was a throwaway spike — verified end-to-end (see spike notes). Tasks 1–7 productionise it via Maven.

### Task 0: Hand-authored Gemini extension spike [High] — COMPLETE

Verified: extension structure valid, SessionStart hook compiles scripts, skill discovered via frontmatter, slash command registered, full devostat workflow ran in Gemini CLI. Commit: `c6e05c9`.

### Task 1: Profile-specific SKILL.md script paths + Gemini frontmatter [High]

*Why High:* Touches the SKILL.md contract that every downstream plan loads. A typo here breaks every future plan on both hosts.

- Root `pom.xml`: add Maven property `devostat.dist.path` to each profile:
  - `dev` and `prod` profiles: `<devostat.dist.path>\${CLAUDE_PLUGIN_DATA}/dist</devostat.dist.path>` (backslash-escaped so Maven preserves the literal `${CLAUDE_PLUGIN_DATA}` in filtered output).
  - `gemini` profile (created in Task 5, but property defined here): `<devostat.dist.path>~/.cache/devostat/dist</devostat.dist.path>`.
- Edit `plugin-resources/src/main/resources/skills/devostat/SKILL.md`:
  - Replace every `${CLAUDE_PLUGIN_DATA}/dist/` (11 occurrences) with `${devostat.dist.path}/`.
  - This single template produces the correct path for both hosts at build time.
- Gemini frontmatter: add a `${skill.frontmatter}` placeholder at the very top of the SKILL.md template (before the `#` heading). Claude profiles set `<skill.frontmatter></skill.frontmatter>` (empty — no frontmatter emitted). Gemini profile sets it to the YAML frontmatter block using a multiline XML property value — `---` is plain text to Maven:
  ```xml
  <skill.frontmatter>---
  name: devostat
  description: Use when starting any task — applies the devostat agent coding workflow.
  ---
  </skill.frontmatter>
  ```
- Claude hook (`plugin-resources/src/main/resources/hooks/hooks.json`): **no changes** — the `$DEVOSTAT_DIST` env var approach is abandoned. The hook stays as-is.
- Verification: `mvn process-resources` regenerates `build/skills/devostat/SKILL.md` — confirm it still contains literal `${CLAUDE_PLUGIN_DATA}/dist/` (not resolved) and has no frontmatter at top. Launch a fresh Claude Code session; confirm scripts still work.
- Test coverage: skip — skill body is documentation. Precedent in plan-18 task 1.

### Task 2: Parameterise Maven output directory [High]

*Why High:* Changes the root pom and two child poms' output-directory references. A mistake risks the `build/` output landing in the wrong place and breaking Claude Code installs.

- Root `pom.xml`: both `dev` and `prod` profiles already define `<build.outputDir>${project.basedir}/build</build.outputDir>` — keep as-is.
- `plugin-resources/pom.xml`: replace all four `${project.parent.basedir}/build/` occurrences in `<outputDirectory>` with `${build.outputDir}/`.
- `plugin-dist/pom.xml`: replace the two `${project.parent.basedir}/build/` occurrences likewise.
- `plugin-node/pom.xml`: no output-dir changes (it writes to its own module's `dist/`, consumed downstream).
- Verification: `mvn process-resources` with default (`dev`) profile produces an identical `build/` tree byte-for-byte (compare with `diff -r` against a pre-change copy). Claude Code still installs and runs without regression.
- Test: pre/post diff of `build/` is the acceptance test. No new unit tests.

### Task 3: New Gemini manifest + commands source tree [Low]

*Why Low:* Pure additive files with fixed schemas. Spike confirmed the exact format.

- New source dir: `plugin-resources/src/main/resources/gemini-extension/`
  - `gemini-extension.json` — fields: `name: "${plugin.name}"`, `version: "${project.version}"`, `description: "${plugin.description}"`. No `contextFileName` (on-demand activation). No `mcpServers` (user-level, documented).
  - `commands/devostat.toml`:
    ```toml
    description = "Apply the devostat agent coding workflow."
    prompt = "Activate the devostat skill and apply it to the user's current task."
    ```
- Verification: `jq .` on the filtered `build-gemini/gemini-extension.json`; TOML validator on `build-gemini/commands/devostat.toml`.

### Task 4: New Gemini hook [High]

*Why High:* Single most host-specific file. The cache-dir derivation, `mkdir -p`, and `${extensionPath}` substitution must all be right on first run.

- New file: `plugin-resources/src/main/resources/gemini-extension/hooks/hooks.json`. Key differences from the Claude version:
  - `${extensionPath}` is substituted by Gemini in hooks.json (confirmed from Gemini variable docs). Use it to copy pre-compiled JS.
  - `${CLAUDE_PLUGIN_DATA}` → `${HOME}/.cache/devostat` as the stable install target.
  - **No `tsc` at install time** — compiled JS ships in `build-gemini/dist/` (Maven pre-compiles via `plugin-node`). The hook only runs `npm install` + copies JS files.
  - Hook command pattern:
    ```
    diff -q "${extensionPath}/package.json" "${HOME}/.cache/devostat/package.json" >/dev/null 2>&1
    || (mkdir -p "${HOME}/.cache/devostat"
        && cp "${extensionPath}/package.json" "${HOME}/.cache/devostat/"
        && cd "${HOME}/.cache/devostat" && npm install --silent
        && mkdir -p "${HOME}/.cache/devostat/dist"
        && cp "${extensionPath}"/dist/*.js "${HOME}/.cache/devostat/dist/"
        && echo "devostat: deps installed")
    || rm -f "${HOME}/.cache/devostat/package.json"
    ```
- Verification: `gemini extensions link build-gemini/` then start `gemini` in a test repo; confirm hook fires, `~/.cache/devostat/dist/init.js` exists, and `node ~/.cache/devostat/dist/init.js --help` works.
- Test: TDD-able via a shell script that invokes the hook command directly with a fake `${extensionPath}` populated with real JS files and asserts the expected files exist at `~/.cache/devostat/dist/`. Write the assertion script first.

### Task 5: Gemini Maven profile + aggregator wiring [High]

*Why High:* Maven multi-module plumbing is where subtle ordering bugs hide. The Gemini profile must redirect `${build.outputDir}` to `build-gemini/` *and* point the resource copies at the new `gemini-extension/` source tree.

- Root `pom.xml`: add a third profile `gemini` with:
  - `<build.outputDir>${project.basedir}/build-gemini</build.outputDir>`
  - `<devostat.dist.path>~/.cache/devostat/dist</devostat.dist.path>`
  - `<plugin.name>devostat</plugin.name>` (same as prod)
  - `<plugin.skillName>devostat</plugin.skillName>`
  - `<plugin.description>...</plugin.description>` (same as prod)
  - Multiline XML property value (see Task 1 for exact format — `---` is plain text to Maven)
- `plugin-resources/pom.xml`: parameterise the source dir (`${plugin.meta.sourceDir}` or similar) for the `copy-plugin-meta` execution, defaulted to `claude-plugin`; the `gemini` profile overrides it to `gemini-extension`. Add new executions for:
  - `copy-gemini-manifest` — copies `gemini-extension.json` to `${build.outputDir}/`
  - `copy-commands` — copies `commands/` to `${build.outputDir}/commands/`
  - `copy-gemini-hooks` — copies `gemini-extension/hooks/` to `${build.outputDir}/hooks/`
  - These executions only run when the Gemini profile is active.
- `plugin-dist/pom.xml`: add a `copy-dist` execution (Gemini profile only) that copies `plugin-node/dist/*.js` to `${build.outputDir}/dist/`. This is in addition to `copy-scripts` + `copy-ts-source` (which remain for both profiles). The Gemini hook copies from `${extensionPath}/dist/` — this is the source.
- Add `build-gemini/` to `.gitignore`.
- Verification: `mvn -P gemini process-resources` produces `build-gemini/` with the full expected layout. `mvn process-resources` (default `dev`) still produces `build/` identical to before. Run both in sequence and compare with `diff -r`.

### Task 6: Gemini smoke-test script [Low]

*Why Low:* Additive verification harness; no production-path code.

- New script `scripts/smoke-gemini.sh` (or Makefile target) that:
  1. Runs `mvn -P gemini package`.
  2. Creates a tmp test repo with a minimal `.agents/plans/` skeleton.
  3. Runs `gemini extensions link build-gemini/`.
  4. Starts `gemini` non-interactively (if the CLI supports it) and asserts the SessionStart hook output appears.
- Verification: the script exits 0 on a clean machine with `gemini` installed; documents the install steps if it isn't.

### Task 8: Fix npm install in Gemini hook to use --prefix [Low]

*Why Low:* One-line fix to an existing file. Risk is low — `--prefix` is standard npm and well-documented.

*Why needed:* The current hook runs `cd "${HOME}/.cache/devostat" && npm install --silent`. If the user's environment sets `npm_config_prefix` or `npm_config_cache` to non-default locations (e.g. via NVM configs), `node_modules` may not land in `~/.cache/devostat/`. Node resolves `require('fast-xml-parser')` by walking up from `~/.cache/devostat/dist/` — so `node_modules` must be at `~/.cache/devostat/node_modules/` regardless of user npm config.

- Edit `plugin-resources/src/main/resources/gemini-extension/hooks/hooks.json`:
  - Replace `cd "${HOME}/.cache/devostat" && npm install --silent` with `npm install --prefix "${HOME}/.cache/devostat" --silent`
  - Drop the `cd` — `--prefix` pins both the install location and `node_modules` dir.
- Re-run `scripts/test-gemini-hook.sh` to confirm assertions still pass.
- Re-run `scripts/smoke-gemini.sh` to confirm end-to-end still passes.
- Test coverage: existing `test-gemini-hook.sh` assertion script covers this.

### Task 7: Docs - install-on-Gemini section [Low]

*Why Low:* Documentation only.

- `README.md`: new "Install on Gemini CLI" subsection under the existing "Installation". Include `gemini extensions install https://github.com/bitkentech/devostat` and the Linear MCP settings.json snippet.
- `DEVELOPMENT.md`: mirror the Claude development flow for Gemini (`mvn -P gemini package`, `gemini extensions link`, uninstall command).
- Verification: read-through coherence check.

---

## Critical files to read before execution

- `plugin-resources/src/main/resources/skills/devostat/SKILL.md` — every `${devostat.dist.path}` replacement point is here.
- `plugin-resources/src/main/resources/hooks/hooks.json` — canonical shape of the Claude hook (unchanged in this plan).
- `plugin-resources/pom.xml` — the resource-filtering template to generalise for Gemini.
- `plugin-dist/pom.xml` — shows how compiled scripts land in `build/`; unchanged after task 2.
- `pom.xml` (root) — profile declarations go here.
- `.agents/learnings/plan-19-spike-notes.md` — full spike findings.

---

## Verification (end-to-end)

1. `mvn -P gemini package && mvn package` — both builds succeed. `diff -rq build/ build-gemini/` shows only the expected differences (manifest, `commands/` dir, hook env-var substitutions, SKILL.md frontmatter and script paths).
2. **Claude Code regression:** `claude` in a throwaway repo — devostat loads, SessionStart hook runs, `node ${CLAUDE_PLUGIN_DATA}/dist/init.js` works, an existing plan's XML can be read via `show.js`. Must be identical to pre-plan behaviour.
3. **Gemini happy path:** `gemini extensions link build-gemini/` + `gemini` in the same throwaway repo — hook runs, skill auto-activates, `/devostat` slash command appears, the devostat scripts run via `node ~/.cache/devostat/dist/init.js`.
4. **Cross-host handoff (the actual user goal):** Start Phase 1 of a dummy plan in Claude Code, commit the plan file + XML, then open Gemini CLI on the same repo and ask it to resume — it reads `.agents/plans/plan-{N}.md` + `plan-{N}-tasks.xml` and picks up at the next task.
