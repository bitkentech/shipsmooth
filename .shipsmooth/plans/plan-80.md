# plan-80 — Examine and optimize the jlink runtime image size

## Context

**Backlog feature:** The shipsmooth plugin ships a self-contained jlink runtime
image that every plugin user downloads on first session (`SessionStart` hook →
`install-shipsmooth.sh` / Windows `.bat`). Smaller image = faster first-run
bootstrap and smaller release artifacts. (No external Linear issue; tracked
here, continuing the packaging lineage of plan-72 → plan-76.)

The jlink image is produced by `cli/build.gradle.kts` (`image_<platform>`
tasks). The current invocation (cli/build.gradle.kts:99-108) is:

```
jlink \
  --module-path <runtimeModulePath>:<platform-jmods> \
  --add-modules io.bitken.ss.cli,openj9.sharedclasses \
  --launcher shipsmooth=io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth \
  --no-header-files --no-man-pages \
  --compress zip-9 \
  --output <jlink-image-<platform>>
```

Observations driving this plan:

- **Module roots are hand-pinned**, not derived from `jdeps`. jlink resolves the
  transitive `requires` closure of `io.bitken.ss.cli` plus `openj9.sharedclasses`.
  We have never verified that this closure contains *only* modules our code
  actually touches — a `requires` in any `module-info` (ours or the shaded core)
  drags in a whole module even if one class is referenced.
- Some size flags are already on (`--compress zip-9`, `--no-header-files`,
  `--no-man-pages`). Others are **not**: `--strip-debug`,
  `--strip-native-commands`. These are pure-size wins but have correctness
  caveats (debug strip affects stack traces; stripping native commands removes
  the bundled `java`/`keytool` binaries the SCC launcher may rely on).
- The runtime is **OpenJ9/Semeru**, not HotSpot. jlink flag behavior and the
  `openj9.sharedclasses` module are OpenJ9-specific — measurements and pruning
  must be validated against the Semeru jlink, not assumptions from Oracle JDK.

This plan is deliberately **measure-first**. We do not cut anything until we
have a baseline and a jdeps-derived "truly required" module set to diff against.

### Calibration decisions (Phase 1)

- **Risk levels:** accepted as suggested — see per-task `[Risk]` tags.
- **Target scope:** baseline and optimize on the **host (linux-x64)** only;
  spot-verify the measured deltas hold on one other `platformJmods` target at
  closeout.
- **Coverage:** no numeric line-coverage gate. The jlink **smoke tests**
  (`jlinkSmokeHelp`, `jlinkSmokeShow`) plus the manual runtime-path checks
  (TLS/HTTPS, git-shell, tasks-XML I/O) are the acceptance gate, per Core
  Invariant #6's "as far as possible" clause for config/migration work. Any
  net-new unit-testable Java still held to the repo default.
- **Task order:** risk-sorted (High → Med → Low) with the dependency exception —
  Task 1 (baseline) and Task 2 (jdeps diff) are hard prerequisites for the
  High-risk prune, so they remain first.

### Non-goals

- Not changing the runtime vendor (stays Semeru/OpenJ9, per
  [[reference_semeru_jlink_only]]).
- Not touching the SCC launcher's runtime behavior beyond what module pruning
  forces.
- No `publishRelease` / release cut as part of this plan (human cuts releases).

## Tasks

### Task 1: Baseline measurement of the current jlink image [Low]

Establish ground truth before any change. Build the current `image_<host>`,
then record:

- Total on-disk image size (`du -sh`) and per-`modules`-file size.
- The full included module set: `<image>/bin/java --list-modules` and the
  `MODULES=` line of `<image>/release`.
- Size attribution: `du` of `lib/`, `lib/modules`, `bin/`, `legal/`.

Capture all of this into `.agents/tmp/jlink-baseline-<platform>.txt` (per the
[[feedback_tmp_directory]] rule). This file is the reference every later task
diffs against. No build-script changes in this task.

### Task 2: Compute the truly-required module set via jdeps [Medium]

