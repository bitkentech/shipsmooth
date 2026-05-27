# Plan 58: Fix PublishRelease stale module references

## Context

The `aec72b5` / `680b308` refactor renamed the Maven module `plugin-tasks-java` → `app` and removed
`plugin-dist` (output now lives under `packaging/target/`). `PublishRelease.java` and
`PackageRuntime.java` were not updated. This caused the 0.3.11 release attempt to fail immediately
when `PublishRelease` tried to build `-pl plugin-tasks-java`.

Backlog reference: no dedicated backlog issue — this is a build-correctness fix unblocking the
0.3.11 release.

## Files to change

### `packaging/src/main/java/io/bitken/ss/dist/PublishRelease.java`

1. `buildAndPackage()`: `-pl plugin-tasks-java` → `-pl app`
2. `buildAndPackage()`: output dir `plugin-dist/target/dist` → `packaging/target/dist`
3. `buildAndPackage()`: four jlink image paths `plugin-tasks-java/target/jlink-image-*` → `app/target/jlink-image-*`
4. `publishWindowsRelease()`: `plugin-tasks-java/target/jlink-image-windows-x64` → `app/target/jlink-image-windows-x64`
5. `syncDistAndPublish()`: `plugin-dist/target/dist` → `packaging/target/dist`

### `packaging/src/main/java/io/bitken/ss/dist/PackageRuntime.java`

6. `main()`: `plugin-tasks-java/target/jlink-image` → `app/target/jlink-image`

## Verification

After the fix, re-run the release:
```bash
mvn install -pl packaging -am -Pprod -P'!dev' -DskipTests
mvn exec:java@publish-release -pl packaging -Dshipsmooth.release.version=0.3.11 -Pprod -P'!dev'
```

---

### Task 1: Fix stale module paths in PublishRelease.java and PackageRuntime.java [Low]

Update all six occurrences of `plugin-tasks-java` and `plugin-dist` to their post-refactor names
(`app` and `packaging`) in both Java source files.
