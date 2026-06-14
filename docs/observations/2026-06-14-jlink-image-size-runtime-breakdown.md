# Analysis of the jlink image size

Prepared 2026-06-14

## Current image (measured)

`./gradlew :cli:image_linux-x64` with `jdk-semeru/jdk-25.0.2+10`, flags
`--compress zip-9 --no-header-files --no-man-pages`:

- Total: 87 MB, 22 modules.

### Compression is on

| Build | `lib/modules` | Total |
|---|---|---|
| current (`--compress zip-9`) | 34 MB | 87 MB |
| same modules, `--no-compress` | 74 MB | 127 MB |

Compression is working and saving ~40 MB. It was not forgotten.

### Where the 87 MB goes

| Bucket | Size | jlink-controllable? |
|---|---|---|
| OpenJ9 VM `lib/default` | 33 MB | No — native `.so` runtime payload |
| `lib/modules` (java.base ~14 MB + app modules ~20 MB) | 34 MB | Partly (~5-6 MB realistic) |
| Semeru bundled OpenSSL (`libcrypto-semeru.so` 6.9 MB + `libssl-semeru.so` 1.3 MB) | 8.2 MB | No — TLS |
| AWT native libs from `java.desktop` | ~5.7 MB | Yes (see below) |
| locale `java_*.properties` | ~3 MB | Yes (`--include-locales=en`) |
| legal / conf / bin | ~0.6 MB | Marginal |

`lib/default` is dominated by `libj9jit29.so` = 17 MB (the OpenJ9 JIT compiler)
and `libj9gc*29.so` = 7 MB (GC). These are the OpenJ9 VM itself; jlink cannot shrink
native libraries.

---

## The one genuinely unnecessary module: `java.desktop`

A headless CLI uses zero AWT/Swing/java.beans, yet `java.desktop` — plus its
dependents `java.prefs` and `java.datatransfer` — is in the image, dragging ~5.7 MB
of native AWT libraries:

| Native lib | Size |
|---|---|
| `libfontmanager.so` | 1.8 MB |
| `libawt.so` | 0.8 MB |
| `libfreetype.so` | 0.7 MB |
| `libmlib_image.so` | 0.6 MB |
| `liblcms.so` | 0.6 MB |
| `libawt_xawt.so` | 0.55 MB |
| `libsplashscreen.so` | 0.36 MB |
| `libjavajpeg.so` | 0.24 MB |

This is also why the module footprint is far larger than the JAXB jars themselves:
the 7 JAXB-related jars sum to only ~1.3 MB on disk, but JAXB drags in ~14-16 MB of
JDK platform modules transitively, primarily `java.desktop` and its dependents.

### Why it's there

Two modules hard-`requires java.desktop` in their compiled module descriptor:

```
# org.glassfish.jaxb.runtime  — backs JAXB's built-in java.awt.Image XmlAdapter
requires java.desktop            ← hard, mandatory — jlink must include it
    # jar contains RuntimeBuiltinLeafInfoImpl$PcdataImpl<java.awt.Image>

# com.fasterxml.jackson.databind — over-declared
requires java.desktop            ← hard, but NO java.awt/beans class refs anywhere
    # verified by class-level jdeps + bytecode string scan
```

Note the contrast with `jakarta.activation`, which uses `requires java.desktop static`
(optional) and therefore does *not* force inclusion. The mandatory pull comes from
`jaxb-runtime` and `jackson-databind`. *Both* descriptors must be patched to remove
`java.desktop` — patching only `jaxb-runtime` is insufficient.

### Safe to remove for shipsmooth

The JAXB-bound model (`TaskType`, `PlanTasks`, `DeviationType`, enums, …) has no
`Image` / `DataHandler` / `@XmlMimeType` fields, so the desktop-backed marshalling
branch is unreachable.

### No jlink flag drops it

`--add-modules` (roots) and `--limit-modules` (observable universe) both leave
`java.desktop` in — jlink honors transitive `requires` unconditionally. Verified: a
`--limit-modules` probe excluding `java.desktop` still produced an 87 MB image with all
AWT libs present. The only mechanism is to patch `requires java.desktop` out of both
module descriptors and re-jar (the technique `core:reinjectModuleInfo` already uses for
core).

### Alternatives to the JAXB stack (if eliminating it entirely)

| Strategy | Effort | Est. delta | Notes |
|---|---|---|---|
| Keep JAXB | Zero | — | 87 MB, cached once per machine per version. Acceptable for a dev tool. |
| Patch out `java.desktop` only | Low-Med | ~5.7 MB native + modules | Keeps JAXB; re-jars two descriptors. |
| Jackson XML (StAX-based) | Low | ~5-10 MB net | No `java.desktop`; Jackson already used for JSON. Jackson-XML's own deps (~3-4 MB) offset some savings. Consolidates to one serialization lib. Verify the `java.desktop` cascade actually disappears with a throwaway jlink first. |
| Hand-written StAX | Medium | ~16 MB | Max savings; `javax.xml.stream` lives in `java.xml` (already required). ~300-400 LOC for this stable schema; every XSD change becomes manual code. |
| Eclipse MOXy | Skip | Variable | Same `jakarta.xml.bind` API → likely same `java.desktop` graph. Not a real win. |

## OpenJ9 vs HotSpot

HotSpot JDK 25 (`/opt/installers/jdk-25.0.2`, OpenJDK 64-Bit Server VM) is installed.
Apples-to-apples jlink: same app modules, same flags, only the VM differs.

