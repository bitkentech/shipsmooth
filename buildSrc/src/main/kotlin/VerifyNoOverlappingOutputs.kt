import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Enforces "one payload file, one producer" for a dev co-deposit assembly, where
 * several producers (render, copyDist, manifests) write straight into one
 * `build/<variant>` dir. Gradle only *warns* on overlapping outputs (and silently
 * disables incremental optimisations), so the invariant would rot by accident;
 * this check makes it a hard failure.
 *
 * Each producer declares what it OWNS — either exact files or whole subtrees. The
 * render owns the `skills/` and `hooks/` subtrees (variant-dependent file sets, so
 * declaring the dir is right) plus the single `dist/session-start-config.json`;
 * copyDist owns the JS files under `dist/`; manifests own the `.claude-plugin`
 * JSON. The only real overlap risk is the shared `dist/` dir (render's config vs
 * copyDist's JS) — owners are disjoint there at file level.
 *
 * The check, over the payload-extension allowlist [PAYLOAD_EXTENSIONS] only
 * (skills/claude/gemini payload content — never .class/jars/generated sources):
 *   1. Overlap — no payload file is owned by two producers (a file is owned by a
 *      producer if it equals a declared file, or lives under a declared dir).
 *   2. Completeness — every payload file actually present in [payloadDir] is owned
 *      by exactly one producer, catching undeclared strays (Gradle has no Bazel
 *      sandbox enforcing declared==actual).
 *
 * Wire with `dependsOn(producers)` so they have run. (plan-71 Task 21, v14.)
 */
abstract class VerifyNoOverlappingOutputs : DefaultTask() {

    @get:InputFiles
    val declaredOutputs: ConfigurableFileCollection = project.objects.fileCollection()

    @get:org.gradle.api.tasks.InputDirectory
    abstract val payloadDir: DirectoryProperty

    /**
     * Success stamp. A pure assertion task declares no outputs, so Gradle re-runs it
     * every invocation ("not up-to-date: has not declared any outputs"). Writing a
     * trivial stamp gives it an output to track, so an unchanged payload leaves it
     * UP-TO-DATE — zero cost on the hot dev loop. Defaults under the task's build dir.
     */
    @get:org.gradle.api.tasks.OutputFile
    val stamp: org.gradle.api.file.RegularFileProperty =
        project.objects.fileProperty().convention(
            project.layout.buildDirectory.file("payload-checks/$name.ok"),
        )

    /** producer name -> its declared outputs (files and/or dirs it owns). */
    @get:Internal
    val producers: MutableMap<String, ConfigurableFileCollection> = LinkedHashMap()

    /** Register a producer: pass its task's `outputs.files` (files and/or dirs). */
    fun addProducer(name: String, declared: FileCollection) {
        val fc = project.objects.fileCollection().from(declared)
        producers[name] = fc
        declaredOutputs.from(fc)
    }

    private fun File.isPayload(): Boolean =
        isFile && extension.lowercase() in PAYLOAD_EXTENSIONS

    /**
     * The producers that own [file]. An exact declared-file match takes precedence:
     * if any producer declares [file] as a file, only file-matchers own it (declared
     * dirs are ignored for that file). This lets the render own
     * `dist/session-start-config.json` by exact file while copyDist's auto-registered
     * `dist/` dir does NOT also claim it. Otherwise, producers whose declared dir
     * contains [file] own it.
     */
    private fun ownersOf(file: File): List<String> {
        val fileMatchers = producers.entries.filter { (_, owned) ->
            owned.files.any { it.isFile && it.canonicalFile == file }
        }.map { it.key }
        if (fileMatchers.isNotEmpty()) return fileMatchers
        return producers.entries.filter { (_, owned) ->
            owned.files.any { it.isDirectory && file.startsWith(it.canonicalFile) }
        }.map { it.key }
    }

    @TaskAction
    fun verify() {
        val dir = payloadDir.get().asFile
        val payloadFiles = dir.walkTopDown().filter { it.isPayload() }.map { it.canonicalFile }.toList()

        val overlaps = LinkedHashMap<File, List<String>>()
        val strays = mutableListOf<File>()
        payloadFiles.forEach { f ->
            val owners = ownersOf(f)
            when {
                owners.size > 1 -> overlaps[f] = owners
                owners.isEmpty() -> strays += f
            }
        }

        if (overlaps.isNotEmpty()) {
            val detail = overlaps.entries.joinToString("\n") { (f, who) -> "  $f  <-  ${who.joinToString(", ")}" }
            throw GradleException(
                "Overlapping payload outputs — these files are owned by more than one producer, " +
                    "which breaks incremental co-deposit assembly:\n$detail",
            )
        }
        if (strays.isNotEmpty()) {
            val detail = strays.joinToString("\n") { "  $it" }
            throw GradleException(
                "Undeclared payload outputs — these files are in $dir but no producer declares " +
                    "them (declare via outputs.file(..)/outputs.dir(..) on the producing task):\n$detail",
            )
        }

        // All assertions passed — write the success stamp so this task is UP-TO-DATE
        // until an input (a producer output or the payload dir) actually changes.
        val stampFile = stamp.get().asFile
        stampFile.parentFile.mkdirs()
        stampFile.writeText("ok: ${payloadFiles.size} payload files verified\n")
    }

    companion object {
        /** Payload content file types (plan-71 v14). NOT .class/jar/generated sources. */
        val PAYLOAD_EXTENSIONS = setOf("md", "js", "json", "toml", "bat", "ts")
    }
}
