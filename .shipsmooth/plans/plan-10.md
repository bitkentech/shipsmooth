# Plan 10: Update README marketplace installation URL to short form

## Context
The README installation instructions use a full GitHub URL for the `/plugin marketplace add` command.
Claude Code supports the shorter `owner/repo` shorthand for GitHub URLs, which is the canonical and preferred form.

## Change
- **File:** `README.md`, line 20
- Replace `https://github.com/pramodbiligiri/claude-plugins` → `pramodbiligiri/claude-plugins`

## Scope
Doc-only change. No tests required.
