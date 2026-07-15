# plan-101 — improve Github README

## Context

Bring README.md in line with https://www.shipsmooth.net (homepage, /faq/,
/concepts/{iterate,risky-bits,vertical-slices}/, /install/, /docs/cli/), which is more
up to date than the README. Website compared against README on 2026-07-15.

### Findings (website vs README)

**Stale / wrong content:**
1. `.agents/plans/` paths are stale everywhere (lines 12, 23, 27, 109) — repo migrated to
   `.shipsmooth/` in plan-85, and the default storage is now a *separate* state directory,
   not in-repo. The example links `.agents/plans/plan-17.md` / `plan-17-tasks.xml` are broken.
2. Line 17: "You can read the full workflow at [SKILL.md]()" — empty link target; duplicates the line-10 link.
3. Uninstall section says "Per-repo `.agents/` directories contain your plan history" — stale name, ignores separate-dir mode.
4. "Local task tracking … checked in alongside the plan" contradicts the current default (separate-dir, zero-trace).

**Missing content the website has:**
5. No link to https://www.shipsmooth.net — add prominently (top) and link FAQ / concepts / CLI docs.
6. Zero-trace headline feature: "doesn't leave any traces in your project's code repository".
7. Storage choice: same-repo vs separate git repo, configured via `shipsmooth.toml` (FAQ).
8. The 3 named concepts (Iterate, Risky bits, Vertical slices) with dedicated pages and real
   examples (plan-91 iterations, plan-59 slice). Restructure the "Features (aspirations?)"
   section around them; drop the "(aspirations?)" hedge.
9. Crisper "what is it" from FAQ: "a skill file and a CLI tool, packaged as a plugin for Claude, Codex etc."
10. CLI reference exists (/docs/cli/) — link it.
11. Tech stack (mostly Java + mini-runtime, a little TypeScript/shell), license (Apache 2.0),
    maturity ("early stages", solo developer).
12. Homepage tagline: "Make plans, but with the freedom to change them. Tackle risky bits first.
    Build vertical slices." — candidate opener.

**Limitations drift:**
13. Website FAQ lists 3 limitations (single plan file; interactive-mode tested; sequential plan
    version numbers). README lists 2 older ones. Align with FAQ wording.

**README is ahead of the site (keep):**
14. Windows install section (site has none).
15. Uninstall section + no-Node/no-JDK bootstrap details.
16. Site says "restart Claude Code" after plugin install; README omits it — add.

### User directives (2026-07-15)

- Move "Features (aspirations?) of the workflow" ahead of "How to use the workflow".
- No `.agents` anywhere: README text **and the demo cast/GIF** must show `.shipsmooth`.
- Installation: harness-name headings must render smaller than the "Installation" heading.
- The runtime-download prose (SessionStart hook + no-Node/no-JDK paragraphs, currently wedged
  between the Codex and OpenCode subsections) is out of place — relocate to a harness-agnostic
  spot at the edge of the Installation section.

### Approach notes

- Demo: `docs/demo.cast` (source) has 4 `.agents` occurrences; `agg` and `asciinema` are
  installed. Plan A is to rewrite the cast text and re-render `demo-small.gif` with `agg` —
  no re-recording. Risk: `.agents` (7 chars) → `.shipsmooth` (11 chars) changes line lengths
  inside recorded frames; rendering may misalign. Verify the GIF visually; fall back to
  re-recording only if unacceptable.
- Scope decision: targeted rewrite of README (reorder + fix + add), not a from-scratch rewrite
  mirroring the site's IA.
- TDD invariant applies "as far as possible": this is a docs/content plan with no code surface.
  Verification = markdown link check (all README links resolve), rendered-heading inspection,
  and visual check of the regenerated GIF. No integration-test preamble.

## Tasks

### Task 1: Regenerate demo cast and GIF with .shipsmooth paths [Medium]
Rewrite the 4 `.agents` occurrences in `docs/demo.cast` to `.shipsmooth`, re-render
`docs/demo-small.gif` with `agg` (match previous size/theme settings), and verify the frames
render cleanly despite the longer path string. Fall back to re-recording if alignment breaks.

### Task 2: Restructure README and fix stale content [Low]
Reorder "Features …" ahead of "How to use the workflow"; drop the "(aspirations?)" hedge;
demote/restyle harness headings under Installation; relocate the runtime-download prose to a
harness-agnostic note in Installation; replace every `.agents` reference with the current
`.shipsmooth` / separate-dir reality; fix the empty `[SKILL.md]()` link and the broken
plan-17 example links; fix the Uninstall section's stale paths; add the missing
"restart Claude Code" step.

### Task 3: Add website-derived content [Medium]
*Depends-on: Task 2*
Add the site link prominently at top; adopt the tagline as opener; introduce the three
concepts (Iterate / Risky bits / Vertical slices) with links to their pages; state the
zero-trace default and the same-repo vs separate-dir storage choice (`shipsmooth.toml`);
align the Limitations section with the FAQ's three; add the FAQ's one-line definition,
CLI docs link, tech stack, Apache 2.0 license, and maturity note.

## Backlog Issue

None recorded — backlog concept is being retired (PB-371); recent plans leave this empty.
