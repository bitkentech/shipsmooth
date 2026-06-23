# Plan 44 — darwin-arm64 runtime zip in release pipeline

## Status: Draft

## Backlog issue
<!-- No Linear — tracked locally -->
Feature: add macOS ARM64 (`darwin-arm64`) runtime zip to the release pipeline so Apple Silicon Mac users get a native jlink image alongside linux-x64 and darwin-x64.

## Context

### ARM64 Mac JDK location
`/opt/installers/jdk-semeru-mac-arm64/Contents/Home/` (extracted from `ibm-semeru-open-jdk_aarch64_mac_25.0.2.1.tar.gz`).

Cross-compilation verified: Linux jlink + ARM64 mac jmods → Mach-O 64-bit arm64 image. Same approach as plan-43 for darwin-x64.

`openj9.sharedclasses.jmod` is present in the ARM64 mac JDK — same module set used by linux-x64 and darwin-x64.

### What plan-43 already delivered
- `jlink.exec.home` / `jlink.jmods.home` split in `plugin-tasks-java/pom.xml`
- `jlink.jmods.darwin-x64` property and `jlink-build-darwin-x64` antrun execution
- `package-runtime-darwin-x64` exec execution in `plugin-dist/pom.xml`
- `PublishRelease` uploads both linux-x64 and darwin-x64 zips
- `session-start.ts` supports `darwin-x64`

### What this plan adds
All of the above repeated for `darwin-arm64`. The pattern is fully established — pure extension, no new architectural decisions.

## Tasks

### Task 1: Add darwin-arm64 jlink build execution to plugin-tasks-java pom [Risk: Low]

Add `<jlink.jmods.darwin-arm64>/opt/installers/jdk-semeru-mac-arm64/Contents/Home</jlink.jmods.darwin-arm64>` property and a `jlink-build-darwin-arm64` antrun execution (same pattern as `jlink-build-darwin-x64`, output to `jlink-image-darwin-arm64/`).

Verify: `mvn -pl plugin-tasks-java -am -Pjlink -Dexperimental.enabled=false package` produces `target/jlink-image-darwin-arm64/bin/java` as a Mach-O arm64 binary.

### Task 2: Add darwin-arm64 PackageRuntime execution to plugin-dist pom [Risk: Low]

*Depends-on: 1*

Add `<jdk.semeru.darwin-arm64>/opt/installers/jdk-semeru-mac-arm64/Contents/Home</jdk.semeru.darwin-arm64>` property and `package-runtime-darwin-arm64` exec execution.

Verify: `mvn exec:java@package-runtime-darwin-arm64 -pl plugin-dist -Pprod -P'!dev'` produces `shipsmooth-tasks-{version}-darwin-arm64.zip`.

### Task 3: Update PublishRelease to package and upload darwin-arm64 zip [Risk: Low]

*Depends-on: 2*

- Add `darwinArm64JdkHome` field, read from `jdk.semeru.darwin-arm64` system property
- Add `PackageRuntime("darwin-arm64", ...)` call in `buildAndPackage()`
- Add darwin-arm64 zip to `gh release upload` in `syncDistAndPublish()`
- Wire `jdk.semeru.darwin-arm64` system property into the `publish-release` exec execution in `plugin-dist/pom.xml`

### Task 4: Update session-start.ts to support darwin-arm64 [Risk: Low]

*Depends-on: 3*

Add `darwin-arm64` to the `supportedPlatforms` array in `session-start.ts`. Add a unit test covering `darwin-arm64` with `forcePlatform` + `jlinkDir`.
