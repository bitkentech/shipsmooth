// Claude integration: filter the .claude-plugin manifests (plugin.json,
// marketplace.json) into build/.claude-plugin/, replacing the Maven
// maven-resources-plugin copy-resources filtering with Copy + expand().
//
// No java-conventions plugin here — this module has no Java, only resource
// filtering (parity with claude/pom.xml, which is build-only).

val pluginBaseName = (findProperty("plugin.base.name") as String?) ?: "shipsmooth"
val pluginVersion = (findProperty("plugin.version") as String?) ?: "0.3.14"
// Variant tuples mirror the Maven dev/prod profiles. -Pvariant=prod for prod;
// default dev (the activeByDefault Maven profile).
val variant = (findProperty("variant") as String?) ?: "dev"
val prodDescription = "Agent coding workflow with plan-before-implement discipline, " +
    "TDD, vertical slices, Linear integration, and immutable git-based plan versioning."
val tokens = when (variant) {
    "prod" -> mapOf(
        "plugin" to mapOf("name" to pluginBaseName, "description" to prodDescription),
        "project" to mapOf("version" to pluginVersion),
        "marketplace" to mapOf(
            "name" to "bitkentech",
            "description" to "Plugin marketplace for bitkentech/shipsmooth",
        ),
    )
    else -> mapOf(
        "plugin" to mapOf("name" to "$pluginBaseName-dev", "description" to "Agent coding workflow (dev build)"),
        "project" to mapOf("version" to pluginVersion),
        "marketplace" to mapOf(
            "name" to "shipsmooth-dev",
            "description" to "Development marketplace for bitkentech/shipsmooth plugin",
        ),
    )
}

val outputDir = (findProperty("build.outputDir") as String?)
    ?.let { file(it) }
    ?: rootProject.layout.projectDirectory.dir("build").asFile

// Filter plugin.json + marketplace.json into build/.claude-plugin/.
val copyPluginMeta by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("src/main/resources/claude-plugin"))
    into(File(outputDir, ".claude-plugin"))
    expand(tokens)
}

tasks.register("assembleClaude") {
    dependsOn(copyPluginMeta)
}
