# JAXB Bloats the jlink Runtime by ~16 MB

**Date:** 2026-04-28  
**Module:** `plugin-tasks-java`  
**Status:** Unresolved — options documented below

---

## Background

`plugin-tasks-java` uses JAXB (Glassfish Reference Implementation) to marshal/unmarshal
the plan task XML files at `.agents/plans/plan-N-tasks.xml`. The module declaration is:

```java
module io.bitken.ss {
    requires info.picocli;
    requires jakarta.xml.bind;
    requires java.xml;
    requires org.glassfish.jaxb.runtime;

    opens io.bitken.ss.commands to info.picocli;
    opens io.bitken.ss.jaxb to jakarta.xml.bind;
}
```

The only JAXB consumer is a single class:
`plugin-tasks-java/src/main/java/io/bitken/shipsmooth/tasks/service/XmlService.java`
(two methods: `readPlanTasks` and `writePlanTasks`, lines 31–42).

The 14 JAXB-annotated POJOs under `io.bitken.ss.jaxb` are generated at
compile time by `jaxb2-maven-plugin` from
`plugin-node/src/main/scripts/tasks/plan-tasks.xsd`.

---

## Measured Impact

Two minimal jlink images were built with `jdk-semeru jdk-25.0.2+10` using
`--strip-debug --compress zip-9 --no-header-files --no-man-pages`:

| Image | Total | `lib/modules` |
|---|---|---|
| picocli + java.xml only | **63 MB** | 16 MB |
| picocli + full JAXB stack | **79 MB** | 26 MB |
| **Delta** | **−16 MB** | **−10 MB** |

The currently shipped runtime (`~/.cache/shipsmooth-dev/runtime-0.2.0/`) is 85 MB total.

### Why 16 MB and not ~1.3 MB?

The 7 JAXB-related jars themselves sum to only ~1.32 MB on disk:

| Jar | Size |
|---|---|
| `jaxb-runtime-4.0.5.jar` | 920 KB |
| `jaxb-core-4.0.5.jar` | 139 KB |
| `jakarta.xml.bind-api-4.0.2.jar` | 131 KB |
| `txw2-4.0.5.jar` | 73 KB |
| `jakarta.activation-api-2.1.3.jar` | 66 KB |
| `angus-activation-2.0.2.jar` | 27 KB |
| `istack-commons-runtime-4.1.2.jar` | 26 KB |

The other ~14.7 MB is JDK platform modules transitively included by jlink, primarily
`java.desktop` and its dependents.

### Module graph delta

Without JAXB (3 modules): `java.base`, `java.xml`, `info.picocli`

With JAXB (14 modules): + `jakarta.xml.bind`, `org.glassfish.jaxb.runtime`,
`org.glassfish.jaxb.core`, `com.sun.xml.txw2`, `com.sun.istack.runtime`,
`jakarta.activation`, **`java.desktop`**, `java.datatransfer`, `java.compiler`,
`java.logging`, `java.prefs`

`java.desktop` brings AWT, font rendering, color management, imaging, and splash screen —
and their native libraries: `libawt.so` (800 KB), `libfontmanager.so` (1.8 MB),
`libfreetype.so` (724 KB), `libmlib_image.so` (600 KB), `liblcms.so` (596 KB),
`libawt_xawt.so` (556 KB), `libsplashscreen.so` (360 KB).

---

## Root Cause: `org.glassfish.jaxb.runtime` hard-requires `java.desktop`

Inspecting the module descriptor (`jar --describe-module`) reveals:

```
# jakarta.activation-api (NOT the culprit):
requires java.desktop static    ← optional, does NOT force jlink inclusion

# org.glassfish.jaxb.runtime (THE culprit):
requires java.desktop           ← hard, mandatory — jlink must include it
```

`jakarta.activation` uses `requires java.desktop static` (optional), so it does **not**
pull in `java.desktop`. The hard `requires java.desktop` comes from `jaxb-runtime` itself.

### `--limit-modules` does not help