Run `jdeps --print-module-deps --ignore-missing-deps` against the exact jars on
`runtimeModulePath()` (cli jar + shaded `core-jlink.jar` + runtime dep jars) to
derive the minimal module set our bytecode actually needs. Then:

- Diff that set against Task 1's included-modules list → the **suspect set**
  (included but not statically required).
- For each suspect, classify *why* it is present: transitive `requires` in a
  `module-info`, an OpenJ9-specific module (`openj9.*`, `jdk.*`), or a reflection/
  ServiceLoader/JNI runtime dependency that jdeps cannot see (charsets, crypto,
  locale, JDBC, time-zone). Record the classification — this is the cut/keep
  rationale.

De-risk question to resolve in this task: can we even *narrow* roots given that
`io.bitken.ss.cli` has an explicit `module-info`? jlink honors `requires`, so a
narrower `--add-modules` may be ignored unless the `module-info` itself is
trimmed. Prove the lever actually moves before planning to pull it.

Output: `.agents/tmp/jlink-required-vs-included-<platform>.txt`. No build-script
changes yet.

### Task 3: Prune confirmed-unneeded modules [High]
*Depends-on: 1,2*

Using Task 2's classification, remove modules from the resolved image that are
confirmed unneeded. Because jlink resolves the `requires` closure, "removing a
module" means either trimming a `requires` in a `module-info` we own (cli or the
shaded core) or excluding via jlink — establish which mechanism actually works
from Task 2's finding.

For every candidate cut: rebuild, run the **full** smoke + test suite (not just
the two jlink smokes), and exercise the runtime paths jdeps is blind to — at
minimum a command that does TLS/HTTPS (release download path), one that shells
git, and one that reads/writes the tasks XML — to catch charset/crypto/locale
regressions before they ship. Cut conservatively: a 1 MB save that risks a
runtime `ClassNotFound` on a user's machine is not worth it.

### Task 4: Apply safe, no-semantic-risk size flags [Medium]
*Depends-on: 1*

Add the size flags that do not change which modules ship, only their footprint:
`--strip-native-commands` (removes bundled `java`/`keytool`/etc. binaries — keep
only if the SCC launcher uses an external `$jreHome/bin/java`, which it does:
cli/build.gradle.kts:129) and evaluate `--strip-debug`. For each flag:

- Apply in `cli/build.gradle.kts` `image_<platform>` argumentProvider.
- Rebuild, re-measure against Task 1 baseline, record the delta.
- Run the existing jlink smoke tests (`jlinkSmokeHelp`, `jlinkSmokeShow`) — they
  must still pass green through the SCC launcher.

`--strip-native-commands` is the de-risk focus: confirm the SCC launcher
(`-Xshareclasses`, external java) and `--launcher shipsmooth=...` still produce a
working `bin/shipsmooth` after native commands are stripped. If the launcher
breaks, back the flag out and record why.

### Task 5: Lock in the win with a size regression guard [Medium]
*Depends-on: 3,4*

The plan-75 release-guard precedent shows size can silently regress on a version
bump. Add a lightweight check (Gradle verification task or extend an existing
guard) that fails the build if the image exceeds an agreed ceiling, so future
changes cannot quietly re-bloat the image. Document the final
included-module set and the measured before/after sizes in the plan file's
closeout notes and in `packaging`/`cli` build comments where the flags live.

## Open questions (resolve during Phase 1 calibration)

1. Which platform(s) do we baseline/optimize? Host (linux-x64) only, or all
   `platformJmods` targets? (Recommend: optimize on host, verify deltas hold on
   one other target before closeout.)
2. Acceptable correctness/size trade-off for `--strip-debug` — do we value
   readable production stack traces over the size win? (Recommend: keep debug
   info unless the saving is large; stack traces aid support.)
3. Coverage threshold for the build-script changes — these are Gradle/jlink
   config, largely not unit-testable; smoke tests are the real gate. Agree the
   threshold up front per Core Invariant #6's "as far as possible" clause.
