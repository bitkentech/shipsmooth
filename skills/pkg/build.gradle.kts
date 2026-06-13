plugins {
    // Convention plugin from buildSrc: toolchain, UTF-8, mavenLocal, JUnit Jupiter.
    id("shipsmooth.java-conventions")
    // gg.jte.gradle: precompile the staged .jte templates straight into compileJava.
    id("gg.jte.gradle") version "3.1.15"
}

// skills:pkg is now skill-rendering ONLY (plan-79): SkillRenderer + the JTE
// staging/generation that feeds it. Target, HooksRenderer, SessionStartConfigRenderer,
// the scripts/ TS hook, install-shipsmooth.sh, and the render*/copyDist* tasks moved
// to :plugin-resources. The .jte.md content stays a sibling at skills/{start,
// experimental,shared} for human editors and is staged via dir("..") below.
dependencies {
    // Shared value types (Os, Platform, Env, PluginModel) live in :plugin-model.
    implementation(project(":plugin-model"))
    // jte is needed by both the generated template classes and SkillRenderer.
    implementation("gg.jte:jte:3.1.15")
}

// Reproduce the antrun rename step: copy the sibling start/, experimental/,
// shared/ trees from the repo's skills/ dir into a staging root, renaming
// *.jte.md -> *.jte. The start/experimental/shared prefix is preserved so the
// generated template names match the literals in SkillRenderer
// (e.g. "start/SKILL.jte") and the gg.jte.generated.precompiled.* packages
// match the Maven output.
val skillsDir = layout.projectDirectory.dir("..") // skills/pkg -> skills/
val jteSrcDir = layout.buildDirectory.dir("jte-src") // single source of truth
val stageJte by tasks.registering(Copy::class) {
    listOf("start", "experimental", "shared").forEach { dir ->
        from(skillsDir.dir(dir)) { into(dir) }
    }
    rename("(.*)\\.jte\\.md", "$1.jte")
    into(jteSrcDir)
}

jte {
    // Generate .java into build/generated-sources/jte and wire them into the
    // main source set's compileJava — mirroring the Maven jte-maven-plugin
    // `generate` goal + build-helper add-source (so the generated artifacts and
    // gg.jte.generated.precompiled.* packages match the Maven output for parity).
    // Resolve the staged dir lazily (provider) rather than eagerly at config time.
    sourceDirectory.set(jteSrcDir.map { it.asFile.toPath() })
    contentType.set(gg.jte.ContentType.Plain)
    generate()
}

// generateJte reads the staged .jte tree, so it must run after stageJte.
tasks.named("generateJte") { dependsOn(stageJte) }
