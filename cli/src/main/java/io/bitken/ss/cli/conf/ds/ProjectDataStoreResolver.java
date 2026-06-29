package io.bitken.ss.cli.conf.ds;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import io.bitken.ss.cli.conf.ConfigFileLocator;
import io.bitken.ss.cli.conf.DefaultConfigFileLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Resolves where a project's shipsmooth state lives, returning a {@link DataStoreResolution}
 * that classifies the situation per the plan-85 branch table.
 *
 * <p>Detection only: {@code resolve()} reads the config file (located by a
 * {@link ConfigFileLocator}) and the filesystem, then classifies. It never creates,
 * moves, or git-inits anything, and never prompts on stdin — acting on a
 * {@link DataStoreResolution.NeedsDecision} is a separate, deferred concern. Matching is
 * by the pair {@code (localPath, remoteUrl)}.
 */
public final class ProjectDataStoreResolver {

    /** Tool-owned in-repo data folder; its {@code plans/} subtree marks settled in-repo state. */
    private static final String DATA_DIR = ".shipsmooth";
    private static final String PLANS_SUBDIR = "plans";

    private final TomlMapper toml;
    private final ConfigFileLocator configFileLocator;

    public ProjectDataStoreResolver() {
        this(new DefaultConfigFileLocator());
    }

    /** Inject a specific config-file locator (used by {@code store init} wiring and tests). */
    public ProjectDataStoreResolver(ConfigFileLocator configFileLocator) {
        this.configFileLocator = configFileLocator;
        toml = new TomlMapper();
    }

    /**
     * @param localPath canonical repo root ({@code git rev-parse --show-toplevel})
     * @param remoteUrl origin URL if present
     * @return a {@link DataStoreResolution}: {@code Settled} when the location is known,
     *         {@code NeedsDecision} when the user must choose, or {@code Unresolvable} when
     *         the user must fix it by hand. Never throws for these cases; an unexpected
     *         checked failure is returned as {@code Unresolvable(UNKNOWN, cause)}.
     */
    public DataStoreResolution resolve(Path localPath, Optional<String> remoteUrl) {
        try {
            return classify(localPath, remoteUrl);
        } catch (IOException e) {
            return DataStoreResolution.Unresolvable.unknown(e);
        }
    }

    private DataStoreResolution classify(Path localPath, Optional<String> remoteUrl) throws IOException {
        Path configFile = configFileLocator.locate();
        Optional<StandaloneConfig.ProjectEntry> match = Files.exists(configFile)
                ? parseConfig(configFile).flatMap(c -> matchingEntry(c, localPath, remoteUrl))
                : Optional.empty();

        if (match.isPresent()) {
            return fromConfigEntry(localPath, match.get());
        }
        return fromFilesystem(localPath, remoteUrl);
    }

    private static final String STORAGE_EMBEDDED = "same-repo";
    private static final String STORAGE_FILESYSTEM = "separate-dir";

    /**
     * A config entry matched this project. Classify per its {@code storageType}/{@code
     * storageRoot}: a valid same-repo entry resolves in-repo; a valid separate-dir entry
     * resolves to its root (recreate decision if missing); anything inconsistent is a
     * malformed entry.
     */
    private DataStoreResolution fromConfigEntry(Path localPath, StandaloneConfig.ProjectEntry entry) {
        boolean hasStorageRoot = entry.getStorageRoot() != null && !entry.getStorageRoot().isBlank();
        String storageType = entry.getStorageType() == null ? null : entry.getStorageType().trim();

        if (STORAGE_EMBEDDED.equals(storageType)) {
            // Embedded entries must NOT also carry a storageRoot.
            return hasStorageRoot ? malformed() : fromInRepoEntry(localPath);
        }
        if (STORAGE_FILESYSTEM.equals(storageType)) {
            // separate-dir storage: a storageRoot is required.
            return hasStorageRoot ? fromExternalEntry(localPath, entry) : malformed();
        }
        // Missing or unknown storageType value.
        return malformed();
    }

