# Plan 21 — README for devostat-gemini repo

## Context

`bitkentech/devostat-gemini` is the Gemini CLI extension release artifact repo (added in plan-20). It has no README, so GitHub shows a bare file listing with no context for anyone landing there.

The README should be self-contained — relevant content copied from `bitkentech/devostat`'s README (features, workflow, install), plus a development section pointing to `bitkentech/devostat` and its DEVELOPMENT.md rather than duplicating dev instructions.

The README is a source file in this repo (`plugin-resources/src/main/resources/gemini-extension/README.md`), copied into `build-gemini/` by Maven, and published to `devostat-gemini` by `release-gemini.sh` on each release.

**Permanent backlog feature issue:** [PB-270](https://linear.app/pb-default/issue/PB-270/create-release-builds-and-process-for-gemini-extension)

### Resolved decisions

- **Source lives here**: `plugin-resources/src/main/resources/gemini-extension/README.md` — version-controlled alongside the extension source, flows through existing Maven build.
- **No filtering needed**: README is static markdown, no Maven property substitution required.
- **Wipe-safe**: `release-gemini.sh` does `find ... -exec rm -rf {}` on the clone before copying `build-gemini/`. README is in `build-gemini/` so it survives.
- **Content**: copy Features and How to use sections from main README verbatim; Installation section with just the Gemini install command; Development section saying dev happens at bitkentech/devostat with a link to DEVELOPMENT.md.
- **Task tracking:** `[Local]` XML at `.agents/plans/plan-21-tasks.xml`.
- **Coverage threshold:** N/A — markdown file, no TDD surface.

---

## Tasks (risk-sorted)

### Task 1: Add README.md to gemini-extension source tree and wire into Maven build [Low]

1. Create `plugin-resources/src/main/resources/gemini-extension/README.md` with content described above.
2. Add a Maven resource copy execution in the `gemini` profile of `plugin-resources/pom.xml` to copy `README.md` from the gemini-extension source dir into `${build.outputDir}/`.
3. Verify `build-gemini/README.md` appears after `mvn process-resources -P 'gemini,!dev,!claude'`.
4. Verify Claude build (`mvn process-resources -Pprod -P'!dev'`) is unaffected — no `README.md` in `build/`.

**Acceptance:** `build-gemini/README.md` exists and contains the correct content. Claude build unchanged.

---

## Verification

1. `mvn process-resources -P 'gemini,!dev,!claude'` → `build-gemini/README.md` exists
2. `mvn process-resources -Pprod -P'!dev'` → no `README.md` in `build/`
3. `./scripts/release-gemini.sh 0.0.2` → `README.md` visible at `https://github.com/bitkentech/devostat-gemini`
