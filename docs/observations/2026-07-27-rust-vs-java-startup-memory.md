# Rust vs Java CLI — startup time and memory

Measured 2026-07-27

Head-to-head measurement of the shipped Java CLI against the experimental Rust port
(`exp/rust`, plan-106) on the same machine, same session. This is the first direct
A/B since the plan-102 explore, which estimated the gap rather than measuring both
binaries side by side.

## What was compared

| | Java | Rust |
|---|---|---|
| Binary | `~/.cache/shipsmooth/0.3.36/bin/shipsmooth` | `exp/rust/target/release/shipsmooth` |
| Version reported | 0.3.36 | 0.3.34 |
| Runtime | Semeru 25.0.2+10, OpenJ9 0.57.0 (JIT + AOT) | rustc 1.93.1, `--release`, default profile |
| Launcher flags | `-Xquickstart -Xshareclasses` (SCC) | — |

Host: Intel i5-7200U @ 2.50 GHz, 4 CPUs, Linux 6.8.0.

**Source commit:** `bac14df3d756c8571c1ca8d16d8787014070d6b6` (`bac14df`, 2026-07-27,
"Rust port of the store command (plan-106)") on `main`. The working tree was clean at
measurement time apart from this document, so the Rust binary was built from exactly
that commit. The Java binary is the released 0.3.36 install from `~/.cache`, which
predates this commit — it is not built from the tree.

## Methodology

- **Warmup:** 5 alternating runs of each binary before timing, so the OpenJ9 shared
  class cache (SCC) and the OS page cache are both hot. The Java numbers below are
  therefore its *best case*, not a cold start.
- **Timing:** 20 runs per command, wall-clock via `date +%s%N` around each invocation,
  output to `/dev/null`.
- **Memory:** 10 runs per command, peak RSS via `/usr/bin/time -f %M`.
- Both binaries were exercised on `--version`, `--help`, and `store info` — `store` is
  the only real subcommand the Rust port implements so far.

## Startup time

| Command | Java median | Rust median | Speedup |
|---|---:|---:|---:|
| `--version` | 442 ms | 3 ms | ~147× |
| `--help` | 462 ms | 3 ms | ~154× |
| `store info` | 474 ms | 6 ms | ~79× |

Full spread (ms):

| Run | Min | Median | P90 | Max | Mean |
|---|---:|---:|---:|---:|---:|
| java `--version` | 415 | 442 | 460 | 513 | 445.2 |
| rust `--version` | 2 | 3 | 3 | 4 | 2.8 |
| java `--help` | 440 | 462 | 488 | 505 | 464.6 |
| rust `--help` | 2 | 3 | 3 | 4 | 2.9 |
| java `store info` | 453 | 474 | 479 | 517 | 471.0 |
| rust `store info` | 5 | 6 | 7 | 7 | 6.4 |

**Java's cost is bootstrap, not work.** `--version` and `store info` differ by only
~30 ms, so ~94% of the wall time is JVM startup that every invocation pays regardless
of what is asked of it.

**Rust's cost tracks work.** `--version`/`--help` at ~3 ms is essentially process
spawn; `store info` adds ~3 ms for config resolution and filesystem probing — the only
case where actual work is visible above the noise floor.

## Memory

Peak RSS:

| Command | Java median | Rust median | Ratio |
|---|---:|---:|---:|
| `--version` | 63.5 MB | 3.4 MB | ~19× |
| `store info` | 63.7 MB | 4.2 MB | ~15× |

| Run | Min | Median | Max | Mean |
|---|---:|---:|---:|---:|
| java `--version` | 63.2 | 63.5 | 63.9 | 63.5 MB |
| rust `--version` | 3.2 | 3.4 | 3.4 | 3.4 MB |
| java `store info` | 63.4 | 63.7 | 64.2 | 63.7 MB |
| rust `store info` | 4.2 | 4.2 | 4.4 | 4.3 MB |

