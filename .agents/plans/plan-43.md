# Plan 43 — macOS x64 runtime zip in release pipeline

## Status: Draft

## Backlog issue
<!-- No Linear — tracked locally -->
Feature: add macOS x64 (`darwin-x64`) runtime zip to the release pipeline so Mac users get a native jlink image alongside the existing Linux one.

## Context

### Mac JDK location
`/opt/installers/jdk-semeru-mac-x64/Contents/Home/` (extracted from `ibm-semeru-open-jdk_x64_mac_25.0.2.1.tar.gz`).

Key finding: `jdk-semeru-mac-x64/Contents/Home/bin/jlink` is a Mach-O binary — it cannot run on Linux.
Cross-compilation approach: use the **Linux** jlink binary with the Mac `jmods/` directory.
Verified: `linux-jlink --module-path <mac-jmods> --add-modules java.base` succeeds.

`openj9.sharedclasses.jmod` is present in the Mac JDK — the same module set used by the Linux build is available.

### Current release pipeline (plan-42 deliverable)
`PublishRelease.buildAndPackage()`:
1. `mvn -pl plugin-tasks-java -am -Pjlink package` — builds jlink image using Linux JDK (`${jlink.jdk.home}`)
2. `mvn compile -Pprod` — builds plugin dist
3. `PackageRuntime("linux-x64", ...)` — zips jlink image → `shipsmooth-tasks-{version}-linux-x64.zip`

`PublishRelease.syncDistAndPublish()`:
- Uploads only the Linux zip to GitHub Releases via `gh release upload`.

### jlink profile parameterisation
`plugin-tasks-java/pom.xml` jlink profile uses `${jlink.jdk.home}` for both the `jlink` executable and the `jmods/` path (single property, same value). To cross-compile for Mac we need to split this into:
- `jlink.exec.home` — the JDK whose `bin/jlink` to invoke (always the Linux JDK on this build host)
- `jlink.jmods.home` — the JDK whose `jmods/` to link against (Linux JDK for linux-x64; Mac JDK for darwin-x64)

The output image will be placed in a target subdirectory named by platform, e.g.:
- `plugin-tasks-java/target/jlink-image-linux-x64/`
- `plugin-tasks-java/target/jlink-image-darwin-x64/`

This lets both images coexist in a single Maven build invocation.

### PackageRuntime
Already accepts a `jlinkImage` path — no structural change needed. The caller passes the platform-specific image path.

### PublishRelease changes needed
- Build Mac jlink image (separate Maven exec or profile invocation, passing Mac jmods)
- Call `PackageRuntime("darwin-x64", macJdkHome, darwinJlinkImage, outputDir, version)`
- Upload `shipsmooth-tasks-{version}-darwin-x64.zip` alongside the Linux zip

### session-start.ts
Already calls `detectPlatform()` → `darwin-x64` on Apple Silicon / Intel Mac. The download URL already interpolates the platform string. No change needed once the zip is uploaded.

## Tasks

### Task 1: Split jlink.jdk.home into exec + jmods properties [Risk: Low]

In `plugin-tasks-java/pom.xml`, rename `${jlink.jdk.home}` usages:
- `bin/jlink`, `bin/jar` executable paths → use new `${jlink.exec.home}` property
- `jmods/` path in `jlink.module.path` → use new `${jlink.jmods.home}` property

Add default values:
```xml
<jlink.exec.home>/opt/installers/jdk-semeru/jdk-25.0.2+10</jlink.exec.home>
<jlink.jmods.home>/opt/installers/jdk-semeru/jdk-25.0.2+10</jlink.jmods.home>
```

The existing smoke tests (`jlink-smoke-help`, `jlink-smoke-show`) use `${jlink.jre.home}` — unaffected.

Verify: `mvn -pl plugin-tasks-java -am -Pjlink package` still produces `target/jlink-image/` (same as before).

### Task 2: Add darwin-x64 jlink build execution to pom [Risk: Medium]

*Depends-on: 1*

Add a second `maven-antrun-plugin` execution in the `jlink` profile that:
- Invokes `${jlink.exec.home}/bin/jlink` with `--module-path` pointing to Mac jmods
- Writes output to `${project.build.directory}/jlink-image-darwin-x64/`
- Uses a new property `${jlink.jmods.darwin-x64}` defaulting to `/opt/installers/jdk-semeru-mac-x64/Contents/Home`

The Mac image must include `openj9.sharedclasses` (present in Mac jmods — verified).

The existing Linux image execution writes to `${project.build.directory}/jlink-image/` (unchanged for backward compat). Add an alias execution that also writes to `${project.build.directory}/jlink-image-linux-x64/` so `PublishRelease` can use a consistent path pattern.

Verify: after `mvn -pl plugin-tasks-java -am -Pjlink package`, both `target/jlink-image-linux-x64/` and `target/jlink-image-darwin-x64/` exist with `bin/shipsmooth-tasks` launchers.

### Task 3: Update PublishRelease to package and upload darwin-x64 zip [Risk: Low]

*Depends-on: 2*

In `PublishRelease`:
- Add `jdk.semeru.darwin-x64` system property (default `/opt/installers/jdk-semeru-mac-x64/Contents/Home`)
- In `buildAndPackage()`: after the Linux `PackageRuntime` call, add a Darwin one:
  ```java
  Path darwinJlinkImage = repoRoot.resolve("plugin-tasks-java/target/jlink-image-darwin-x64");
  Path darwinJdkHome = Path.of(System.getProperty("jdk.semeru.darwin-x64", "/opt/installers/jdk-semeru-mac-x64/Contents/Home"));
  new PackageRuntime("darwin-x64", darwinJdkHome, darwinJlinkImage, outputDir, version).run();
  ```
- In `syncDistAndPublish()`: upload the darwin zip alongside the linux one:
  ```java
  Path darwinZip = outputDir.resolve("shipsmooth-tasks-" + version + "-darwin-x64.zip");
  runCommand(List.of("gh", "release", "upload", "v" + version, darwinZip.toString()), repoRoot);
  ```

Note: `PackageRuntime.run()` currently smoke-tests the launcher only on native Linux — skip smoke-test for darwin-x64 on the Linux build host (the existing TODO comment covers this).

### Task 4: Update session-start.ts download and verify end-to-end [Risk: Low]

*Depends-on: 3*

Review `session-start.ts` to confirm:
- `detectPlatform()` returns `darwin-x64` on Intel Mac, `darwin-arm64` on Apple Silicon
- The download URL interpolation handles `darwin-x64`
- Any hardcoded `linux-x64` guards are replaced with proper platform checks

Add a unit test for `detectPlatform()` covering `darwin-x64`. Document that `darwin-arm64` is not yet supported (no installer present — future plan).

## Open Questions

1. The Mac jlink image will contain `bin/java` pointing to a relative path — verify the launcher script works unchanged on macOS (the `exec "$INSTALL/runtime/bin/java"` pattern is POSIX-compatible, should be fine).
2. Should we also build a `darwin-arm64` zip? No — no ARM Mac JDK is available in `/opt/installers/`. Defer to a future plan.
