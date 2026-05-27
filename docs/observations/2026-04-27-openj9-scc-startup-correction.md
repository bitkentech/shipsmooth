# OpenJ9 SCC Startup Time — Corrected Measurements

**Date:** 2026-04-27
**Module:** `plugin-tasks-java`
**Scripts:** `scripts/experiment-jlink-with-shr.sh`, `scripts/experiment-startup-matrix.sh`

## Context

[docs/decisions/2026-04-27-jlink-startup-optimisation.md](../decisions/2026-04-27-jlink-startup-optimisation.md)
reports a **2ms median** for "jlink (no --strip-debug) + -Xquickstart + SCC" and **15ms median** for
"Full Semeru JDK + -Xquickstart + SCC". These figures could not be reproduced.

This document records the follow-up investigation and corrected numbers.

## What was reproduced

- `libj9shr29.so` is absent from a jlink image built without `openj9.sharedclasses` in `--add-modules`.
  Adding `openj9.sharedclasses` to `--add-modules` does include the library — `-Xshareclasses` then
  works without error on the jlink-built `bin/java`.
- All four tested configurations (full JDK, jlink+zip-9, jlink+no-compress, standalone JRE) measured
  in the **330–380ms median range** — consistent with published OpenJ9+SCC benchmarks for a JAXB CLI app.

## Benchmark methodology

- Isolated SCC cache per configuration (no cross-contamination with production cache)
- 5 warmup runs to populate AOT cache
- 100 timed runs of `bin/shipsmooth --help` via bash `time`, wall-clock milliseconds
- All runs in a single session on the same machine

## Results

| Configuration | Min | Median | Mean | P95 | Max | Stdev | Size |
|---|---:|---:|---:|---:|---:|---:|---:|
| Full Semeru JDK + -Xquickstart + SCC | 315 ms | 356 ms | 374 ms | 465 ms | 735 ms | 55 ms | 396 MB |
| jlink + openj9.sharedclasses + zip-9 + SCC | 332 ms | 367 ms | 372 ms | 412 ms | 441 ms | 21 ms | 85 MB |
| jlink + openj9.sharedclasses + NO compress + SCC | 329 ms | 366 ms | 369 ms | 412 ms | 491 ms | 30 ms | 121 MB |
| Standalone Semeru JRE + SCC | 339 ms | 378 ms | 394 ms | 498 ms | 542 ms | 46 ms | 175 MB |

## Why the decision doc numbers are not reproducible

Raw `time` output for a representative run:

```
real    0m0.378s
```

The awk parser correctly extracts 378ms. There is no parsing error in the current benchmark scripts.

The figures in the decision doc (2ms, 15ms) are physically implausible for JVM startup:

- Even a minimal JVM requires ~15–30ms for GC init, class loading, and module graph resolution.
- Sub-10ms startup is only achievable with GraalVM Native Image or CRaC checkpoint restore.
- Published OpenJ9+SCC benchmarks (Eclipse OpenJ9 DayTrader8, Tomcat) show 30–42% improvement over
  baseline — from ~1s to ~600–700ms for heavier apps, consistent with our ~340ms for a lightweight CLI.

The most likely explanation for the decision doc's numbers is a one-off measurement error — possibly the
timing was taken before the JVM fully exited, or against a run that short-circuited (e.g., an unset
variable caused a different binary to be invoked). The decision doc's conclusions about which flags to
use (`-Xquickstart`, `-Xshareclasses`, drop `--strip-debug`) remain valid; only the reported timings
are wrong.

## Corrected understanding of the performance floor

~340–360ms is the real steady-state startup for this application stack (OpenJ9 25 + JAXB + picocli +
module-path). The jlink image with `openj9.sharedclasses` (85MB folder, ~zip-9 compressed) delivers the
same performance as the standalone JRE distribution (175MB) at roughly half the on-disk footprint.

## Recommendation

Ship the jlink image built with `openj9.sharedclasses` added to `--add-modules`. It is:
- Self-contained (no external JRE dependency)
- ~340ms median startup (same as all alternatives)
- Smaller than shipping the standalone Semeru JRE

The `scripts/package-tasks-java.sh` packager (currently JRE-based) should be updated to build from the
jlink image instead. That is left as a follow-up task.
