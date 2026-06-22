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

    /** Test seam — supply a locator that points at a fixed config file path. */
    ProjectDataStoreResolver(ConfigFileLocator configFileLocator) {
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
                ? matchingEntry(parseConfig(configFile), localPath, remoteUrl)
                : Optional.empty();

        if (match.isPresent()) {
            return fromConfigEntry(localPath, match.get());
        }
        return fromFilesystem(localPath, remoteUrl);
    }

    /** Config entry matched this project: external state (row 2/3) or malformed (Unresolvable). */
    private DataStoreResolution fromConfigEntry(Path localPath, StandaloneConfig.ProjectEntry entry) {
        if (entry.getStateDir() == null || entry.getStateDir().isBlank()) {
            return DataStoreResolution.Unresolvable.of(
                    DataStoreResolution.UnresolvableReason.MALFORMED_CONFIG_ENTRY);
        }
        Path stateDir = Path.of(entry.getStateDir()).toAbsolutePath().normalize();
        if (Files.isDirectory(stateDir)) {
            // Config wins over any in-repo folder: we do not even inspect the repo here.
            return new DataStoreResolution.Settled(new ProjectDataStore.Standalone(localPath, stateDir));
        }
        // Configured external dir is gone — ask whether to recreate it.
        return new DataStoreResolution.NeedsDecision(
                DataStoreResolution.UndecidableSituation.CONFIG_DIR_MISSING,
                List.of(new DataStoreResolution.Option(
                        DataStoreResolution.Choice.RECREATE_MISSING_DIR, stateDir, true)));
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
    private static Path proposedExternalPath(Path localPath) {
        String xdgState = System.getenv("XDG_STATE_HOME");
        Path stateHome = (xdgState != null && !xdgState.isBlank())
                ? Path.of(xdgState)
                : Path.of(System.getProperty("user.home"), ".local", "state");
        Path name = localPath.toAbsolutePath().normalize().getFileName();
        String repoName = (name != null) ? name.toString() : "project";
        return stateHome.resolve("shipsmooth").resolve(repoName).toAbsolutePath().normalize();
    }

    private StandaloneConfig parseConfig(Path configFile) throws IOException {
        return toml.readValue(configFile.toFile(), StandaloneConfig.class);
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