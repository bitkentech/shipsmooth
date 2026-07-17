# Cargo dependencies and the Java → Rust dependency map

Companion to [00-overview.md](00-overview.md). This is the complete third-party
surface of both implementations. Versions are indicative — pin latest stable at
migration start.

The headline: **11 Java artifacts (plus two codegen/build plugins) collapse to
7 runtime crates**, and the heaviest Java dependencies (JAXB, Dagger) map to
*nothing* — they are replaced by hand-written structs and plain construction.

## Proposed Cargo.toml (workspace)

```toml
[workspace]
members = ["crates/ss-core", "crates/ss-cli"]
resolver = "2"

[workspace.package]
version = "0.3.34"        # single source of truth; replaces gradle.properties plugin.version

[workspace.dependencies]
serde      = { version = "1", features = ["derive"] }
quick-xml  = { version = "0.37", features = ["serialize"] }
time       = { version = "0.3", features = ["formatting", "parsing", "macros"] }
thiserror  = "2"
regex      = "1"
serde_json = "1"
toml_edit  = { version = "0.22", features = ["serde"] }
clap       = { version = "4.5", features = ["derive"] }
tempfile   = "3"
assert_cmd = "2"
predicates = "3"
```

```toml
# crates/ss-core/Cargo.toml
[package]
name = "ss-core"
version.workspace = true

[features]
experimental = []          # replaces Build.EXPERIMENTAL_BUILD (baked by build.env)

[dependencies]
serde.workspace = true
quick-xml.workspace = true
time.workspace = true
thiserror.workspace = true
regex.workspace = true

[dev-dependencies]
tempfile.workspace = true
```

```toml
# crates/ss-cli/Cargo.toml
[package]
name = "ss-cli"
version.workspace = true

[[bin]]
name = "shipsmooth"
path = "src/main.rs"

[features]
experimental = ["ss-core/experimental"]

[dependencies]
ss-core = { path = "../ss-core" }
clap.workspace = true
serde.workspace = true
serde_json.workspace = true
toml_edit.workspace = true
thiserror.workspace = true

[dev-dependencies]
tempfile.workspace = true
assert_cmd.workspace = true
predicates.workspace = true
```

## Java → Rust, artifact by artifact

### core (main)

| Java dependency (version) | Used for | Rust replacement | Notes |
|---|---|---|---|
| `jakarta.xml.bind:jakarta.xml.bind-api` 4.0.2 (api) | JAXB API: `JAXBContext`, `Marshaller`/`Unmarshaller` in TaskStore; `JAXBException` in core's public API | **`quick-xml` + `serde`** | The whole marshal/unmarshal layer becomes `quick_xml::se`/`de` over hand-written structs. `JAXBException` in signatures → `ss_core::Error::Xml` variant |
| `org.glassfish.jaxb:jaxb-runtime` 4.0.5 | JAXB implementation at runtime | — (covered by quick-xml) | |
| xjc plugin (`com.github.bjornvester.xjc`) generating `io.bitken.ss.jaxb` from plan-tasks.xsd | The XML data model | **nothing — hand-written structs** in `ss_core::model` | Deliberate: the XSD is ~130 lines; explicit structs beat generated code (see 01-core.md §1). The XSD file is retained as documentation/spec + parity-test input |
| `jakarta.inject:jakarta.inject-api` 2.0.1 (api) | `Provider<T>` lazy service handles, `@Singleton` | **nothing** | Laziness existed for picocli tree construction on unsettled projects; clap parses before services are built (see 02-cli.md) |
| `com.google.dagger:dagger` 2.59.2 + `dagger-compiler` (annotation processor) | DI graph: ServicesModule/AppComponents | **nothing — plain `Services` struct** | Hand-constructed in one place; also removes the jlink shading the dagger jar required |
| `com.fasterxml.jackson.core:jackson-databind` 2.17.2, `jackson-datatype-jsr310` 2.17.2 | *Vestigial in core*: `module-info.java` does not `require` jackson and no core main/test source imports it (verified by grep, 2026-07-17) | **nothing** | Simply doesn't port. (Worth deleting from core/build.gradle.kts on the Java side regardless) |
| JDK `java.xml` (DOM, `DatatypeFactory`/`XMLGregorianCalendar`) | `xs:any` element handling, XML dates | `quick-xml` raw events + **`time`** newtypes | XSD lexical date/dateTime formats via `time` format descriptions |
| JDK `ProcessBuilder` | GitState/GitTags shelling to git | `std::process::Command` | stdlib, no crate; do **not** substitute `git2` |
| JDK `java.util.regex` | PlanMarkdownParser, PlanNumbers | **`regex`** | `(?im)` inline flags for `MULTILINE\|CASE_INSENSITIVE` |
| Checked exceptions | error signatures | **`thiserror`** | one `ss_core::Error` enum |
| java-templates `Build.java` (templating via Gradle `expand()`) | compile-time VERSION + EXPERIMENTAL_BUILD | `env!("CARGO_PKG_VERSION")` + **cargo feature `experimental`** | Features are part of the build fingerprint → the plan-75 stale-constants defect class disappears; no build.rs needed unless the schema URL (below) wants one |

