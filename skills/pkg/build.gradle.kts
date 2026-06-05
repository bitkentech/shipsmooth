import com.github.gradle.node.npm.task.NpmTask

plugins {
    // Convention plugin from buildSrc: Semeru 25 toolchain, UTF-8, mavenLocal,
    // JUnit Jupiter. The JTE and render wiring is added in later plan-71 tasks.
    id("shipsmooth.java-conventions")
    // node-gradle: real input/output tracking for the npm/tsc pipeline,
    // replacing the brittle `dist -nt tasks/session-start.ts` mtime hack.
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    // System Node (v18 on this box) — do not download a Node distribution.
    download.set(false)
    nodeProjectDir.set(file("scripts"))
}

// npm install, tracked by package-lock.json -> node_modules.
tasks.named<com.github.gradle.node.npm.task.NpmInstallTask>("npmInstall") {
    inputs.file("scripts/package-lock.json")
    outputs.dir("scripts/node_modules")
}

// `npm run build` (tsc + esbuild bundle), tracked by the TS sources -> dist.
val compileTs by tasks.registering(NpmTask::class) {
    dependsOn("npmInstall")
    args.set(listOf("run", "build"))
    workingDir.set(file("scripts"))
    inputs.dir("scripts/tasks")
    inputs.file("scripts/package.json")
    inputs.file("scripts/tsconfig.json")
    outputs.dir("scripts/dist")
}