Attempting `--limit-modules` to exclude `java.desktop` during jlink is silently ignored
— jlink still includes `java.desktop` because `org.glassfish.jaxb.runtime`'s compiled
module descriptor mandates it. There is no flag-based workaround with this version of JAXB.

---

## Options

| Strategy | Effort | Image size delta | Notes |
|---|---|---|---|
| **Keep JAXB** | Zero | — | 85 MB cached once per machine per version. Acceptable for a dev tool. |
| **Jackson XML** | Low | **−10–15 MB est.** | Uses StAX (in `java.xml`), no `java.desktop`. Jackson already used in `plugin-skill` for JSON (Plan 33). Can retain XSD-codegen POJOs via `jackson-module-jakarta-xmlbind-annotations`. Should be verified with a test jlink before committing to this path. |
| **Hand-written StAX** | Medium | **−16 MB** | Maximum savings. `javax.xml.stream` lives in `java.xml` (already required). ~300–400 LOC for read/write of this specific schema. Schema is stable, so maintenance cost is contained. Every future XSD change becomes a manual code change instead of a regen. |
| **Eclipse MOXy** | Skip | Variable | Same `jakarta.xml.bind` API, same JPMS module graph — likely same `java.desktop` problem. JPMS support is "finicky." Not a real win. |

### Recommended next step (if size matters)

Before planning a migration, build a throwaway jlink image with Jackson XML substituted
to confirm the `java.desktop` cascade actually disappears. Jackson XML's own deps
(`jackson-core`, `jackson-databind`, `jackson-annotations`, `jackson-dataformat-xml`,
`woodstox-core`, `stax2-api`) sum to ~3–4 MB, so net savings are likely 5–10 MB, not
the full 16 MB. The consolidation benefit (one serialization library across modules) is
an additional argument for this path.

---

## Reproduction

```bash
JDK=/opt/installers/jdk-semeru/jdk-25.0.2+10
M2=$HOME/.m2/repository

# Without JAXB
$JDK/bin/jlink \
  --module-path $M2/info/picocli/picocli/4.7.5/picocli-4.7.5.jar \
  --add-modules info.picocli,java.base,java.xml \
  --output /tmp/jlink-no-jaxb \
  --no-header-files --no-man-pages --strip-debug --compress zip-9
du -sh /tmp/jlink-no-jaxb              # 63M
du -sh /tmp/jlink-no-jaxb/lib/modules  # 16M

# With JAXB
$JDK/bin/jlink \
  --module-path $M2/info/picocli/picocli/4.7.5/picocli-4.7.5.jar:\
$M2/jakarta/xml/bind/jakarta.xml.bind-api/4.0.2/jakarta.xml.bind-api-4.0.2.jar:\
$M2/jakarta/activation/jakarta.activation-api/2.1.3/jakarta.activation-api-2.1.3.jar:\
$M2/org/glassfish/jaxb/jaxb-runtime/4.0.5/jaxb-runtime-4.0.5.jar:\
$M2/org/glassfish/jaxb/jaxb-core/4.0.5/jaxb-core-4.0.5.jar:\
$M2/org/eclipse/angus/angus-activation/2.0.2/angus-activation-2.0.2.jar:\
$M2/org/glassfish/jaxb/txw2/4.0.5/txw2-4.0.5.jar:\
$M2/com/sun/istack/istack-commons-runtime/4.1.2/istack-commons-runtime-4.1.2.jar \
  --add-modules info.picocli,java.base,java.xml,jakarta.xml.bind,org.glassfish.jaxb.runtime \
  --output /tmp/jlink-with-jaxb \
  --no-header-files --no-man-pages --strip-debug --compress zip-9
du -sh /tmp/jlink-with-jaxb              # 79M
du -sh /tmp/jlink-with-jaxb/lib/modules  # 26M

# Verify java.desktop is hard-required (culprit):
$JDK/bin/jar --describe-module \
  --file=$M2/org/glassfish/jaxb/jaxb-runtime/4.0.5/jaxb-runtime-4.0.5.jar \
  | grep "java.desktop"
# Output: "requires java.desktop" (no "static" qualifier = mandatory)
```