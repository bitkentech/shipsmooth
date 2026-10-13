import java.io.File

/**
 * The shipsmooth-beta product identity (plan-115): the Rust CLI shipped as its own
 * plugin, alongside — never instead of — the Java `shipsmooth`. Shared by every build
 * script that renders or assembles the beta, so its name and pre-release framing are
 * defined once.
 */
object BetaProduct {
    const val NAME = "shipsmooth-beta"

    /** Prefixed to the prod description so the beta reads as pre-release on sight (05 §Goal 2). */
    fun description(prodDescription: String) =
        "[BETA] Rust CLI preview of shipsmooth — expect rough edges. $prodDescription"
}

/**
 * The shipsmooth-beta release version: the `[workspace.package] version` of the Rust
 * workspace at exp/rust/Cargo.toml. It is the single source of truth for the beta line
 * — it names the binary's `--version`, the plugin manifest version, the rendered cache
 * path, the `v<version>` tag and the asset filenames — and is an independent series
 * that does not track gradle.properties `plugin.version`.
 */
object CargoVersion {
    private val WORKSPACE_VERSION = Regex("""(?s)\[workspace\.package\].*?\nversion\s*=\s*"([^"]+)"""")

    fun workspace(cargoToml: File): String =
        WORKSPACE_VERSION.find(cargoToml.readText())?.groupValues?.get(1)
            ?: error("no [workspace.package] version in $cargoToml")
}
