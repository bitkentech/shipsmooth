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
