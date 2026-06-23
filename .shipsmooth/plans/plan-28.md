# Plan 28: jlink Build for plugin-tasks-java with IBM Semeru

Produce a small, self-contained custom Java runtime image for the `plugin-tasks-java` module using `jlink` against the IBM Semeru OpenJ9 JDK 25.0.2+10 located at `/opt/installers/jdk-semeru/jdk-25.0.2+10`.

## Context

The `plugin-tasks-java` module (delivered in plan 27) currently builds a GraalVM Native Image binary. We want a complementary distribution path that produces a self-contained directory image via `jlink` — useful where Native Image is not feasible and where OpenJ9's smaller footprint (vs HotSpot) is desirable. The output is a directory tree under `target/` containing a stripped-down Java runtime, the application as a real JPMS module, and a generated `bin/shipsmooth-tasks` launcher script.

The cleanest path on JVMs is moditect (adds `module-info` to non-modular dependencies and to the app jar) + `maven-jlink-plugin`. JAXB requires hand-written module overrides because of its `ServiceLoader` usage and split-package pitfalls — we accept that as known config rather than research.

## Design Decisions

- **Profile-gated:** all jlink work lives behind `-Pjlink`. Default `mvn package` is unaffected and does not require Semeru to be installed.
- **Toolchain pinning:** the Semeru JDK path is configured via a property `<jlink.jdk.home>` (default `/opt/installers/jdk-semeru/jdk-25.0.2+10`), passed to moditect's `<jdkHome>` and to maven-jlink-plugin's `<jdkToolchain>` / `<sourceJdkModules>`.
- **moditect:** `add-module-info` for picocli, jakarta.xml.bind-api, jakarta.activation-api, jaxb-runtime, angus-activation, and the app jar itself. JAXB modules get explicit `uses`/`provides` overrides for `jakarta.xml.bind.JAXBContextFactory` and friends.
- **maven-jlink-plugin:** packaging stays `jar` for the module; jlink runs in the `package` phase under the profile. `--launcher shipsmooth-tasks=com.github.pramodbiligiri.shipsmooth.tasks/com.github.pramodbiligiri.shipsmooth.tasks.TasksCli`. Flags: `--strip-debug --no-header-files --no-man-pages --compress=zip-9` (zstd is HotSpot-only; OpenJ9's jlink supports `zip-9`).
- **Output:** `target/jlink-image/` containing `bin/`, `lib/`, `legal/`, etc. Smoke-test by running `target/jlink-image/bin/shipsmooth-tasks --help` from the build.
- **Scope:** Linux x86_64 only. Cross-platform is out of scope (jlink runtimes are platform-specific).

## Risk Analysis (default levels — pending human override)

- **Task 1: Add jlink profile skeleton & toolchain wiring [Low]** — straightforward Maven plumbing; profile, properties, and a no-op exec verifying the Semeru `jlink` is reachable.
- **Task 2: Add moditect overrides for non-modular deps [High]** — JAXB module descriptors are the known pain point. Requires getting `uses`/`provides` for `JAXBContextFactory` and the activation `DataContentHandler` SPI right; angus-activation has overlapping packages with jakarta.activation-api in some versions.
- **Task 3: Add module-info to the app jar [Medium]** — moditect must declare correct `requires` for picocli + JAXB modules; one missing transitive break the modular layer at jlink time.
- **Task 4: Configure maven-jlink-plugin to produce the image [Medium]** — getting `<modulePath>`, launcher class, and compression flags right against OpenJ9's jlink. Image must build cleanly.
- **Task 5: End-to-end smoke test of the launcher [Low]** — execute `target/jlink-image/bin/shipsmooth-tasks --help` and a real subcommand (e.g. `show --plan 27`) against a fixture; assert exit code 0 and expected output.

## Tasks

### Task 1: Add jlink profile skeleton & toolchain wiring [Low]
- Add `<jlink.jdk.home>` property (default `/opt/installers/jdk-semeru/jdk-25.0.2+10`) to `plugin-tasks-java/pom.xml`.
- Add a `<profile id="jlink">` containing (initially) only an `exec-maven-plugin` execution that runs `${jlink.jdk.home}/bin/jlink --version` in the `validate` phase, so a missing JDK fails fast with a clear error.
- Verify `mvn -Pjlink validate` prints the OpenJ9 jlink version.

### Task 2: Add moditect overrides for non-modular deps [High]
- Add `org.moditect:moditect-maven-plugin` to the jlink profile.
- Configure `add-module-info` in the `generate-resources` phase for: `info.picocli:picocli`, `jakarta.xml.bind:jakarta.xml.bind-api`, `jakarta.activation:jakarta.activation-api`, `org.glassfish.jaxb:jaxb-runtime`, `org.glassfish.jaxb:jaxb-core`, `org.glassfish.jaxb:txw2`, `com.sun.istack:istack-commons-runtime`, and `org.eclipse.angus:angus-activation` (transitive resolution to be confirmed during implementation).
- For `jaxb-runtime` add explicit `provides jakarta.xml.bind.JAXBContextFactory with org.glassfish.jaxb.runtime.v2.JAXBContextFactory;`.
- For `jakarta.xml.bind-api` add `uses jakarta.xml.bind.JAXBContextFactory;`.
- Run `mvn -Pjlink generate-resources` and confirm modularised jars appear under `target/modules/`.
- Run `${jlink.jdk.home}/bin/jdeps --module-path target/modules ...` against a sample modularised jar to sanity-check resolution.

### Task 3: Add module-info to the app jar [Medium]
- Add a moditect `add-module-info` execution for the project's own jar declaring module name `com.github.pramodbiligiri.shipsmooth.tasks` with `requires` for `info.picocli`, `jakarta.xml.bind`, `org.glassfish.jaxb.runtime`, and `java.xml`.
- Set `mainClass` to `com.github.pramodbiligiri.shipsmooth.tasks.TasksCli`.
- Verify the modularised app jar resolves: `${jlink.jdk.home}/bin/java --module-path target/modules --module com.github.pramodbiligiri.shipsmooth.tasks --help`.

### Task 4: Configure maven-jlink-plugin to produce the image [Medium]
- Add `org.apache.maven.plugins:maven-jlink-plugin` to the jlink profile, bound to `package`.
- Configure: `<modulePath>` pointing at moditect output + `${jlink.jdk.home}/jmods`; `<modulesToLink>` listing the app module; `<launcher>shipsmooth-tasks=com.github.pramodbiligiri.shipsmooth.tasks/com.github.pramodbiligiri.shipsmooth.tasks.TasksCli</launcher>`; `<stripDebug>true</stripDebug>`; `<noHeaderFiles>true</noHeaderFiles>`; `<noManPages>true</noManPages>`; `<compress><compression>2</compression></compress>` (zip-9).
- Output directory: `target/jlink-image/`.
- Verify `mvn -Pjlink package` produces the image; check size with `du -sh target/jlink-image`.

### Task 5: End-to-end smoke test of the launcher [Low]
- Add an `exec-maven-plugin` execution under the jlink profile in the `verify` phase that runs `target/jlink-image/bin/shipsmooth-tasks --help` and asserts exit 0.
- Add a second smoke check running `target/jlink-image/bin/shipsmooth-tasks show --plan 27` against the existing `plan-27-tasks.xml` fixture; capture stdout and grep for an expected task heading.
- Document the build command in `plugin-tasks-java/README.md` (or top-level README if that's where build instructions live — confirm during implementation).

## Critical Files

- `plugin-tasks-java/pom.xml` — all profile/plugin configuration lives here.
- `plugin-tasks-java/src/main/java/com/github/pramodbiligiri/shipsmooth/tasks/TasksCli.java` — main class referenced by jlink launcher.
- `/opt/installers/jdk-semeru/jdk-25.0.2+10/bin/{jlink,jdeps,java}` — Semeru OpenJ9 toolchain.
- `.agents/plans/plan-27-tasks.xml` — fixture for the smoke test in Task 5.

## Verification

End-to-end success criteria, runnable from the repo root:

```bash
# Default build still works without Semeru:
mvn -pl plugin-tasks-java -am package

# jlink build produces a working image:
mvn -pl plugin-tasks-java -am -Pjlink clean verify

# Manual smoke:
plugin-tasks-java/target/jlink-image/bin/shipsmooth-tasks --help
plugin-tasks-java/target/jlink-image/bin/shipsmooth-tasks show --plan 27

# Size sanity (expect well under 60 MB):
du -sh plugin-tasks-java/target/jlink-image
```

The image must run without any external Java installation on the host.
