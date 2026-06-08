import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider

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
 *
 * `jlinkDir` is a Provider<String>, not a String: the dev variant resolves it
 * lazily from the cli jlink task's output dir (establishing the producer->consumer
 * dependency edge that makes devBuild auto-build the host image). The other
 * variants wrap a constant in a provider for the same shape — systemProperties()
 * therefore returns Provider values uniformly (one shape across all variants), and
 * every value is resolved at task-execution time, never at config time.
 *
 * `objects` (ObjectFactory) is injected so the constant properties get their own
 * independent providers (see constant()), rather than being derived from jlinkDir —
 * a constant like build.platform must not carry a task dependency on the jlink image.
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
    val jlinkDir: Provider<String>,
    val pluginRepoName: String,
    val outputDir: String,
    val experimentalEnabled: Boolean,
    val pluginHookCommand: String,
    val objects: ObjectFactory,
) {
    /**
     * The -D system properties to hand io.bitken.ss.resources.Target, each as a
     * Provider<String> so the render task can resolve them lazily (one uniform
     * shape — the load-bearing one is shipsmooth.jlink.dir, wired to the cli image
     * task; the rest are constants wrapped in their own independent providers).
     */
    fun systemProperties(): Map<String, Provider<String>> = mapOf(
        "build.platform" to constant(buildPlatform),
        "build.os" to constant(buildOs),
        "build.env" to constant(buildEnv),
        "plugin.base.name" to constant(pluginBaseName),
        "plugin.version" to constant(pluginVersion),
        "plugin.description" to constant(pluginDescription),
        "plugin.skill.start.basename" to constant(pluginSkillStartBasename),
        "skill.frontmatter" to constant(skillFrontmatter),
        "shipsmooth.jlink.dir" to jlinkDir,
        "plugin.repo.name" to constant(pluginRepoName),
        "build.outputDir" to constant(outputDir),
        "experimental.enabled" to constant(experimentalEnabled.toString()),
        "plugin.hook.command" to constant(pluginHookCommand),
    )

    /**
     * Wrap a constant in its own Provider so every systemProperties() value has the
     * same type as the lazily-wired jlinkDir, WITHOUT coupling to it. An injected
     * ObjectFactory property is independent of jlinkDir — so resolving a constant
     * (e.g. build.platform) never forces the jlink image build, unlike a
     * jlinkDir.map { } derivation would.
     */
    private fun constant(value: String): Provider<String> =
        objects.property(String::class.java).convention(value)
}
