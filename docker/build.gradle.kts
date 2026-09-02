plugins {
    // Shared conventions: JDK 25 toolchain / release 21, UTF-8, JUnit Jupiter, jacoco.
    id("shipsmooth.java-conventions")
}

dependencies {
    // Match the jackson line used by :cli / :packaging.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
}

// ===========================================================================
// shipsmooth-claude image build tooling (io.bitken.ss.docker), folded in from a
// standalone build repo (plan-113). This module has NO internal deps: it is a
// downstream consumer of the *published* plugin — the Dockerfile runs
// `claude plugin install shipsmooth` against the bitkentech marketplace — not a
// participant in the plugin build graph. A branch-built image therefore ships
// the last *released* plugin, not this working tree.
//
// The shipsmooth version baked into the image comes from the root
// `plugin.version` (the single source of truth every module uses); nothing is
// pinned here. See docker/README.md for the maintainer build/publish flow.
// ===========================================================================

val shipsmoothVersion = providers.gradleProperty("plugin.version").get()
val dockerRepo = providers.gradleProperty("dockerRepo").getOrElse("bitkentech/shipsmooth-claude")
val explicitImage = providers.gradleProperty("image").getOrElse("")

// BuildAndPushImage resolves the Dockerfile, the build context, and the rendered
// build/overview.md from user.dir. Run from the repo root via
// `./gradlew :docker:<task>`, that would be the repo root — so pin the working
// dir to this module.
val moduleDir = layout.projectDirectory.asFile

// Prints `key=value` lines: claude-code (npm stable), shipsmooth, compound-tag.
// buildImage consumes the same resolution; also runnable on its own to see what
// a build would bake in.
tasks.register<JavaExec>("resolveVersions") {
    group = "docker"
    description = "Resolve the component versions to bake into the sandbox image."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.bitken.ss.docker.ResolveVersions"
    args(shipsmoothVersion)
}

// Build the image locally, no push. Pass -Pimage=repo:tag for a single explicit
// tag (the smoke test does this); otherwise the standard latest/dated/compound set.
tasks.register<JavaExec>("buildImage") {
    group = "docker"
    description = "Build the sandbox image locally (no push)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.bitken.ss.docker.BuildAndPushImage"
    workingDir = moduleDir
    args("build", dockerRepo, shipsmoothVersion, explicitImage)
}

// WIRED ONLY — never depended on by an aggregate task. Outward-facing and hard to
// undo: pushes image tags + the repository Overview to Docker Hub. Needs a prior
// `docker login` and DOCKERHUB_USERNAME / DOCKERHUB_TOKEN in the environment.
tasks.register<JavaExec>("buildAndPush") {
    group = "docker"
    description = "Build + publish the sandbox image and Overview to Docker Hub. Run deliberately."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.bitken.ss.docker.BuildAndPushImage"
    workingDir = moduleDir
    args("build-and-push", dockerRepo, shipsmoothVersion)
}

// Check an image's immutable labels against the mutable Overview (default) or
// against the image's own runtime with -Plocal=true. Runnable on any tag any time.
tasks.register<JavaExec>("validateLabels") {
    group = "docker"
    description = "Verify an image's OCI labels agree with the Overview (or -Plocal=true, the image itself)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "io.bitken.ss.docker.ValidateLabels"
    workingDir = moduleDir
    val image = providers.gradleProperty("image").getOrElse("$dockerRepo:latest")
    val localMode = providers.gradleProperty("local").getOrElse("false") == "true"
    args(image, if (localMode) "--local" else "")
}
