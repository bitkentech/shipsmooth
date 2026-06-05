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
