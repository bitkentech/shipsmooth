# Plan 30 — Package shipsmooth-tasks from the jlink image

## Context

- **Backlog issue:** `BL-001 — Reduce shipsmooth-tasks zip footprint and remove standalone JRE dependency` (newly created for this plan; recorded below since this repo is in `[Local]` task-tracking mode and has no permanent Linear backlog).
- **Source observation:** [docs/observations/2026-04-27-openj9-scc-startup-correction.md](../../docs/observations/2026-04-27-openj9-scc-startup-correction.md) — recommendation section.
- **Earlier work:** Plan 28 introduced the `jlink` Maven profile and the OpenJ9 SCC launcher. Plan 29 tied versions to `${project.version}`. This plan completes the loop by making `package-tasks-java.sh` consume the jlink image instead of the standalone Semeru JRE.

## Backlog issue (BL-001)

> **Title:** Reduce shipsmooth-tasks zip footprint and remove standalone JRE dependency
>
> **Why:** The shipped zip currently bundles the full Semeru JRE (~175MB on disk). The jlink image with `openj9.sharedclasses` added to `--add-modules` delivers identical startup performance (~340–367ms median) at ~85MB — roughly half the size, and self-contained (no `/opt/installers/jre-semeru/...` build-time dependency).
>
> **Acceptance:** `scripts/package-tasks-java.sh` produces a `linux-x64.zip` whose runtime is the jlink image, and the extracted launcher starts the CLI with the same OpenJ9 SCC + `-Xquickstart` behavior as today.

## Goal

Switch `scripts/package-tasks-java.sh` from packaging `JRE_SRC` (`/opt/installers/jre-semeru/jdk-25.0.2+10-jre`) to packaging `plugin-tasks-java/target/jlink-image/`. The shipped zip's runtime directory will be renamed `jre/` → `runtime/`. The user-facing top-level launcher remains hand-written (one fewer indirection than re-using the jlink-baked launcher, plus full control over `-Xquickstart -Xshareclasses` flags).

## Design decisions

1. **`openj9.sharedclasses` belongs in the jlink profile, not in the packaging script.**
   The jlink image must include `libj9shr29.so` so the packaged launcher can use `-Xshareclasses` against `runtime/bin/java`. We add `openj9.sharedclasses` to the existing `--add-modules` arg in `plugin-tasks-java/pom.xml`. No changes to the experimental scripts in `scripts/experiment-*.sh` (per agreement, those are retained for now).

2. **Top-level launcher stays hand-written; jlink-baked launcher inside the image is dead weight.**
   The jlink build still bakes `runtime/bin/shipsmooth-tasks` because the `--launcher` arg is part of the existing profile and changing it is out of scope. The user-facing `<zip>/bin/shipsmooth-tasks` (one level above the runtime dir) calls `$INSTALL/runtime/bin/java` directly with the OpenJ9 perf flags. Avoids an extra shell `exec` and keeps SCC name/cacheDir logic in one place.

3. **Module path comes from the jlink image, not from the local Maven repo.**
   The current `package-tasks-java.sh` loops over `lib/*.jar` to build `MODULE_PATH`. Once the runtime is a jlink image, the application module and its deps are baked into `runtime/lib/modules` (jimage). The launcher therefore drops `--module-path` and just runs `-m com.github.pramodbiligiri.shipsmooth.tasks/...TasksCli`. The `lib/` directory in the staged zip is no longer needed and is removed.

4. **`JRE_SRC` precondition is replaced with a jlink-image precondition.**
   The script checks for `target/jlink-image/bin/java` instead of `/opt/installers/jre-semeru/...`. Failure message points the user at `mvn -pl plugin-tasks-java -am -Pjlink package`.

5. **Smoke test stays as-is.**
   `"$STAGE_DIR/bin/shipsmooth-tasks" --help > /dev/null` continues to gate the zip step.

## Risk-calibrated tasks

### Task 1: Add `openj9.sharedclasses` to the jlink profile [Low]

**Default Risk: Low.** One-line change inside `plugin-tasks-java/pom.xml` — add `,openj9.sharedclasses` to the existing `--add-modules` arg. The jlink experiment script (`experiment-jlink-with-shr.sh`) already proved the build works with this module added.

**Verification:** `mvn -pl plugin-tasks-java -am -Pjlink clean package` succeeds, and `find target/jlink-image/lib -name 'libj9shr*.so'` returns a hit.

### Task 2: Rewrite `scripts/package-tasks-java.sh` to package the jlink image [Medium]

**Default Risk: Medium.** Several coupled changes: (i) replace `JRE_SRC` precondition with `target/jlink-image/bin/java` precondition; (ii) `cp -r target/jlink-image "$STAGE_DIR/runtime"` instead of copying the standalone JRE; (iii) drop the dependency-resolution + `lib/` copy step (modules are baked into the image); (iv) rewrite the launcher heredoc to call `$INSTALL/runtime/bin/java` and drop `--module-path`. The launcher's `-m` invocation must match the module/main-class baked into the image — a wrong arg combo here is a runtime failure caught only by the smoke test.

**Verification:** Script runs end-to-end, smoke test passes, `unzip -l <zip>` shows `runtime/bin/java` and no `lib/` entries, extracted launcher invocation works from a temp dir, and zip size is in the 80–95 MB range (vs ~175 MB today).

### Task 3: Verify zip end-to-end on a clean extraction [Low]

**Default Risk: Low.** Pure verification — extract the produced zip into `/tmp`, run `bin/shipsmooth-tasks --help` and `bin/shipsmooth-tasks show --plan 27`, confirm the SCC cache is created under `${XDG_CACHE_HOME:-$HOME/.cache}/shipsmooth/scc/`. No code changes; if this fails, the failure points back at Task 2.

**Verification:** Both invocations succeed; SCC cache directory exists post-run.

## Open questions

None at draft time. Awaiting your risk-level overrides per Phase 1 of the workflow.

## References

- [docs/observations/2026-04-27-openj9-scc-startup-correction.md](../../docs/observations/2026-04-27-openj9-scc-startup-correction.md) — measurements and recommendation
- [docs/decisions/2026-04-27-jlink-startup-optimisation.md](../../docs/decisions/2026-04-27-jlink-startup-optimisation.md) — original (partly incorrect) decision doc
- `scripts/experiment-jlink-with-shr.sh` — proves jlink+`openj9.sharedclasses` builds and runs
- `plugin-tasks-java/pom.xml` (jlink profile, lines 146–262) — where Task 1 lands
- `scripts/package-tasks-java.sh` — where Task 2 lands