| Image | Size |
|---|---|
| OpenJ9/Semeru, current (prod) | 87 MB |
| OpenJ9 java.base-only floor | 61 MB |
| HotSpot, same app modules | 66 MB |
| HotSpot, app minus java.desktop (projected) | ~58-60 MB |
| HotSpot java.base + java.xml + java.logging + jdk.crypto.ec | 47 MB |
| HotSpot java.base-only floor | 42 MB |

**Conclusion:** ~45 MB is real, but it is a HotSpot floor. On OpenJ9 the floor is
61 MB (17 MB JIT + 7 MB GC + 8.2 MB bundled OpenSSL). HotSpot is smaller because it
uses the system OpenSSL (no bundled crypto libs) and a smaller VM (`lib/server` 27 MB
vs OpenJ9 `lib/default` 33 MB).

### The trade-off a switch would involve

- **Gain:** ~21 MB (87→66 MB), or ~27 MB (→~60 MB) with the `java.desktop` cut on top.
- **Lose:** `-Xquickstart` and `-Xshareclasses` (the SCC launcher) are OpenJ9-only —
  the exact fast cold-start features the SCC launcher machinery exists for, on a CLI
  launched every Claude Code session. OpenJ9 was chosen for startup, not footprint.
  HotSpot's analogues (AppCDS/AOT) are different and not currently wired.
- **Scope:** all 4 platform targets are Semeru-pinned; a switch touches every target.
- Any decision needs a cold-start benchmark (OpenJ9+SCC vs HotSpot+AppCDS), not size
  alone.

## Realistic jlink-only outcome (no runtime change)

`java.desktop` cluster cut + `--include-locales=en` ⇒ ~9-12 MB off ⇒ ~75-78 MB.
Reaching ~45 MB is not achievable via jlink on OpenJ9; it requires the runtime switch
above.

## Public corroboration: the OpenJ9 floor is a known, accepted gap

The "OpenJ9 jlink images are bigger than HotSpot" effect is documented upstream, so the
61 MB OpenJ9 floor measured above is not specific to our build:

- Eclipse OpenJ9 issue [#4488](https://github.com/eclipse-openj9/openj9/issues/4488)
  ("OpenJ9 JLink produces a bigger JRE than Hotspot") reports minimal `java.base`-style
  images for Java 11:

  | OS | HotSpot | OpenJ9 |
  |---|---|---|
  | Linux | 46.8 MB | 52.6 MB |
  | Windows | 38.0 MB | 50.6 MB |

  The issue was moved to the Deep Backlog (i.e. accepted as a known difference, not a bug
  with a fix). Maintainers attribute the Linux delta partly to extra properties/`.dat`
  files; community comments also note OpenJ9 ships its own `libcrypto` that HotSpot does
  not. Both match what we see locally (the `j9ddr.dat` data file, bundled
  `libcrypto-semeru.so`/`libssl-semeru.so`).

- Container data referenced in the same discussion: Java 11 OpenJ9 + jlink ≈ 78 MB
  compressed vs HotSpot + jlink ≈ 74 MB; and on Windows OpenJ9 11 jlink ≈ 54 MB vs
  HotSpot ≈ 39 MB — the same ~10-15 MB OpenJ9 premium.

Caveat: those public figures are Java 11. Our measurements are Java 25, where both VMs
have grown, so the absolute numbers are larger here (HotSpot floor 42 MB, OpenJ9 61 MB),
but the direction and rough magnitude of the OpenJ9 premium are consistent.

IBM does not publish an official "minimal jlink floor size" figure for Semeru; their
size guidance is about the JRE-vs-JDK container images (the Red Hat Ecosystem Catalog
"Semeru Runtime ... UBI 9 Minimal" images), which is a different question from a jlinked
custom runtime.

## To reproduce this analysis

```bash
HS=/opt/installers/jdk-25.0.2                       # HotSpot
J9=/opt/installers/jdk-semeru/jdk-25.0.2+10         # OpenJ9/Semeru
MP=.agents/tmp/jlink-mp   # cli.jar + core-jlink.jar + runtimeClasspath jars

# OpenJ9 java.base floor
$J9/bin/jlink --module-path $J9/jmods --add-modules java.base \
  --no-header-files --no-man-pages --compress zip-9 --output /tmp/j9-base
du -sh /tmp/j9-base        # 61M

# HotSpot java.base floor
$HS/bin/jlink --module-path $HS/jmods --add-modules java.base \
  --no-header-files --no-man-pages --compress zip-9 --output /tmp/hs-base
du -sh /tmp/hs-base        # 42M

# HotSpot, same app modules as prod (minus openj9.sharedclasses, which HotSpot lacks)
$HS/bin/jlink --module-path "$MP:$HS/jmods" --add-modules io.bitken.ss.cli \
  --launcher shipsmooth=io.bitken.ss.cli/io.bitken.ss.cli.Shipsmooth \
  --no-header-files --no-man-pages --compress zip-9 --output /tmp/hs-app
du -sh /tmp/hs-app         # 66M

# Confirm both libs hard-require java.desktop (no "static" qualifier = mandatory):
$J9/bin/jar --describe-module --file $MP/jaxb-runtime-4.0.5.jar     | grep java.desktop
$J9/bin/jar --describe-module --file $MP/jackson-databind-2.17.2.jar | grep java.desktop

# Confirm compression is on (compare modules blob with/without --compress):
# current build => lib/modules 34M, total 87M ; --no-compress => 74M / 127M
```
