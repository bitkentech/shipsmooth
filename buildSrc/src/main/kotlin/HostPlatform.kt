/**
 * Detects the build host's platform tag (e.g. "linux-x64", "darwin-arm64").
 *
 * The tag strings MUST match detectPlatform() in
 * skills/pkg/scripts/tasks/session-start.ts, because session-start's installRuntime
 * copies the dev jlink image into a runtime dir keyed by this same tag. session-start
 * reads Node's already-normalised process.platform/process.arch; here we read the JVM's
 * os.name/os.arch, which use different spellings — so this is the JVM->tag bridge:
 *   arch  amd64 / x86_64 -> x64 ,  aarch64 / arm64 -> arm64
 *   os    linux -> linux ,  mac -> darwin ,  windows -> win32
 *
 * Rationale for System.getProperty over Gradle's org.gradle.platform.BuildPlatform:
 * the public BuildPlatform API cannot detect the host — BuildPlatformFactory.of(arch, os)
 * only wraps an os/arch you already determined; the actual host detector
 * (CurrentBuildPlatform) is an .internal. class. So reading the JVM props is the
 * substantive step either path requires, not a shortcut around a cleaner API.
 */
object HostPlatform {
    /** The host platform tag, matching session-start.ts. Errors loudly if unsupported. */
    fun tag(): String {
        val osName = System.getProperty("os.name").lowercase()
        val osArch = System.getProperty("os.arch").lowercase()

        val os = when {
            osName.contains("linux") -> "linux"
            osName.contains("mac") || osName.contains("darwin") -> "darwin"
            osName.contains("windows") -> "win32"
            else -> error("HostPlatform: unsupported os.name '$osName' (need linux, mac, or windows)")
        }
        val arch = when (osArch) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> error("HostPlatform: unsupported os.arch '$osArch' (need amd64/x86_64 or aarch64/arm64)")
        }
        return "$os-$arch"
    }
}
