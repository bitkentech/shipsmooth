import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Sync
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

/**
 * One source dir for a prod Sync assembly: a producer task plus the private dir it
 * writes into. The Sync pulls from each [stagingDir]; the producer is wired as a
 * dependency so it has run first.
 */
class SyncSource(
    val task: TaskProvider<out Task>,
    val stagingDir: File,
)

/**
 * Reusable prod payload assembly (plan-71 dual-mode, the release-path counterpart to
 * [registerPayloadAssembly]). Each producer writes into its OWN private [SyncSource.stagingDir];
 * the registered [Sync] task is the SOLE writer of [payloadDir], merging every staging dir into
 * it. Because Sync owns the destination outright it is structurally overlap-immune (and prunes
 * stale files), so the dev co-deposit overlap-check is neither needed nor wired here. The extra
 * copy is acceptable on the rare release path. Returns the Sync task provider.
 */
fun Project.registerPayloadSync(
    syncTaskName: String,
    description: String,
    payloadDir: File,
    sources: List<SyncSource>,
): TaskProvider<Sync> =
    tasks.register(syncTaskName, Sync::class.java) {
        group = "assemble"
        this.description = description
        sources.forEach { src ->
            dependsOn(src.task)
            from(src.stagingDir)
        }
        into(payloadDir)
    }
