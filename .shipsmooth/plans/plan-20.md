# Plan 20 — Gemini Extension Release Process

## Context

`scripts/release.sh` builds and publishes the Claude Code plugin to the orphan `releases` branch and creates a GitHub Release. The Gemini CLI extension (added in plan-19) has no release process — `build-gemini/` is gitignored and never published.

Gemini CLI installs extensions by cloning a repo where `gemini-extension.json` lives at the root. Rather than polluting this source repo with build artifacts or orphan-branch gymnastics, a dedicated `bitkentech/devostat-gemini` repo is used as a pure publish artifact — analogous to the `releases` orphan branch for Claude, but cleaner.

**Permanent backlog feature issue:** [PB-270 — *Create release builds and process for Gemini Extension*](https://linear.app/pb-default/issue/PB-270/create-release-builds-and-process-for-gemini-extension)

### End-user install command (post-plan)

```
gemini extensions install https://github.com/bitkentech/devostat-gemini
```

No `--ref` needed. Gemini auto-prompts users to update when new commits land on `main` of that repo.

### devostat-gemini repo layout

The repo contains only the Maven gemini build output (`build-gemini/*`). Never hand-edited:

```
devostat-gemini/
  gemini-extension.json
  hooks/hooks.json
  commands/devostat.toml
  skills/devostat/SKILL.md
  dist/          (9 compiled JS files)
  package.json
```

### Build model

`mvn process-resources -P 'gemini,!dev,!claude'` already produces this tree in `build-gemini/`. The release script stamps the version via `jq` post-build (same pattern as Claude's `plugin.json` stamping).

### Resolved decisions

- **Dedicated repo** (`bitkentech/devostat-gemini`): clean install URL, no branch gymnastics in source repo. Repo already exists.
- **Independent versioning**: Gemini releases use `0.0.1`, `0.0.2`, … independently from Claude's `v0.x.y`. This allows iterative testing of the Gemini release process without bumping the Claude plugin version. Versions are decoupled by design — `release.sh` takes separate `--claude-version` and `--gemini-version` args, or a dedicated `release-gemini.sh` script is used.
- **Gemini-only release script**: rather than extending the existing `release.sh` (which is Claude-specific and has its own versioning cadence), add a separate `scripts/release-gemini.sh`. Keeps Claude and Gemini release processes independent and simpler.
- **Both builds up-front**: if the Maven gemini build fails, nothing is published.
- **Task tracking:** `[Local]` XML at `.agents/plans/plan-20-tasks.xml`.
- **Coverage threshold:** N/A — shell script with no TDD surface. Same precedent as plan-18 task 1 and plan-19 tasks 1–3.

---

## Tasks (risk-sorted)

### Task 2: Document Gemini install and release process [Low]

Add documentation to:

- **README.md** — Gemini CLI installation section (reinstate the section removed in plan-19, now that the release process works): install command, brief note about first-session hook bootstrap.
- **DEVELOPMENT.md** — Gemini release process: how to run `scripts/release-gemini.sh`, versioning convention (`0.0.x` for testing, stable releases to follow), link to `bitkentech/devostat-gemini`.

### Task 1: Create scripts/release-gemini.sh [Low]

Create `scripts/release-gemini.sh` following the same structure as `release.sh`:

1. Accept `<version>` as `$1` (e.g. `0.0.1`). Tag format: `v${VERSION}`.
2. Preflight: clean working tree; tag `v${VERSION}` must not exist in `devostat-gemini` repo (`gh release view`).
3. Build: `rm -rf build-gemini/` then `mvn process-resources -P 'gemini,!dev,!claude' -q`.
4. Stamp version: `jq --arg v "$VERSION" '. + {"version": $v}' build-gemini/gemini-extension.json`.
5. Clone `https://github.com/bitkentech/devostat-gemini.git` into a temp dir.
6. Replace repo contents: `find $CLONE -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +` then `cp -r build-gemini/. $CLONE/`.
7. Commit, tag `v${VERSION}`, push branch + tag.
8. `gh release create v${VERSION} --repo bitkentech/devostat-gemini --title "v${VERSION}" --notes "..."`.
9. Clean up temp clone dir.
10. Echo install command.

**Acceptance:** `./scripts/release-gemini.sh 0.0.1` publishes to `bitkentech/devostat-gemini`. Verified via the checks in the Verification section below.

---

## Verification

Iterative testing using small version numbers (`0.0.1`, `0.0.2`, …) until stable:

1. From clean branch: `./scripts/release-gemini.sh 0.0.1`
2. Confirm:
   - `gh api repos/bitkentech/devostat-gemini/contents/gemini-extension.json | jq -r .content | base64 -d | jq .version` → `"0.0.1"`
   - GitHub Release `v0.0.1` exists in `bitkentech/devostat-gemini`
   - `gemini-extension.json`, `hooks/hooks.json`, `commands/devostat.toml`, `skills/`, `dist/*.js`, `package.json` all present at repo root
3. Install test: `gemini extensions install https://github.com/bitkentech/devostat-gemini`
4. Iterate with `0.0.2`, `0.0.3` etc. until end-to-end install works correctly.
5. Once stable, integrate the Gemini section into `release.sh` (or keep as separate script — decision deferred).
