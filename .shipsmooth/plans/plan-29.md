# Plan 29: Tie release version to Maven project version

## Context

The SCC cache name in `plugin-tasks-java/pom.xml` is currently hardcoded as `shipsmooth_v1`. To auto-invalidate the cache on each release, the name should embed `${project.version}`. For this to work, the Maven project version must stay in sync with the release version.

Currently `${project.version}` is `0.1.0` across all POMs, while the latest release tag is `v0.2.0`. Two gaps to close:

1. Sync the POMs to the current release version (`0.2.0`) now.
2. Make `release.sh` bump the Maven version as part of every future release, so the two stay in sync going forward.
3. Update the SCC cache name to use `${project.version}` instead of a hardcoded literal.

## Design Decisions

- Use `mvn versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false` — updates all module POMs atomically.
- Commit the version bump on the feature branch before building, so releases always come from committed code.
- SCC cache name: `shipsmooth_v${project.version}` (renders as e.g. `shipsmooth_v0.2.0`). Maven property expansion works here because the antrun script is generated at build time via `<echo file=...>`.
- No SNAPSHOT versioning — this project does not publish to a Maven repo, so the SNAPSHOT convention does not apply. POMs sit at the last released version between releases.

## Files

- Modify: `scripts/release.sh` — add `mvn versions:set` + git commit step
- Modify: `plugin-tasks-java/pom.xml` — change SCC cache name
- Modify: `pom.xml`, `plugin-dist/pom.xml`, `plugin-node/pom.xml`, `plugin-devel/pom.xml`, `plugin-resources/pom.xml` — bumped to 0.2.0 via `mvn versions:set`

## Tasks

### Task 1: Sync POM versions to current release (0.2.0)

Run `mvn versions:set` to bring all POMs from `0.1.0` to `0.2.0`, matching the latest git tag `v0.2.0`.

### Task 2: Update SCC cache name to use ${project.version}

Change the hardcoded `shipsmooth_v1` in the antrun launcher script to `shipsmooth_v${project.version}`.

### Task 3: Update release.sh to bump Maven version and commit

Add these steps to `release.sh` after the clean-tree check and before the build:
1. `mvn versions:set -DnewVersion=$VERSION -DgenerateBackupPoms=false`
2. `git add -u '**/pom.xml' pom.xml`
3. `git commit -m "chore: bump version to $VERSION"`

### Task 4: Commit and verify

Commit tasks 1–2 together, confirm the SCC launcher renders the correct cache name after `mvn -Pjlink package`.
