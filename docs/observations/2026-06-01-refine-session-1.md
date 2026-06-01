# Refine Session 1: Target.java

**Date:** 2026-06-01  
**File:** `integrations/common/src/main/java/io/bitken/ss/resources/Target.java`  
**Skill:** `experimental-refine-dev`

---

## Initial version

```java
package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

public record Target(Platform platform, Os os, Env env) {

    public static void main(String[] args) throws IOException {
        fromProperties().build();
    }

    private static Target fromProperties() {
        return Target.from(
            System.getProperty("build.platform", "claude"),
            System.getProperty("build.os", "posix"),
            System.getProperty("build.env", "prod")
        );
    }

    public static Target from(String platformProp, String osProp, String envProp) {
        Platform platform = Platform.from(platformProp);
        Os os = Os.from(osProp);
        if (os == Os.WINDOWS && platform != Platform.CLAUDE) {
            throw new IllegalArgumentException("Windows is only supported with the Claude platform, got: " + platformProp);
        }
        Env env = Env.from(envProp);
        if (os == Os.WINDOWS && env == Env.DEV) {
            throw new IllegalArgumentException("Windows + Dev environment is not supported");
        }
        return new Target(platform, os, env);
    }

    private void build() throws IOException {
        String basePluginName = System.getProperty("plugin.base.name");
        String startBase      = System.getProperty("plugin.skill.start.basename");
        PluginModel baseModel = buildPluginModel(
            basePluginName,
            System.getProperty("plugin.version"),
            System.getProperty("plugin.description"),
            startBase,
            System.getProperty("skill.frontmatter", ""),
            System.getProperty("shipsmooth.jlink.dir", ""),
            System.getProperty("plugin.repo.name")
        );
        Path outputDir = Path.of(System.getProperty("build.outputDir"));
        ObjectMapper mapper = new ObjectMapper();
        boolean experimentalEnabled = Boolean.parseBoolean(System.getProperty("experimental.enabled", "false"));

        SkillRenderer skills = new SkillRenderer(baseModel, outputDir, startBase);
        skills.renderBase();
        if (experimentalEnabled) {
            skills.renderExperimental();
        }
        new HooksRenderer(mapper, baseModel, outputDir).write();
        new SessionStartConfigRenderer(mapper, baseModel, outputDir).write();
    }

    public PluginModel buildPluginModel(
            String basePluginName, String version, String description,
            String startBase, String frontmatter, String jlinkDir, String repoName) {
        String name        = env.decorate(basePluginName);
        String cacheSubdir = platform.cacheSubdir(basePluginName, env);
        return new PluginModel(
            name, version, description,
            env.decorate(startBase),
            os.cliBinPath(basePluginName, version, cacheSubdir),
            frontmatter,
            skillFragmentDir(),
            platform instanceof Platform.Gemini,
            os, env, jlinkDir,
            repoName != null ? repoName : name
        );
    }

    public String cliBin(String pluginName, String version) {
        return os.cliBinPath(pluginName, version, platform.cacheSubdir(pluginName, env));
    }

    public String skillFragmentDir() {
        return platform.skillFragmentDir();
    }

    public String launcherFileName() {
        return os.launcherFileName();
    }
}
```

---

## Chronological back-and-forth

### Step 1 — LLM proposes: split `Target` into `Target` (pure value) + `BuildRunner` (entry point)

**Rule applied:** Single Responsibility — `Target` was both a value type `(platform, os, env)` and a build
orchestrator with `main()` + `build()`.

**User response:** "No that violates anemic domain"

**Lesson:** Moving `build()` out of `Target` strips it of behavior, leaving a data bag. Rich domain models
should have methods that *do* things. `build()` is legitimate behavior on `Target`.

---

### Step 2 — LLM narrows: only lift `main()` + `fromProperties()` out into `TargetMain`

**Rule applied:** Same SRP, narrower scope — only the entry-point concern moves out.

**User response:** "No. See class structure rule"

**Lesson:** The class structure rule says all `new` invocations and external dependencies should be resolved
in the constructor, not pulled lazily inside instance methods. The real violation was `build()` calling
`System.getProperty()` nine times — not that `main()` lived on the class.

---

### Step 3 — LLM proposes Option B: expand `Target` to hold all build fields; `fromProperties()` does all reading

**Two options were offered:**
- Option A: pure `(platform, os, env)` record + separate `TargetBuild` class
- Option B: `Target` becomes the full build configuration; one canonical constructor path via `fromProperties()`

**User chose Option B.** LLM noted tests call `Target.from(String, String, String)` directly and that
factory must stay.

**Applied:** `record` → `class`; all fields `private final`; `fromProperties()` reads all properties and
constructs everything; `build()` becomes pure orchestration.

---

### Step 4 — User flags: weird double construction in `fromProperties()`

```java
Target base = new Target(platform, os, env, null, null, null, false, null);  // half-null object
PluginModel baseModel = base.buildPluginModel(...);
return new Target(platform, os, env, baseModel, ...);  // real object
```

**LLM fix:** Extracted `buildPluginModel` as `private static` taking `(Platform, Os, Env, ...)` so
`fromProperties()` could call it without constructing an intermediate `Target`.

**But:** This left a wrapper instance method that did nothing but unpack `this` and delegate to the static.
User flagged this next.

---

### Step 5 — User flags: wrapper instance method is noise

```java
public PluginModel buildPluginModel(String ...) {
    return buildPluginModel(platform, os, env, ...);  // just unpacking this
}
```

**LLM realises:** The root tension is that `fromProperties()` is static and needs `buildPluginModel` before
construction is complete. The real fix: `fromProperties()` calls `from()` to get a validated base, then
calls `base.buildPluginModel()` on it — one purposeful intermediate object, not a null-field hack.
Single `buildPluginModel` instance method restored with logic directly inside it.