Same shape as the timing: Java's RSS is flat at ~63.5 MB whether it prints a version
string or resolves a store (~0.2 MB delta — all JVM baseline). Rust's grows ~0.8 MB
from `--version` to `store info`, which is the config parse and path resolution.

## On-disk footprint

| | Size |
|---|---:|
| Java install total | 103 MB |
| ├─ jlink runtime | 87 MB |
| └─ shared class cache | 16 MB |
| Rust binary | 4.2 MB |

The 16 MB SCC is a real cost of Java's fast path, and easy to overlook: the launcher
allocates a 64 MB sparse cache file (`ls` reports 67108864 bytes; 16 MB actually
resident). That cache is what buys 442 ms instead of something considerably worse, so
every Java figure in this document is the optimized configuration.

The 87 MB runtime is analysed separately in
[2026-06-14-jlink-image-size-runtime-breakdown.md](2026-06-14-jlink-image-size-runtime-breakdown.md);
that doc's conclusion is that ~75–78 MB is the realistic jlink-only floor on OpenJ9.

## Relation to earlier measurements

- The plan-102 explore recorded Java 103 MB / 69 MB RSS / ~450 ms vs Rust 2 MB /
  3.8 MB RSS / <10 ms. Timing and RSS reproduce closely here.
- The Rust binary has grown 2 MB → 4.2 MB since that explore, which is expected: the
  `store` command and its dependencies (clap, quick-xml, toml_edit, regex) have landed
  in the meantime.
- [2026-04-27-openj9-scc-startup-correction.md](2026-04-27-openj9-scc-startup-correction.md)
  measured ~340–360 ms median for `--help` on the same OpenJ9+SCC stack. This run
  measures ~462 ms. The runs are not directly comparable — different app version
  (more code and modules since April), and that benchmark used an isolated per-config
  SCC. The ~340 ms figure remains the better estimate of the *floor*; the point common
  to both is that the floor is hundreds of milliseconds, not tens.

## Caveats

- **Version skew:** Rust reports 0.3.34 against Java 0.3.36 (the known `Cargo.toml`
  skew). Immaterial for startup, which is dominated by runtime bootstrap.
- **Not feature-equivalent:** Rust implements only `store`; Java also has `plan` and
  `task`. `--help` output therefore differs in length. This measures startup and
  baseline overhead, not a like-for-like feature-complete CLI. A finished Rust port
  will be larger and marginally slower, though the JVM bootstrap gap is structural and
  will not close.
- **Single host, single session.** No cross-machine or cold-cache numbers.
- The Rust release binary is not checked in; it was built for this measurement.

## To reproduce

```bash
JAVA=~/.cache/shipsmooth/0.3.36/bin/shipsmooth
RUST=exp/rust/target/release/shipsmooth

# Build the Rust binary (~55 s)
export CARGO_HOME=/opt/cargo RUSTUP_HOME=/opt/installers/rustup PATH=/opt/cargo/bin:$PATH
(cd exp/rust && cargo build --release)

# Warm the SCC and page cache before timing — otherwise Java's first runs are outliers
for i in 1 2 3 4 5; do $JAVA --version >/dev/null 2>&1; $RUST --version >/dev/null 2>&1; done

# Startup: 20 runs, median wall-clock
for i in $(seq 1 20); do
  s=$(date +%s%N); $JAVA store info >/dev/null 2>&1; e=$(date +%s%N)
  echo $(( (e-s)/1000000 ))
done | sort -n | awk '{a[NR]=$1} END {print "median", a[int((NR+1)/2)], "ms"}'

# Peak RSS
/usr/bin/time -f "maxrss=%M KB" $JAVA store info >/dev/null
/usr/bin/time -f "maxrss=%M KB" $RUST store info >/dev/null

# Footprint
du -sh ~/.cache/shipsmooth/0.3.36/ ~/.cache/shipsmooth/0.3.36/runtime ~/.cache/shipsmooth/0.3.36/scc
du -h exp/rust/target/release/shipsmooth
```
