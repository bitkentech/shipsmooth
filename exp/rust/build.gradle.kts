// Opt-in convenience wrappers around cargo for the experimental Rust port.
// Deliberately NOT attached to build/check/assemble: the default Gradle build
// must stay green on machines without a Rust toolchain (exp/README.md).

import java.io.File

/**
 * The Rust toolchain this machine offers, resolved once at configuration time.
 * Lookup order for cargo: explicit home dir (-Pcargo.home / $CARGO_HOME), then
 * PATH. An absent toolchain is a valid state — tasks skip instead of failing.
 * CARGO_HOME/RUSTUP_HOME are exported to the exec'd cargo only when explicitly
 * known; a PATH-found cargo must not have a home dir guessed from its location.
 */
class RustToolchain(cargoHomeDir: File?, rustupHomeDir: File?, pathEntries: List<File>) {
    private val cargoHome: File?
    private val rustupHome: File?
    private val cargo: File?

    init {
        cargoHome = cargoHomeDir?.takeIf { File(it, "bin/cargo").canExecute() }
        rustupHome = rustupHomeDir
        cargo = cargoHome?.let { File(it, "bin/cargo") }
            ?: pathEntries.map { File(it, "cargo") }.firstOrNull { it.canExecute() }
    }

    fun found(): Boolean = cargo != null

    fun command(args: List<String>): List<String> = listOf(cargo!!.absolutePath) + args

    fun environment(): Map<String, String> = buildMap {
        cargoHome?.let { put("CARGO_HOME", it.absolutePath) }
        rustupHome?.let { put("RUSTUP_HOME", it.absolutePath) }
    }
}

val toolchain = RustToolchain(
    cargoHomeDir = (findProperty("cargo.home") as String? ?: System.getenv("CARGO_HOME"))?.let(::File),
    rustupHomeDir = (findProperty("rustup.home") as String? ?: System.getenv("RUSTUP_HOME"))?.let(::File),
    pathEntries = System.getenv("PATH").orEmpty().split(File.pathSeparator).map(::File),
)

fun cargoTask(name: String, vararg cargoArgs: String) =
    tasks.register<Exec>(name) {
        group = "rust (experimental)"
        description = "Runs `cargo ${cargoArgs.joinToString(" ")}` in exp/rust (skips when no Rust toolchain is found)"
        workingDir = projectDir
        onlyIf {
            if (!toolchain.found()) {
                logger.lifecycle(
                    "Skipping $name: cargo not found (set -Pcargo.home=<dir>, \$CARGO_HOME, or PATH)"
                )
            }
            toolchain.found()
        }
        if (toolchain.found()) {
            commandLine(toolchain.command(cargoArgs.toList()))
            environment(toolchain.environment())
        }
    }

cargoTask("cargoBuild", "build")
cargoTask("cargoTest", "test")
cargoTask("cargoClean", "clean")

// ---- shipsmooth-beta release binaries (plan-115) ----------------------------------
//
// One `release-small` binary per POSIX release platform, cross-compiled from any host
// with zig as the linker (via `cargo-zigbuild zig cc`, which filters rustc's link args
// into a form zig accepts). We call our own per-target linker wrapper instead of
// `cargo zigbuild` because cargo-zigbuild passes an UNVERSIONED zig target for macOS
// (`aarch64-macos-none`), which zig 0.17 stamps with minos=15.0 — the binary would
// refuse to launch on macOS 14 and older. The versioned target pins minos=11.0.
//
// Needs: cargo-zigbuild (next to cargo, or on PATH), zig (cargo-zigbuild finds it via
// $CARGO_ZIGBUILD_ZIG_COMMAND, `python3 -m ziglang`, or `zig` on PATH), and the three
// rustup targets. Skips (never fails) when cargo or cargo-zigbuild is absent, so a
// machine without the cross toolchain keeps a green build.

/** A shipsmooth-beta release platform: installer platform id, rustc target, zig target. */
data class BetaPlatform(val id: String, val rustTarget: String, val zigTarget: String) {
    val taskSuffix: String = id.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }
    val linkerEnv: String = "CARGO_TARGET_${rustTarget.uppercase().replace('-', '_')}_LINKER"
}

val betaPlatforms = listOf(
    // musl: a fully static binary, independent of the host glibc.
    BetaPlatform("linux-x64", "x86_64-unknown-linux-musl", "x86_64-linux-musl"),
    BetaPlatform("darwin-x64", "x86_64-apple-darwin", "x86_64-macos.11.0-none"),
    BetaPlatform("darwin-arm64", "aarch64-apple-darwin", "aarch64-macos.11.0-none"),
)

val cargoZigbuild: File? = (findProperty("cargo.home") as String? ?: System.getenv("CARGO_HOME"))
    ?.let { File(it, "bin/cargo-zigbuild") }?.takeIf { it.canExecute() }
    ?: System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .map { File(it, "cargo-zigbuild") }.firstOrNull { it.canExecute() }

val betaBinDir = layout.buildDirectory.dir("beta-bin")

val betaBinaryTasks = betaPlatforms.map { platform ->
    tasks.register<Exec>("betaBinary${platform.taskSuffix}") {
        group = "rust (experimental)"
        description = "Cross-compiles the shipsmooth-beta binary for ${platform.id} (${platform.rustTarget})."
        workingDir = projectDir
        onlyIf {
            val ready = toolchain.found() && cargoZigbuild != null
            if (!ready) logger.lifecycle("Skipping $name: needs cargo and cargo-zigbuild")
            ready
        }
        val linker = layout.buildDirectory.file("zig-linkers/${platform.rustTarget}.sh").get().asFile
        val built = File(projectDir, "target/${platform.rustTarget}/release-small/shipsmooth-beta")
        val staged = betaBinDir.get().file("${platform.id}/shipsmooth-beta").asFile
        outputs.file(staged)
        outputs.upToDateWhen { false } // cargo owns incrementality
        doFirst {
            linker.parentFile.mkdirs()
            linker.writeText(
                "#!/bin/sh\nexec \"${cargoZigbuild!!.absolutePath}\" zig cc -- -g -fno-sanitize=all " +
                    "-target ${platform.zigTarget} \"\$@\"\n"
            )
            linker.setExecutable(true)
        }
        if (toolchain.found()) {
            commandLine(toolchain.command(listOf(
                "build", "-p", "ss-cli", "--profile", "release-small", "--target", platform.rustTarget,
            )))
            environment(toolchain.environment())
            environment(platform.linkerEnv, linker.absolutePath)
        }
        doLast {
            staged.parentFile.mkdirs()
            built.copyTo(staged, overwrite = true)
            staged.setExecutable(true, false)
        }
    }
}

tasks.register("betaBinaries") {
    group = "rust (experimental)"
    description = "Cross-compiles the shipsmooth-beta binary for every POSIX release platform."
    dependsOn(betaBinaryTasks)
}
