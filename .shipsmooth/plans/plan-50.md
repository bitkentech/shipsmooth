# Plan 50: Fix java permissions after zip extraction and marketplace name in prod build

## Context

Two independent bugs surfaced after the 0.3.8 release:

### Bug 1 — `runtime/bin/java` not executable after install

When `session-start.ts` extracts the runtime zip via `AdmZip.extractAllTo()`, it only
`chmod`s the top-level `bin/shipsmooth-tasks` launcher. The `runtime/bin/java`,
`runtime/bin/jitserver`, and `runtime/bin/keytool` binaries land with mode `rw-rw-rw-`
(non-executable) because `AdmZip` ignores Unix mode bits stored in zip entries.

The zip entries themselves are correct — `PackageRuntime.java` sets 0755 on all files
where `Files.isExecutable()` is true. The fault is entirely on the extraction side.

Symptom: on a fresh install the hook runs, the launcher is chmod'd, but when the
launcher calls `exec "$INSTALL/runtime/bin/java" ...` it fails:

```
/home/pramod/.cache/shipsmooth/runtime-0.3.8/bin/shipsmooth-tasks: 6: exec:
/home/pramod/.cache/shipsmooth/runtime-0.3.8/runtime/bin/java: Permission denied
```

Fix: after `AdmZip.extractAllTo()`, walk `runtime/bin/` inside the extracted directory
and `chmod` every file to 0755.

### Bug 2 — Prod build ships `shipsmooth-dev` as the marketplace name

`plugin-resources/src/main/resources/claude-plugin/marketplace.json` has the top-level
`"name"` field hardcoded as `"shipsmooth-dev"`. Only `plugins[].name` is parameterized
via `${plugin.name}`. In a prod build the plugin entry becomes `"name": "shipsmooth"`,
but the marketplace identifier stays `"shipsmooth-dev"`.

This matters because the marketplace `"name"` is the `@qualifier` users type when
installing: `/plugin install shipsmooth@bitkentech`. The README install instructions
specify `bitkentech` as the qualifier, so prod builds must ship `"name": "bitkentech"`.

Fix: introduce a `marketplace.name` pom property (`shipsmooth-dev` in the dev profile,
`bitkentech` in the prod profile) and use `${marketplace.name}` as the top-level `"name"`
in `marketplace.json`.

## Design

**Bug 1:** In `downloadAndInstall()` in `session-start.ts`, after `extractAllTo`, add a
walk of `path.join(extractDir, 'runtime', 'bin')` that calls `fs.chmodSync(file, 0o755)`
on every entry. This mirrors the existing `chmodSync` on the launcher and is safe to run
unconditionally — setting 0755 on already-executable files is a no-op.

**Bug 2:** Add `<marketplace.name>` to both the `dev` and `prod` profiles in the root
`pom.xml`, then replace `"shipsmooth-dev"` in the top-level `"name"` field of
`marketplace.json` with `${marketplace.name}`.

## Tasks

### Task 1: Fix runtime/bin/* permissions after zip extraction [Low]

- In `plugin-node/src/main/scripts/tasks/session-start.ts`, inside `downloadAndInstall`,
  after the existing `fs.chmodSync(extractedBin, 0o755)` call, walk
  `path.join(extractDir, 'runtime', 'bin')` and `chmodSync` every file to 0755.
- Add a test in `session-start.test.ts` that builds a fake zip with a `runtime/bin/java`
  entry that has no executable bit, extracts it via `installRuntime` using the zip-based
  path, and asserts that `runtime/bin/java` is executable afterwards.

### Task 2: Fix marketplace top-level name in prod build [Low]

- In the root `pom.xml`, add `<marketplace.name>shipsmooth-dev</marketplace.name>` to the
  `dev` profile and `<marketplace.name>bitkentech</marketplace.name>` to the `prod`
  profile.
- In `plugin-resources/src/main/resources/claude-plugin/marketplace.json`, replace the
  hardcoded `"shipsmooth-dev"` in the top-level `"name"` field with `${marketplace.name}`.
- Verify: run `mvn process-resources -pl plugin-resources -Pprod -P'!dev'
  -Dbuild.outputDir=...` and confirm the output `marketplace.json` has
  `"name": "bitkentech"`.
