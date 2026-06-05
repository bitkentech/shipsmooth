// Gemini integration: filter gemini-extension.json into build-gemini/ and copy
// the commands/ tree + README, replacing the Maven maven-resources-plugin
// filtering with Copy + expand(). No Java in this module (parity with gemini/pom.xml).

val pluginBaseName = (findProperty("plugin.base.name") as String?) ?: "shipsmooth"
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.14"
// Variant tuples mirror the Maven gemini (prod) / gemini-dev profiles.
// -Pvariant=prod for prod; default dev.
val variant = (findProperty("variant") as String?) ?: "dev"
val prodDescription = "Agent coding workflow with plan-before-implement discipline, " +
    "TDD, vertical slices, Linear integration, and immutable git-based plan versioning."
val tokens = when (variant) {
    "prod" -> mapOf(
        "plugin" to mapOf("name" to pluginBaseName, "description" to prodDescription),
        "project" to mapOf("version" to pluginVersion),
    )
    else -> mapOf(
        "plugin" to mapOf("name" to "$pluginBaseName-dev", "description" to "Agent coding workflow (dev build)"),
        "project" to mapOf("version" to pluginVersion),
    )
}

val outputDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: rootProject.layout.projectDirectory.dir(if (variant == "prod") "build-gemini" else "build-gemini-dev").asFile

val resourcesDir = layout.projectDirectory.dir("src/main/resources/gemini-extension")
// gemini-dev uses the gemini-dev/commands tree; prod (gemini) uses gemini/commands.
val commandsVariant = if (variant == "prod") "gemini" else "gemini-dev"

// Filter the manifest (the only file with tokens).
val copyGeminiManifest by tasks.registering(Copy::class) {
    from(resourcesDir) { include("gemini-extension.json") }
    into(outputDir)
    expand(tokens)
}

// Copy the variant's commands/ tree (unfiltered) into build-gemini*/commands/.
val copyGeminiCommands by tasks.registering(Copy::class) {
    from(resourcesDir.dir("$commandsVariant/commands"))
    into(File(outputDir, "commands"))
}

// Copy README (unfiltered).
val copyGeminiReadme by tasks.registering(Copy::class) {
    from(resourcesDir) { include("README.md") }
    into(outputDir)
}

tasks.register("assembleGemini") {
    dependsOn(copyGeminiManifest, copyGeminiCommands, copyGeminiReadme)
}
