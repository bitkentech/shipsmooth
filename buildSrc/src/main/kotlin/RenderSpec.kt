/**
 * The full set of system properties consumed by io.bitken.ss.resources.Target
 * (and Os.java's plugin.hook.command). Modelling the variant tuple as a single
 * value object is the guard against render-variable drift between targets — the
 * #1 correctness risk called out in docs/proposals/build-migrate.md when the
 * Maven profile matrix is replaced by per-variant Gradle tasks.
 *
 * One RenderSpec == one render target (claude-dev, gemini-dev, ...). Both tasks
 * share this definition, so a variable can never be set for one target and
 * forgotten for the other.
 */
data class RenderSpec(
    val buildPlatform: String,
    val buildOs: String,
    val buildEnv: String,
    val pluginBaseName: String,
    val pluginVersion: String,
    val pluginDescription: String,
    val pluginSkillStartBasename: String,
    val skillFrontmatter: String,
    val jlinkDir: String,
    val pluginRepoName: String,
    val outputDir: String,
    val experimentalEnabled: Boolean,
    val pluginHookCommand: String,
) {
    /** The -D system properties to hand io.bitken.ss.resources.Target. */
    fun systemProperties(): Map<String, String> = mapOf(
        "build.platform" to buildPlatform,
        "build.os" to buildOs,
        "build.env" to buildEnv,
        "plugin.base.name" to pluginBaseName,
        "plugin.version" to pluginVersion,
        "plugin.description" to pluginDescription,
        "plugin.skill.start.basename" to pluginSkillStartBasename,
        "skill.frontmatter" to skillFrontmatter,
        "shipsmooth.jlink.dir" to jlinkDir,
        "plugin.repo.name" to pluginRepoName,
        "build.outputDir" to outputDir,
        "experimental.enabled" to experimentalEnabled.toString(),
        "plugin.hook.command" to pluginHookCommand,
    )
}
