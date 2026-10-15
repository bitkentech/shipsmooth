# plan-116 — Plugin author → Bitken Technologies (all hosts)

**Backlog feature:** consistent Bitken Technologies product identity across every
shipsmooth distribution (local backlog; no external issue).

## Context

The kickoff asked to change the **beta** plugin author from "Pramod Biligiri" to
"Bitken Technologies". Investigation showed the beta Claude manifest has no file of
its own: `copyClaudeMetaBeta` renders the shared template
`harness/claude/src/main/resources/claude-plugin/plugin.json` with `betaTokens`, and
the author there is a hardcoded literal, not a token. The installed 0.1.0-beta
manifest (`~/.claude/plugins/cache/bitkentech/shipsmooth-beta/0.1.0-beta/.claude-plugin/plugin.json`)
confirms it ships `"author": {"name": "Pramod Biligiri"}`.

**Decision (user, 2026-10-15): change it everywhere, not beta-only.** Everything
else about the product is already Bitken — the `bitkentech` GitHub org and
marketplace, `marketplace.json` owner/author ("Bitken Technologies"), the
`@bitkentech` npm scope. A beta-only `${plugin.author}` token would add a
per-product divergence to maintain for no benefit.

### In scope — the four plugin manifests carrying the author

| File | Feeds |
|---|---|
| `harness/claude/src/main/resources/claude-plugin/plugin.json` | Claude dev, prod, Windows and beta renders (shared template) |
| `.claude-plugin/plugin.json` | root dev manifest |
| `harness/codex/src/main/resources/codex-plugin/.codex-plugin/plugin.json` | Codex plugin |
| `harness/opencode/src/main/ts/manifest/package.json` | OpenCode npm package |

### Explicitly out of scope (user decision)

- `LICENSE:175` copyright line — a legal statement, untouched.
- `README.md:144` "developed by" credit — personal attribution, untouched.
- `docs/` bylines, session logs, Maven-era `groupId`s.

### Release impact

Nothing changes for installed users until the next releases: prod (next
`plugin.version` patch), beta (next Cargo workspace patch, e.g. `0.1.1-beta`), and
OpenCode only via an npm publish. Cutting releases is not part of this plan. The
pending beta marketplace entry in `bitkentech/claude-plugins` (outside this repo)
should use the same author when it is added.

## Tasks

### Task 1: Set plugin author to Bitken Technologies in all manifests [Low]

Add a guard test asserting every in-scope manifest's `author.name` is
"Bitken Technologies" — including the **rendered** beta and prod Claude
`plugin.json`, so the shared-template path is covered — commit it red, then edit
the four literals. Risk Low: four one-line JSON string edits; no rendering logic
changes, the template already flows unchanged through every token set.
