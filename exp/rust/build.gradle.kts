// Opt-in convenience wrappers around cargo for the experimental Rust port.
// Deliberately NOT attached to build/check/assemble: the default Gradle build
// must stay green on machines without a Rust toolchain (exp/README.md).
//
// Cargo lookup order: -Pcargo.home=<dir> → $CARGO_HOME → PATH. When rustup
// manages the toolchain, its shims need RUSTUP_HOME; pass -Prustup.home=<dir>
// (or have it exported) so the exec'd cargo can resolve the toolchain.

import java.io.File

val cargoExe: String? by lazy {
    val fromProperty = (findProperty("cargo.home") as String?)?.let { File(it, "bin/cargo") }
    val fromEnv = System.getenv("CARGO_HOME")?.let { File(it, "bin/cargo") }
    val fromPath = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .map { File(it, "cargo") }
    (listOfNotNull(fromProperty, fromEnv) + fromPath)
        .firstOrNull { it.canExecute() }
        ?.absolutePath
}

val rustupHome: String? =
    (findProperty("rustup.home") as String?) ?: System.getenv("RUSTUP_HOME")

fun registerCargoTask(name: String, vararg cargoArgs: String) =
    tasks.register<Exec>(name) {
        group = "rust (experimental)"
        description = "Runs `cargo ${cargoArgs.joinToString(" ")}` in exp/rust (skips if cargo is absent)"
        workingDir = projectDir
        onlyIf {
            if (cargoExe == null) {
                logger.lifecycle(
                    "Skipping $name: cargo not found (set -Pcargo.home=<dir>, \$CARGO_HOME, or PATH)"
                )
            }
            cargoExe != null
        }
        doFirst {
            commandLine(listOf(cargoExe!!) + cargoArgs)
            rustupHome?.let { environment("RUSTUP_HOME", it) }
            environment("CARGO_HOME", File(cargoExe!!).parentFile.parent)
        }
    }

registerCargoTask("cargoBuild", "build")
registerCargoTask("cargoTest", "test")
registerCargoTask("cargoClean", "clean")
