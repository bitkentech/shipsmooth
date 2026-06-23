# Plan 09 — Canonicalize README install instructions

## Narrative

The README currently documents two install paths, neither fully correct:

1. **Marketplace path** (lines 5–9) — `/plugin marketplace add ...` then `/plugin install
   code-flow@pramodb-plugins`. This works (confirmed in
   `~/.claude/plugins/installed_plugins.json`), but the two-step flow could use a brief
   explainer so users understand why both commands are needed.

2. **Manual curl path** (lines 11–16) — copies SKILL.md to `~/.claude/skills/code-flow/`.
   This is stale: it targets the old personal-skill location, not the plugin cache. It
   also skips `plugin.json`, so the skill won't appear in `/plugin list`.

A third path was proposed in plan-07 (`/plugin install code-flow@github:pramodbiligiri/code-flow`)
but was never implemented.

The README should have one canonical install path — the marketplace flow — and drop the
stale manual alternative entirely.

## Delivers

- [PB-123 — Improve code-flow repo and packaging/distribution](https://linear.app/pb-default/issue/PB-123/improve-code-flow-repo-and-packaging-dist-references)

## Tasks

### Task 1 — Rewrite README Installation section

Edit `README.md`:

1. Keep the marketplace install as the only method. Add a brief explainer so users
   understand the two-step flow (register marketplace, then install plugin).

2. Remove the manual curl section entirely (lines 11–16). Manual plugin installation
   is fragile — it bypasses the plugin system's cache management and metadata.

No other files change. No tests required (documentation-only change).

## Verification

- Read the updated README and confirm the install instructions match the actual working
  flow observed in `~/.claude/plugins/installed_plugins.json`
- Confirm no stale paths remain (no `~/.claude/skills/` references, no curl commands,
  no `@github:pramodbiligiri/code-flow` syntax)
