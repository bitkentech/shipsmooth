# Plan 51: Move copy-package-json to top-level build in plugin-dist

## Context

During the 0.3.9 release attempt, `PublishRelease.buildAndPackage()` ran:

```
mvn compile -Pprod -P!dev
```

This activated the `prod` profile in the root pom, which sets `build.platform=claude`.
However, the `claude` profile in `plugin-dist/pom.xml` uses property activation on
`build.platform=claude`. Maven does not cascade properties set by one profile to
auto-activate another profile in the same invocation — only command-line properties
(`-D`) trigger property-based profile activation. So the `claude` profile never
activated, and `copy-package-json` (and `copy-scripts`, `copy-ts-source`) never ran.
Result: `build/package.json` was missing and `syncDistAndPublish` threw
`NoSuchFileException`.

The investigation also revealed that the `gemini` profile in `plugin-dist` has its own
identical `copy-package-json` execution — a duplication that would break the same way
for a Gemini release.

## Design

`package.json` is platform-agnostic: both Claude and Gemini builds need it (Gemini hook
uses it for `npm install`; Claude hook uses it the same way). It should live in the
top-level `<build>` section of `plugin-dist/pom.xml`, gated by `${skip.copy-dist}` (the
same gate already used by `copy-dist`). This removes the duplication and makes the
execution unconditionally available regardless of which platform profile is active.

`copy-scripts` and `copy-ts-source` are Claude-only (compiled JS task scripts). They
stay in the `claude` profile — their activation problem is a separate concern and they
are not needed for Gemini releases.

After moving `copy-package-json`, `PublishRelease.buildAndPackage()` does not need any
`-Dbuild.platform=claude` workaround. The compile invocation stays as-is.

Backlog issue: none (build tooling fix, discovered during 0.3.9 release).

## Tasks

### Task 1: Move copy-package-json to top-level build in plugin-dist [Low]

- In `plugin-dist/pom.xml`, move the `copy-package-json` execution from the `claude`
  profile into the top-level `<build>` section, alongside the existing `copy-dist`
  execution. Add `<skip>${skip.copy-dist}</skip>` to match the gate on `copy-dist`.
- Remove the duplicate `copy-package-json` execution from the `gemini` profile. If the
  `gemini` profile's `<executions>` block becomes empty, remove the plugin declaration
  too.
- Verify: `mvn compile -Pprod -P'!dev'` produces `build/package.json` with the correct
  `0.3.9` version substituted. Also verify `mvn compile -Pprod -P'!dev'` still produces
  `build/.claude-plugin/marketplace.json` with `"name": "bitkentech"`.