### cli (main)

| Java dependency (version) | Used for | Rust replacement | Notes |
|---|---|---|---|
| `info.picocli:picocli` 4.7.5 | command tree, parsing, help/version, hidden flags, exit codes | **`clap`** (derive) | Hidden flag ↔ `hide`; `mixinStandardHelpOptions` ↔ built-in; the `opens ... to info.picocli` JPMS clauses vanish |
| `com.fasterxml.jackson.core:jackson-databind` 2.17.2 | ResolutionJson output | **`serde_json`** | Gate JSON must stay byte-identical — golden tests from `ResolutionJsonTest` |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` 2.17.2 | JSON time types | **`time`** (via ss-core) if any timestamps appear in JSON | likely unused in the gate payload — verify |
| `com.fasterxml.jackson.dataformat:jackson-dataformat-toml` 2.17.2 (read) + hand-rolled `ArrayOfTablesTomlEmitter` (write) | shipsmooth.toml read/write | **`toml_edit`** (with `serde`) | One crate, both directions, multi-line `[[projects]]` native — the plan-90 emitter workaround is deleted, not ported. `toml_edit` also preserves comments/layout on read-modify-write, which Jackson never could |
| java-templates `SchemaConfig.java` (schema URL per build variant) | `[toml-schema] location` value ConfigWriter emits | compile-time `env!("SS_SCHEMA_LOCATION")` set by **build.rs** (or a tiny `const` switched on the `experimental`/dev feature) | Keeps the plan-91 "one signal drives both variants" property: the feature flag is the signal |
| project(":core") | — | `ss-core = { path = "../ss-core" }` | |

### Test-scope

| Java | Used for | Rust replacement |
|---|---|---|
| `org.junit:junit-bom` 5.10.2 + `junit-jupiter` (all modules, via buildSrc conventions) | unit + integration tests | **built-in `#[test]`** — no framework dependency at all |
| JUnit `@TempDir` | temp fixtures | **`tempfile`** |
| Launching the CLI in integration tests (in-JVM `Shipsmooth` construction) | ShipsmoothIntegrationTest etc. | **`assert_cmd`** + **`predicates`** — spawn the real release binary; strictly closer to production than the in-JVM Java tests |
| `org.tomlj:tomlj` 1.1.1 (cli test-only) + hand-written `org.tomlschema` test package | validating emitted TOML in tests | **`toml_edit`** parse-back asserts (already a main dep). The `org.tomlschema` validator ports only if a surviving test genuinely needs schema semantics; expect to replace it with direct value asserts |

### Deliberately NOT used (and why)

| Crate | Why not |
|---|---|
| `git2` / `gix` | Java shells out to the user's git; that inherits their config, credential helpers, and hooks. Linking libgit2 would change behaviour. Keep `std::process::Command` |
| `chrono` | `time` covers the XSD lexical formats with a smaller footprint; pick one, never both |
| `dirs` / `directories` | Config path must reproduce the *Java* XDG resolution (`$XDG_CONFIG_HOME` else `~/.config`) on every OS — `dirs` uses platform-native locations on macOS/Windows, which would orphan existing user configs. ~10 lines by hand |
| `serde-xml-rs` / `xml-rs` | quick-xml is faster, maintained, and its event API is needed anyway for the `xs:any` raw-element preservation |
| `anyhow` | The error surface is small and typed end-to-end (`thiserror`); the bin maps `Error` → exit code + message explicitly, which the gate contract requires anyway |
| `clap` builder-only alternatives (`argh`, `pico-args`, `lexopt`) | Need subcommand trees, hidden options, and auto help/version parity with picocli — clap derive is the 1:1 fit |

## Supply-chain note

Runtime tree stays shallow: serde, quick-xml, time, regex, thiserror, clap,
serde_json, toml_edit and their transitive deps — all top-percentile,
widely-audited crates. `cargo vendor` + `cargo deny` (licenses + advisories) in
CI replaces the role the Gradle dependency lockfile plays today; NOTICE-file
updates for the new third-party set are part of the release-pipeline follow-up.
