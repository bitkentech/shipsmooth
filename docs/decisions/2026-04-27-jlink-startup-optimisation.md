# jlink Startup Optimisation — Plan 28 Evolution

**Date:** 2026-04-27
**Plan:** [plan-28](../../.agents/plans/plan-28.md)
**Branch:** `t/plan-28-jlink-semeru`
**Module:** `plugin-tasks-java`

## Summary

Plan 28 began as a straightforward task: produce a self-contained `jlink` runtime image of `plugin-tasks-java` against IBM Semeru OpenJ9 JDK 25. It was completed in five tasks at an 80MB image with a ~1s startup. A follow-on investigation into startup latency drove a sequence of empirical experiments that ended with a **2ms median startup** in an 84MB image — three orders of magnitude faster than where we started — by combining OpenJ9's Shared Classes Cache (SCC) with `-Xquickstart`, after dropping `--strip-debug`.

This document captures what was tried, what was rejected, and why, so the reasoning survives the commit log.

## Final configuration

```xml
<jlink.jdk.home>/opt/installers/jdk-semeru/jdk-25.0.2+10</jlink.jdk.home>
<jlink.jre.home>/opt/installers/jre-semeru/jdk-25.0.2+10-jre</jlink.jre.home>
```

jlink flags (in `plugin-tasks-java/pom.xml`, `jlink` profile):
```
--no-header-files --no-man-pages --compress zip-9
```
(notably **no** `--strip-debug`)

Launcher script (`target/scc-launcher/shipsmooth`):
```sh
exec ${jlink.jre.home}/bin/java \
  -Xquickstart \
  -Xshareclasses:name=shipsmooth_v1,cacheDir="$SCC_DIR",nonfatal \
  --module-path ${jlink.runtime.module.path} \
  -m io.bitken.shipsmooth.tasks/io.bitken.shipsmooth.tasks.TasksCli "$@"
```

## Benchmark methodology

Each configuration was measured with:
1. SCC cache cleared
2. 5 warmup runs to populate the cache
3. 100 timed runs of `shipsmooth --help` via `time`, parsed to milliseconds

Measurements ran on the same machine in the same session to keep OS-level variance comparable.

## Evolution

### Phase 1 — Plan 28 baseline (Tasks 1–5)

The original plan delivered a working jlink image:
- Task 1: profile skeleton, Semeru toolchain wiring (`d013c30`)
- Task 2: moditect overrides — **deviated**: all dependencies already shipped real `module-info.class`, so moditect was unnecessary; reduced to `jdeps` verification (`d013c30`)
- Task 3: `module-info.java` for the app, `requires info.picocli, jakarta.xml.bind, java.xml, org.glassfish.jaxb.runtime` plus `opens` for picocli/JAXB reflection (`8ec5f0e`)
- Task 4: `maven-antrun` jlink invocation (chosen over `maven-jlink-plugin` to avoid classpath-injection issues) producing `target/jlink-image/` (`3de9927`)
- Task 5: `verify`-phase smoke tests for `--help` and `show --plan 27` (`66b0c56`)

Result: 80MB image, ~1s startup.

### Phase 2 — Startup latency investigation

#### Baseline measurement (no flags)

```
N=100, no -Xquickstart, no SCC, jlink-stripped java
Min:    735 ms   Median: 1019 ms   P95: 1505 ms   Max: 1978 ms   Stdev: 230 ms
```

The wide stdev and bimodal distribution (clusters around 850–950ms and 1250–1350ms) pointed at JIT tier transitions — exactly what OpenJ9's SCC is designed to eliminate.

#### Experiment 1 — `-Xquickstart` + `-Xshareclasses` on the jlink image (Task 6 attempt 1, `f0d6277`)

Patched the generated launcher to add both flags. Build failed at the verify-phase smoke test:

```
JVMJ9VM011W Unable to load j9shr29:
  /opt/.../jlink-image/lib/default/libj9shr29.so: cannot open shared object file
```

`--strip-debug` had removed `libj9shr29.so` along with the debug info. SCC cannot work in a stripped jlink image — at least not without re-injecting native libs.

Considered: re-injecting `libj9shr29.so` manually. Rejected — that library transitively depends on `libj9vm29.so`, `libj9jit29.so`, etc., and the version suffix (`29`) ties the cache to a specific Semeru build. Progressively reconstructing the JDK piece-by-piece undermines jlink's value.

#### Experiment 2 — full Semeru JDK + `-Xquickstart` + SCC (Task 6 attempt 2)

Bypassed the jlink image entirely; pointed the launcher at `${jlink.jdk.home}/bin/java`:

```
N=100, -Xquickstart + -Xshareclasses, full JDK
Min:    13 ms   Median:  15 ms   P95:  21 ms   Max:  44 ms   Stdev: 4 ms
```

99% improvement, ~70× faster than baseline. But this requires shipping the full 396MB JDK.

#### Experiment 3 — isolating `-Xquickstart` alone

To distinguish quickstart vs. SCC contributions, ran `-Xquickstart` only against the jlink image's `java`:

```
N=100, -Xquickstart only
Min:    480 ms   Median:  616 ms   P95: 925 ms   Max: 1238 ms   Stdev: 133 ms
```

40% faster than baseline. Real but modest — SCC was doing the heavy lifting in Experiment 2.

#### Experiment 4 — Semeru JRE instead of full JDK (Task 7, `01d37f8`)

Downloaded the Semeru JRE-only build (56MB compressed, 175MB extracted) and pointed the launcher at it:

