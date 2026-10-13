import java.io.File

/**
 * The shipsmooth-beta release version (plan-115): the `[workspace.package] version`
 * of the Rust workspace at exp/rust/Cargo.toml. It is the single source of truth for
 * the beta line — it names the binary's `--version`, the plugin manifest version, the
 * rendered cache path, the `v<version>` tag and the asset filenames — and is an
 * independent series that does not track gradle.properties `plugin.version`.
 */
object CargoVersion {
    private val WORKSPACE_VERSION = Regex("""(?s)\[workspace\.package\].*?\nversion\s*=\s*"([^"]+)"""")

    fun workspace(cargoToml: File): String =
        WORKSPACE_VERSION.find(cargoToml.readText())?.groupValues?.get(1)
            ?: error("no [workspace.package] version in $cargoToml")
}
