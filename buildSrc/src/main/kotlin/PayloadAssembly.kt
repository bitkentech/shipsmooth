import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * One payload producer for a dev co-deposit assembly: a task plus how its declared
 * outputs should be attributed to the overlap-check.
 *
 * - [ownsFilesOnly] = true for `Copy` tasks: `into()` auto-registers the destination
 *   DIR as an output alongside any explicit `outputs.file(..)`, and a dir entry would
 *   let the producer "own" any stray dropped under it (defeating overlap + stray
 *   detection). So only the file entries are passed to the check.
 * - = false for the render, which legitimately owns whole subtrees (`skills/`,
 *   `hooks/`) and must pass its declared dirs too.
 */
class PayloadProducer(
    val name: String,
    val task: TaskProvider<out Task>,
    val ownsFilesOnly: Boolean,
)

/**
 * Reusable dev co-deposit payload assembly (plan-71, shared across integrations so
 * the Claude and Gemini build scripts each wire their own variant without referencing
 * each other). Registers:
 *   - `verify<Name>` (a [VerifyNoOverlappingOutputs]) over the [producers], and
 *   - `<assembleTaskName>` — a lifecycle task that depends on the verify (which in turn
 *     depends on every producer), so running it builds + checks the whole payload.
 *
 * The producers co-deposit straight into [payloadDir]; the verify enforces
 * one-writer-per-file. Returns the assemble task provider.
 */
fun Project.registerPayloadAssembly(
    assembleTaskName: String,
    description: String,
    payloadDir: File,
    producers: List<PayloadProducer>,
): TaskProvider<Task> {
    val verify = tasks.register("verify${assembleTaskName.replaceFirstChar { it.uppercase() }}", VerifyNoOverlappingOutputs::class.java) {
        group = "assemble"
        this.description = "Enforce one-writer-per-file across the $assembleTaskName payload producers."
        this.payloadDir.set(payloadDir)
        producers.forEach { p ->
            dependsOn(p.task)
            val declared: FileCollection =
                if (p.ownsFilesOnly) files(p.task.map { t -> t.outputs.files.filter { it.isFile } })
                else p.task.get().outputs.files
            addProducer(p.name, declared)
        }
    }
    return tasks.register(assembleTaskName) {
        group = "assemble"
        this.description = description
        dependsOn(verify)
    }
}