```
N=100, JRE + -Xquickstart + -Xshareclasses
Min:    318 ms   Median:  357 ms   P95: 481 ms   Max: 587 ms   Stdev: 51 ms
```

Surprisingly worse than the full JDK. SCC populated correctly (959 AOT methods cached) but the JRE never got below ~320ms.

Tested whether `${jlink.jdk.home}/jmods` in the runtime module-path was the cause. It wasn't — same numbers (355ms with vs without `jmods`). The split `jlink.runtime.module.path` (without `jmods`) was kept anyway since runtime doesn't need module metadata only `jlink` itself uses.

#### Experiment 5 — drop `--strip-debug` (`a0400b3`)

Hypothesis: `--strip-debug` was removing native symbols that OpenJ9's SCC AOT-resolution logic depends on. Rebuilt the jlink image without `--strip-debug` (84MB, +4MB) and re-ran with SCC:

```
N=100, jlink (no --strip-debug) + -Xquickstart + SCC
Min:    2 ms   Median:  2 ms   P95: 5 ms   Max: 7 ms   Stdev: 1.2 ms
```

**2ms median.** The hypothesis was correct: `--strip-debug` was the culprit all along. The SCC could only fully work when the native debug symbols were present for OpenJ9's AOT method resolution.

### Phase 3 — Hardening the SCC flags

Added two robustness improvements to the `-Xshareclasses` directive:

- `name=shipsmooth_v1` (was `shipsmooth_cache`) — explicit versioned cache name avoids collisions with other OpenJ9 apps run by the same user; gives a migration path if the module structure changes (bump to `_v2` and the old cache is ignored, not corrupted).
- `nonfatal` — degrades to running without SCC if the cache is unavailable (permissions, disk full, version mismatch) instead of hard-crashing the JVM.

Pre-warming the cache for distribution was considered and rejected: the SCC file at `/home/pramod/.cache/javasharedresources/` was 64MB allocated / 18MB used. Bundling a 64MB cache with an 84MB image is a poor trade; users get the cache populated automatically on first run.

## Results table

| Configuration | Median | P95 | Stdev | Image size |
|---|---:|---:|---:|---:|
| Baseline (jlink, stripped, no flags) | 1019 ms | 1505 ms | 230 ms | 80 MB |
| `-Xquickstart` only | 616 ms | 925 ms | 133 ms | 80 MB |
| Semeru JRE + `-Xquickstart` + SCC | 357 ms | 481 ms | 51 ms | 56 MB tar |
| Full Semeru JDK + `-Xquickstart` + SCC | 15 ms | 21 ms | 4 ms | 396 MB |
| **jlink (no `--strip-debug`) + `-Xquickstart` + SCC** | **2 ms** | **5 ms** | **1.2 ms** | **84 MB** |

## Decisions and trade-offs

1. **`--strip-debug` removed.** 4MB cost (80MB → 84MB), 500× speedup. No-brainer.
2. **`-Xquickstart` enabled.** Trades peak throughput for faster startup. Right call for a short-lived CLI; wrong call for a long-running server.
3. **SCC at `target/scc/`.** During build/test the cache lives under `target/`. For a real user distribution the `cacheDir` would be relative to the launcher script (`$(dirname "$0")/../scc`) or in a user-writable XDG location.
4. **JRE artefact retained but unused at runtime.** The `jlink.jre.home` property and the JRE itself are kept in the build environment for reference / fallback; the production launcher uses the jlink-built `bin/java`. The JRE path was the focus of Task 7 but was superseded by the no-strip-debug discovery.
5. **Pre-warmed cache rejected.** Adds 18–64MB to a distribution to save one cold-start (~7ms) on first use. Not worth it.
6. **The Java binary is not yet a shipped artefact.** Plan 28's output is a build/test artefact only. Distribution wiring (Node.js launcher, OS detection, multi-platform jlink) is out of scope and left for a future plan.

## What changed in `plugin-tasks-java/pom.xml`

- `<jlink.jdk.home>` (Task 1) — Semeru JDK location, for jlink + jmods.
- `<jlink.jre.home>` (Task 7) — Semeru JRE location; not used by current launcher.
- `<jlink.runtime.module.path>` — module path without `jmods`, for the launcher.
- `<jlink.module.path>` — module path with `jmods`, for jlink itself.
- `jlink-version-check` antrun execution (Task 1).
- `jlink-build-image` antrun execution (Task 4) — **note: no `--strip-debug`**.
- `jlink-write-scc-launcher` antrun execution (Task 6) — emits `target/scc-launcher/shipsmooth` with `-Xquickstart` and `-Xshareclasses:name=shipsmooth_v1,nonfatal`.
- `jlink-smoke-help` and `jlink-smoke-show` antrun executions (Task 5) — both invoke the SCC launcher.

## Open questions for follow-up

- **Cache location for shipped binaries.** When this becomes a user-facing artefact, where should the SCC live? Options: relative to launcher (`$DIR/../scc`), `XDG_CACHE_HOME`, or `/tmp`. Each has implications for reuse across runs and across users.
- **First-run cost.** The 2ms number is steady-state. The first run after a cold cache is ~50ms. Worth measuring across more cache states (cold, partially warm, fully warm).
- **Cross-platform jlink.** The current image is Linux-x64-only. Multi-platform jlink builds (macOS, Windows) would require downloading the corresponding Semeru jmods.
- **Comparison with GraalVM Native Image.** `plugin-tasks-java` also has a Native Image build path. A head-to-head startup comparison is worth doing before picking a default distribution format.
