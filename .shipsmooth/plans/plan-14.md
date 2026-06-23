# Plan 14: Docs enhancement — README overhaul + DEVELOPMENT.md split

## Context
The README grew incrementally as features landed (plans 5–13). It currently buries user-facing value under developer setup instructions and mentions Linear as the primary task backend. Now that plan 13 delivered local XML-based task tracking, the README should lead with what the plugin does — local task management first, Linear as an optional integration — and move the dev/build docs to a separate file.

**Linear feature issue:** PB-240

## Design decisions

- **Local-first framing:** README "What it does" section positions local XML task tracking as the default, with Linear mentioned parenthetically as an option for teams already using it.
- **Dev docs split:** All build/dev/registration instructions move verbatim to `DEVELOPMENT.md`. README links to it with a one-liner.
- **Hero image placeholder:** An HTML comment + italic note where a GIF/screenshot will go. No actual media — that's a manual step.
- **Principles section:** Kept but trimmed to a tight paragraph.

## Tasks (risk-sorted: High → Medium → Low)

### Task 1: Create DEVELOPMENT.md from existing README dev sections [Low]
- Extract from README: Prerequisites, Repo structure, Build the dev version, Register the dev build, Usage, Switching back to production, Notes
- Move content as-is into new `DEVELOPMENT.md` with a brief intro line
- No content changes, just relocation

### Task 2: Rewrite README.md [Low]
- Hero image placeholder (HTML comment + italic TODO note)
- One-line description: Claude Code plugin for plan-driven, risk-prioritised agentic coding
- "What it does" section: 4 bullets — local XML task tracking (primary), optional Linear integration, risk categorisation, pause/resume via plan files
- "Installation" section: keep existing marketplace instructions unchanged
- "How the workflow works" section: brief narrative (plan → calibrate → execute → closeout), reference SKILL.md
- "Development" section: single line linking to DEVELOPMENT.md
- "Principles" section: keep existing Spiral Model / Agile paragraph, trim if verbose

## Files to create/modify
- `DEVELOPMENT.md` — new (extracted from README)
- `README.md` — rewrite

## Verification
1. Read both files — confirm no dev content lost in the split
2. Confirm README leads with local task tracking, mentions Linear in parentheses
3. Confirm DEVELOPMENT.md has all dev setup instructions intact
4. `git diff` to review