    /** Valid separate-dir entry: settled when the root exists, else offer to recreate it. */
    private DataStoreResolution fromExternalEntry(Path localPath, StandaloneConfig.ProjectEntry entry) {
        Path storageRoot = Path.of(entry.getStorageRoot()).toAbsolutePath().normalize();
        if (Files.isDirectory(storageRoot)) {
            // Config wins over any in-repo folder: we do not even inspect the repo here.
            return new DataStoreResolution.Settled(new ProjectDataStore.Standalone(localPath, storageRoot));
        }
        return new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CONFIG_DIR_MISSING,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.RECREATE_MISSING_DIR, storageRoot, true)));
    }

    /**
     * Valid in-repo entry: settled only once the in-repo folder is actually set up. The
     * entry records the choice, but settled-ness still requires the on-disk folder, so an
     * unprovisioned repo is offered the in-repo setup rather than re-asked from scratch.
     */
    private DataStoreResolution fromInRepoEntry(Path localPath) {
        if (Files.isDirectory(localPath.resolve(DATA_DIR).resolve(PLANS_SUBDIR))) {
            return new DataStoreResolution.Settled(new ProjectDataStore.InRepo(localPath));
        }
        return new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.IN_REPO_NOT_SET_UP,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.IN_REPO, localPath.resolve(DATA_DIR), true)));
    }

    private static DataStoreResolution malformed() {
        return DataStoreResolution.Unresolvable.of(
                DataStoreResolution.UnresolvableReason.MALFORMED_CONFIG_ENTRY);
    }

    /** No matching config entry: legacy guard, settled in-repo, or a clean first run. */
    private DataStoreResolution fromFilesystem(Path localPath, Optional<String> remoteUrl) {
        if (LegacyDataTreeGuard.isLegacyDataTree(localPath)) {
            return DataStoreResolution.Unresolvable.of(
                    DataStoreResolution.UnresolvableReason.LEGACY_AGENTS_TREE);
        }
        if (Files.isDirectory(localPath.resolve(DATA_DIR).resolve(PLANS_SUBDIR))) {
            return new DataStoreResolution.Settled(new ProjectDataStore.InRepo(localPath));
        }
        return cleanFirstRun(localPath, remoteUrl);
    }

    /** Nothing configured and no state anywhere: offer external (recommended) or in-repo. */
    private DataStoreResolution cleanFirstRun(Path localPath, Optional<String> remoteUrl) {
        Path external = proposedExternalPath(localPath);
        Path inRepo = localPath.resolve(DATA_DIR);
        return new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CLEAN_FIRST_RUN,
                List.of(
                        new DataStoreResolution.Option(DataStoreResolution.Choice.EXTERNAL, external, true),
                        new DataStoreResolution.Option(DataStoreResolution.Choice.IN_REPO, inRepo, false)));
    }

    /**
     * The external state path the CLI proposes (user may override). Recorded verbatim on
     * accept — never hash-derived. {@code XDG_STATE_HOME} else {@code ~/.local/state}, then
     * {@code shipsmooth/<repo-dir-name>}.
     */
    /**
     * Propose the external state location as a <em>sibling</em> of the project repo —
     * {@code <parent>/<repo>-shipsmooth}. This is deliberately next to the repo, not hidden
     * under {@code ~/.local/state}: the external dir is the user's project content (plan
     * narratives, task history, its own git repo) which they may push to a remote — not
     * ephemeral local-install state — so it must be discoverable.
     */
    private static Path proposedExternalPath(Path localPath) {
        Path repo = localPath.toAbsolutePath().normalize();
        Path name = repo.getFileName();
        String repoName = (name != null) ? name.toString() : "project";
        return repo.resolveSibling(repoName + "-shipsmooth");
    }

    /**
     * Read the config file, tolerating an unusable one. An empty or unparseable file
     * resolves as {@link Optional#empty()} — "no usable config" — so resolution falls
     * through to the filesystem rather than wedging on {@code Unresolvable(UNKNOWN)}. This
     * matters because a failed {@code store init} write can leave a 0-byte config behind
     * (plan-87); a stray or truncated global config must never poison an otherwise-valid
     * project. A parse that yields {@code null} (empty TOML) is likewise treated as absent.
     */
    private Optional<StandaloneConfig> parseConfig(Path configFile) {
        try {
            return Optional.ofNullable(toml.readValue(configFile.toFile(), StandaloneConfig.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<StandaloneConfig.ProjectEntry> matchingEntry(
            StandaloneConfig config, Path localPath, Optional<String> remoteUrl) {
        String localPathStr = localPath.toAbsolutePath().normalize().toString();
        for (StandaloneConfig.ProjectEntry entry : config.getProjects()) {
            if (!localPathStr.equals(normalisePath(entry.getLocalPath()))) {
                continue;
            }
            if (remoteUrl.isPresent() && entry.getRemoteUrl() != null
                    && !remoteUrl.get().equals(entry.getRemoteUrl())) {
                continue;
            }
            return Optional.of(entry);
        }
        return Optional.empty();
    }

    private static String normalisePath(String raw) {
        if (raw == null) return "";
        return Path.of(raw).toAbsolutePath().normalize().toString();
    }
}