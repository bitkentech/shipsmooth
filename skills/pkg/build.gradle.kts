import com.github.gradle.node.npm.task.NpmInstallTask
import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.tasks.PathSensitivity.RELATIVE

plugins {
    // Convention plugin from buildSrc: Semeru 25 toolchain, UTF-8, mavenLocal,
    // JUnit Jupiter. The JTE and render wiring is added in later plan-71 tasks.
    id("shipsmooth.java-conventions")
    // node-gradle: real content-hashed input/output tracking for the npm/tsc
    // pipeline, replacing the brittle `dist -nt tasks/session-start.ts` mtime
    // hack (a correctness fix — see docs/proposals/build-migrate.md §2).
    id("com.github.node-gradle.node") version "7.1.0"
    // gg.jte.gradle: precompile the staged .jte templates straight into
    // compileJava, collapsing the antrun + jte-maven + build-helper chain.
    id("gg.jte.gradle") version "3.1.15"
}

dependencies {
    // jte + jackson are needed both by the generated template classes and by
    // the Target/SkillRenderer code in src/main/java (parity with pom.xml).
    implementation("gg.jte:jte:3.1.15")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

node {
    // System Node (v18 on this box) — do not download a Node distribution,
    // matching the exec-maven-plugin behaviour in pom.xml.
    download.set(false)
    nodeProjectDir.set(file("scripts"))
}

// `npm install`, keyed on package-lock.json -> node_modules. node-gradle runs in
// nodeProjectDir (scripts/), so no per-task workingDir is needed.
tasks.named<NpmInstallTask>("npmInstall") {
    inputs.file("scripts/package-lock.json").withPathSensitivity(RELATIVE)
    outputs.dir("scripts/node_modules")
}

// `npm run build` (tsc + esbuild bundle). Hashing the whole scripts/tasks tree
// plus the build config kills the single-sentinel-file staleness bug the old
// mtime check had: edit any source and the rebuild fires; touch alone does not.
val compileTs by tasks.registering(NpmTask::class) {
    dependsOn(tasks.named<NpmInstallTask>("npmInstall"))
    args.set(listOf("run", "build"))

    inputs.dir("scripts/tasks").withPathSensitivity(RELATIVE)
    inputs.file("scripts/package.json").withPathSensitivity(RELATIVE)
    inputs.file("scripts/tsconfig.json").withPathSensitivity(RELATIVE)
    outputs.dir("scripts/dist")
}

// Reproduce the antrun rename step: copy the sibling start/, experimental/,
// shared/ trees from the repo's skills/ dir into a staging root, renaming
// *.jte.md -> *.jte. The start/experimental/shared prefix is preserved so the
// generated template names match the literals in SkillRenderer
// (e.g. "start/SKILL.jte") and the gg.jte.generated.precompiled.* packages
// match the Maven output.
val skillsDir = layout.projectDirectory.dir("..") // skills/pkg -> skills/
val stageJte by tasks.registering(Copy::class) {
    listOf("start", "experimental", "shared").forEach { dir ->
        from(skillsDir.dir(dir)) { into(dir) }
    }
    rename("(.*)\\.jte\\.md", "$1.jte")
    into(layout.buildDirectory.dir("jte-src"))
}

jte {
    // Generate .java into build/generated-sources/jte and wire them into the
    // main source set's compileJava — mirroring the Maven jte-maven-plugin
    // `generate` goal + build-helper add-source (so the generated artifacts and
    // gg.jte.generated.precompiled.* packages match the Maven output for parity).
    sourceDirectory.set(layout.buildDirectory.dir("jte-src").get().asFile.toPath())
    contentType.set(gg.jte.ContentType.Plain)
    generate()
}

// generateJte reads the staged .jte tree, so it must run after stageJte.
tasks.named("generateJte") { dependsOn(stageJte) }
