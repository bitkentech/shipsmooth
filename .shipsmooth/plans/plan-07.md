# Plan 07 — Package code-flow as a Shareable Plugin

## Narrative

The `code-flow` skill currently lives as a standalone git repo with `SKILL.md` at the root. It is
installed personally via `~/.claude/skills/code-flow/SKILL.md`. To make it shareable — installable
by others via `/plugin install` or submittable to the official `anthropics/claude-plugins-official`
marketplace — it needs to be restructured as a Claude Code plugin.

A plugin is the standard distribution unit for Claude Code. It requires:
- A `.claude-plugin/plugin.json` manifest at the repo root
- Skills nested under `skills/<skill-name>/SKILL.md`

## Design Decisions

- Move `SKILL.md` → `skills/code-flow/SKILL.md`. The root location is not required for personal
  installs (those read from `~/.claude/skills/`), so moving it only affects the repo layout.
- The personal install at `~/.claude/skills/code-flow/SKILL.md` is kept in sync manually after the
  move (copy the file).
- `.agents/plans/` and `.claude/settings.local.json` are untouched — they are execution history and
  local dev config, not plugin content.
- Apache 2.0 LICENSE file added at repo root (required for plugin distribution).
- README gets an Installation section explaining how others can install the plugin.

## Tasks

### Task 1 — Create `.claude-plugin/plugin.json`
Create the plugin manifest. Fields: name, description, author, homepage, repository.

### Task 2 — Move `SKILL.md` into `skills/code-flow/`
Create `skills/code-flow/` directory and move `SKILL.md` there. No content changes to SKILL.md.

### Task 3 — Update README with installation instructions
Add an **Installation** section to `README.md` explaining:
- Install via Claude Code: `/plugin install code-flow@github:pramodbiligiri/code-flow`
- Or manually: copy `skills/code-flow/SKILL.md` to `~/.claude/skills/code-flow/SKILL.md`

### Task 4 — Sync personal skill install
Copy `skills/code-flow/SKILL.md` to `~/.claude/skills/code-flow/SKILL.md` to keep personal install
current. (Cancelled — not needed before merge review.)

### Task 5 — Add Apache 2.0 LICENSE file
Add a `LICENSE` file at the repo root with the standard Apache 2.0 license text.

## Open Questions

- None. Scope is clear from studying the official plugin examples (`skill-creator`, `feature-dev`).

## References

- Official plugin examples: `~/.claude/plugins/marketplaces/claude-plugins-official/plugins/`
- Plugin manifest schema: `.claude-plugin/plugin.json` (no formal JSON schema required for basic plugins)
- Personal skill install location: `~/.claude/skills/`