---

### Step 6 — User asks: why does `fromProperties()` exist at all?

`main()` is its only caller.

**LLM fix:** Inlined `fromProperties()` into `main()`. `main()` now does all property reads and
construction directly.

---

### Step 7 — User asks: why not move most of `main()` into the constructor?

**LLM applies class structure rule fully:** Constructor takes raw string properties, parses
`Platform`/`Os`/`Env` via private static helpers, calls `guard()`, builds `PluginModel`, constructs all
renderers. `main()` becomes `new Target(...System.getProperty()...).build()`.

`fromProperties()` disappears. Two constructors remain: the full one for `main()`, and a lean
`private Target(Platform, Os, Env)` for `from()` which tests need.

---

### Step 8 — User flags: `build()` still does `new` for renderers

**LLM fix:** Move `new SkillRenderer(...)`, `new HooksRenderer(...)`, `new SessionStartConfigRenderer(...)`
into the constructor as fields. `build()` becomes pure method calls on already-constructed fields.

---

### Step 9 — User asks: are the two constructors needed?

The lean constructor exists only because `from()` exists, which exists only because tests call it.

**LLM traces what tests actually do with the lean `Target`:** `buildPluginModel()`, `cliBin()`,
`skillFragmentDir()`, `launcherFileName()`, accessors. The last three are pure delegates already covered
in `PlatformTest`/`OsTest`. Only `buildPluginModel` has real logic.

---

### Step 10 — Make `buildPluginModel` package-visible static; rewrite tests

**Applied:** `private static buildPluginModel(Platform, Os, Env, ...)` → `static` (package-visible).
Tests call `Target.buildPluginModel(Platform.CLAUDE, Os.POSIX, Env.PROD, ...)` directly.
`from()`, lean constructor, and all delegate methods (`cliBin`, `skillFragmentDir`, `launcherFileName`,
accessors) removed. `TargetTest` reduced to 3 guard tests. 11 duplicate tests dropped.

**User interrupts:** "Don't take out buildPluginModel into static. Undo last change."
*(Change was reverted.)*

---

### Step 11 — User asks: is `static PluginModel buildPluginModel` needed? It's just calling `PluginModel` ctor

**LLM suggests:** Move to `PluginModel.from(Platform, Os, Env, ...)` static factory.

**User:** "Why not directly call `PluginModel`'s ctor with required params?"

**LLM applies:** Inlined the two local derivations (`name`, `cacheSubdir`) directly into the constructor
body and called `new PluginModel(...)` there. `buildPluginModel` static deleted from `Target`.
Tests updated to construct `PluginModel` directly using `Platform`/`Os`/`Env` methods inline.

---

## Final version

```java
package io.bitken.ss.resources;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

public class Target {

    private final SkillRenderer skillRenderer;
    private final HooksRenderer hooksRenderer;
    private final SessionStartConfigRenderer sessionStartConfigRenderer;
    private final boolean experimentalEnabled;

    Target(String platformProp, String osProp, String envProp,
           String basePluginName, String version, String description,
           String startBase, String frontmatter, String jlinkDir, String repoName,
           String outputDir, boolean experimentalEnabled) {
        Platform platform       = Platform.from(platformProp);
        Os os                   = Os.from(osProp);
        Env env                 = Env.from(envProp);
        guard(os, platform, env);
        String name             = env.decorate(basePluginName);
        String cacheSubdir      = platform.cacheSubdir(basePluginName, env);
        PluginModel baseModel   = new PluginModel(
            name, version, description,
            env.decorate(startBase),
            os.cliBinPath(basePluginName, version, cacheSubdir),
            frontmatter,
            platform.skillFragmentDir(),
            platform instanceof Platform.Gemini,
            os, env, jlinkDir,
            repoName != null ? repoName : name
        );
        Path outDir             = Path.of(outputDir);
        ObjectMapper mapper     = new ObjectMapper();
        this.skillRenderer              = new SkillRenderer(baseModel, outDir, startBase);
        this.hooksRenderer              = new HooksRenderer(mapper, baseModel, outDir);
        this.sessionStartConfigRenderer = new SessionStartConfigRenderer(mapper, baseModel, outDir);
        this.experimentalEnabled        = experimentalEnabled;
    }

    public static void main(String[] args) throws IOException {
        new Target(
            System.getProperty("build.platform", "claude"),
            System.getProperty("build.os", "posix"),
            System.getProperty("build.env", "prod"),
            System.getProperty("plugin.base.name"),
            System.getProperty("plugin.version"),
            System.getProperty("plugin.description"),
            System.getProperty("plugin.skill.start.basename"),
            System.getProperty("skill.frontmatter", ""),
            System.getProperty("shipsmooth.jlink.dir", ""),
            System.getProperty("plugin.repo.name"),
            System.getProperty("build.outputDir"),
            Boolean.parseBoolean(System.getProperty("experimental.enabled", "false"))
        ).build();
    }

    void build() throws IOException {
        skillRenderer.renderBase();
        if (experimentalEnabled) {
            skillRenderer.renderExperimental();
        }
        hooksRenderer.write();
        sessionStartConfigRenderer.write();
    }

    static void guard(Os os, Platform platform, Env env) {
        if (os == Os.WINDOWS && platform != Platform.CLAUDE) {
            throw new IllegalArgumentException(
                "Windows is only supported with the Claude platform, got: " + platform.id());
        }
        if (os == Os.WINDOWS && env == Env.DEV) {
            throw new IllegalArgumentException("Windows + Dev environment is not supported");
        }
    }
}
```
