# Plan 16 — GitHub Release Build Process for devostat (PB-255)

**Status:** IN PROGRESS (adding Task 5)
**Linear issue:** PB-255 (child of PB-123)
**Branch:** t/pb-255-github-release-build
**Linear project:** [[agent] 16 · github-release-build](https://linear.app/pb-default/project/agent-16-github-release-build-cb1f6ccae416)

---

## Context

The devostat plugin has no release pipeline. The production build (`mvn process-resources -Pprod`) produces a `build/` directory that is gitignored and never committed. The README advertises installation via `/plugin marketplace add pramodbiligiri/claude-plugins` → `/plugin install devostat@pramodb-plugins`, but the marketplace currently pins to a specific SHA on main where compiled files don't exist. Users can't actually install the plugin without the built artifacts being available at a cloneable git ref.

---

## Decision: Orphan `releases` branch + GitHub Releases

After exploring the Claude Code plugin marketplace docs, the supported plugin source types are: relative path, `github`, `url`, `git-subdir`, and `npm`. GitHub Release ZIPs are **not** supported — sources must be git-cloneable.

**Chosen approach:**
- An **orphan branch** called `releases` holds only built plugin output in a `dist/` folder
- The marketplace.json in `pramodbiligiri/claude-plugins` uses `git-subdir` source pointing to `ref: "releases"`, `path: "dist"` — users always get the latest release
- **GitHub Releases** are created from version tags on the `releases` branch for human-readable version history and changelogs
- No CI — all commands run locally by the developer
- The `releases` branch has no shared history with `main` (orphan branch)

**marketplace.json** (in `pramodbiligiri/claude-plugins`):
```json
{
  "name": "pramodb-plugins",
  "owner": {
    "name": "Pramod Biligiri",
    "url": "https://github.com/pramodbiligiri/devostat"
  },
  "plugins": [
    {
      "name": "devostat",
      "description": "Agent coding workflow with plan-driven discipline, TDD, vertical slices, and Linear integration.",
      "source": {
        "source": "git-subdir",
        "url": "https://github.com/pramodbiligiri/devostat.git",
        "path": "dist",
        "ref": "releases"
      },
      "author": {
        "name": "Pramod Biligiri"
      },
      "category": "development"
    }
  ]
}
```

**`dist/` folder structure** on the `releases` branch:
```
dist/
├── .claude-plugin/
│   └── plugin.json          (version stamped by release process)
├── hooks/
│   └── hooks.json
├── scripts/tasks/
│   ├── *.js                 (compiled)
│   └── *.ts                 (source, for SessionStart recompile)
├── skills/devostat/
│   └── SKILL.md
└── package.json
```

---

## Tasks (risk-sorted: High → Medium → Low)

### Task 1 [PB-256]: Create orphan `releases` branch with first release — MEDIUM risk
> **⚠ MANUAL** — Human runs these commands locally. Agent provides instructions only.

First-time setup of the orphan branch. Risk is medium because orphan branch workflows are unfamiliar and the exact file layout must match what Claude Code expects at install time.

**Steps:**
1. On main, run `mvn process-resources -Pprod`
2. Stamp `version: "0.1.0"` into `build/.claude-plugin/plugin.json`:
   ```bash
   tmp=$(mktemp) && jq '. + {"version": "0.1.0"}' build/.claude-plugin/plugin.json > "$tmp" && mv "$tmp" build/.claude-plugin/plugin.json
   ```
3. Note main SHA: `MAIN_SHA=$(git rev-parse --short HEAD)`
4. `git checkout --orphan releases`
5. `git rm -rf .`
6. Copy build output:
   ```bash
   mkdir -p dist
   cp -r build/.claude-plugin dist/
   cp -r build/hooks dist/
   cp -r build/scripts dist/
   cp -r build/skills dist/
   cp build/package.json dist/
   ```
7. `git add dist/`
8. Commit: `git commit -m "release: v0.1.0 — Built from main@${MAIN_SHA}"`
9. Tag: `git tag v0.1.0`
10. Push: `git push origin releases v0.1.0`
11. Create GitHub Release: `gh release create v0.1.0 --target releases --title "v0.1.0" --notes "First release of devostat plugin."`
12. Switch back: `git checkout main`

### Task 3 [PB-258]: Validate end-to-end installation — MEDIUM risk
> **⚠ MANUAL** — Human runs plugin install commands in Claude Code. Agent cannot execute these.

Verify that a fresh user can install the plugin from the marketplace. Medium risk because the `git-subdir` sparse clone + orphan branch combination is untested.

**Steps:**
1. Remove cached plugin: `rm -rf ~/.claude/plugins/cache/pramodb-plugins/devostat`
2. `/plugin marketplace update`
3. `/plugin install devostat@pramodb-plugins`
4. Verify: `/devostat:devostat` skill is available
5. Verify SessionStart hook runs (installs deps, compiles TS)
6. Check version in `~/.claude/plugins/cache/pramodb-plugins/devostat/.claude-plugin/plugin.json`

### Task 2 [PB-257]: Update marketplace.json in `pramodbiligiri/claude-plugins` — LOW risk
> **⚠ MANUAL** — Human edits the separate marketplace repo. Agent provides the target JSON.

Replace the current SHA-pinned source with the `git-subdir` source (see design section above).

### Task 4 [PB-259]: Write release.sh automation script — LOW risk
> **🤖 AGENT** — Agent writes this code.

Shell script at `scripts/release.sh` to automate future releases. Takes a version arg, runs maven build, stamps version, switches to orphan `releases` branch, replaces `dist/`, commits, tags, pushes, creates GitHub Release, returns to original branch.

### Task 5 [PB-260]: Update DEVELOPMENT.md with release process documentation — LOW risk
> **🤖 AGENT** — Agent writes this.

DEVELOPMENT.md currently only documents the dev build. Now that `scripts/release.sh` exists and the orphan `releases` branch workflow is in place, add a "Releasing a new version" section covering:

- Prerequisites (jq, gh, clean working tree)
- `./scripts/release.sh <version>` invocation with example
- Numbered summary of what the script does
- `dist/` folder structure on the `releases` branch
- Note that `releases` is an orphan branch with no shared history with `main`

---

## Verification

- `git log releases` shows release commits with source SHA references
- `git tag -l "v*"` shows version tags pointing to releases branch commits
- GitHub Releases page lists each version with notes
- `/plugin install devostat@pramodb-plugins` installs from a clean state
- Installed plugin's `plugin.json` shows correct version
- `/devostat:devostat` skill loads and works
