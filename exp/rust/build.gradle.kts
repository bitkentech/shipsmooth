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
        // A dedicated target dir: cargo does not relink when only the linker env
        // changes, so sharing target/ with a plain `cargo zigbuild` could stage a
        // stale binary linked with the wrong (minos 15.0) deployment target.
        val targetDir = File(projectDir, "target/beta")
        val built = File(targetDir, "${platform.rustTarget}/release-small/shipsmooth-beta")
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
                "--target-dir", targetDir.absolutePath,
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

// The workspace version: the shipsmooth-beta release version (exp/rust/Cargo.toml).
val cargoWorkspaceVersion: String = Regex("""(?s)\[workspace\.package\].*?\nversion\s*=\s*"([^"]+)"""")
    .find(file("Cargo.toml").readText())?.groupValues?.get(1)
    ?: error("no [workspace.package] version in exp/rust/Cargo.toml")

/**
 * Load-command facts of a 64-bit little-endian Mach-O executable: the LC_BUILD_VERSION
 * minimum OS as (major, minor), and whether an LC_CODE_SIGNATURE is present.
 */
data class MachOFacts(val minOs: Pair<Int, Int>?, val signed: Boolean)

fun machOFacts(bin: File): MachOFacts {
    val buf = java.nio.ByteBuffer.wrap(bin.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    require(buf.getInt(0) == 0xfeedfacf.toInt()) { "$bin is not a 64-bit Mach-O executable" }
    var minOs: Pair<Int, Int>? = null
    var signed = false
    var offset = 32 // sizeof(mach_header_64)
    repeat(buf.getInt(16)) { // ncmds
        val cmd = buf.getInt(offset)
        when (cmd) {
            0x32 -> buf.getInt(offset + 12).let { minOs = (it ushr 16) to ((it ushr 8) and 0xff) } // LC_BUILD_VERSION
            0x1d -> signed = true // LC_CODE_SIGNATURE
        }
        offset += buf.getInt(offset + 4)
    }
    return MachOFacts(minOs, signed)
}

// Artifact check for the cross-compiled binaries (05 §Risks: verify each binary before
// the release that advertises it). It cannot execute the darwin binaries on Linux, so it
// asserts the launch-critical facts instead: minimum macOS <= 11.0 on both (the
// cargo-zigbuild default of 15.0 regression), and an ad-hoc signature on arm64 (Apple
// Silicon kills unsigned binaries). The linux binary runs here and must print the version.
// -Pbeta.bin.dir=<dir> checks another <platform>/shipsmooth-beta tree (e.g. downloaded assets).
tasks.register("verifyBetaBinaries") {
    group = "rust (experimental)"
    description = "Checks the shipsmooth-beta binaries: linux --version, darwin minimum macOS and signature."
    val binDir = (findProperty("beta.bin.dir") as String?)?.let(::File) ?: betaBinDir.get().asFile
    if (findProperty("beta.bin.dir") == null) mustRunAfter(betaBinaryTasks)
    doLast {
        fun bin(platform: String) = File(binDir, "$platform/shipsmooth-beta").also {
            check(it.canExecute()) { "missing or not executable: $it" }
        }
        val linux = bin("linux-x64")
        if (System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
            val proc = ProcessBuilder(linux.absolutePath, "--version").redirectErrorStream(true).start()
            val printed = proc.inputStream.bufferedReader().readText().trim()
            check(proc.waitFor() == 0 && printed == cargoWorkspaceVersion) {
                "linux-x64 --version printed '$printed', expected '$cargoWorkspaceVersion'"
            }
        } else {
            logger.lifecycle("verifyBetaBinaries: not on linux-x64, linux binary not executed")
        }
        for ((platform, mustBeSigned) in listOf("darwin-x64" to false, "darwin-arm64" to true)) {
            val facts = machOFacts(bin(platform))
            val (major, minor) = checkNotNull(facts.minOs) { "$platform: no LC_BUILD_VERSION" }
            check(major < 11 || (major == 11 && minor == 0)) {
                "$platform: minimum macOS is $major.$minor, must be <= 11.0"
            }
            check(!mustBeSigned || facts.signed) { "$platform: missing LC_CODE_SIGNATURE" }
        }
        logger.lifecycle("verifyBetaBinaries: OK ($cargoWorkspaceVersion, $binDir)")
    }
}
