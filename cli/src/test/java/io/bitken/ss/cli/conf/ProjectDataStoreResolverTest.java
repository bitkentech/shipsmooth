package io.bitken.ss.cli.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProjectDataStoreResolverTest {

    @TempDir Path tmp;

    // Inv-1: no config file → in-repo default
    @Test
    void noConfigFile_returnsInRepo() {
        Path absent = tmp.resolve("ss-config.toml");
        var result = new ProjectDataStoreResolver(() -> absent)
                .resolve(tmp, Optional.of("https://github.com/org/repo.git"));
        assertInstanceOf(ProjectDataStore.InRepo.class, result);
    }

    // Match by both localPath and remoteUrl
    @Test
    void matchesByLocalPathAndRemoteUrl() throws IOException {
        Path stateDir = tmp.resolve("state");
        Path config = writeConfig(tmp, """
                [[projects]]
                remoteUrl = "https://github.com/org/repo.git"
                localPath  = "%s"
                stateDir   = "%s"
                """.formatted(tmp, stateDir));

        var result = new ProjectDataStoreResolver(() -> config)
                .resolve(tmp, Optional.of("https://github.com/org/repo.git"));
        assertInstanceOf(ProjectDataStore.Standalone.class, result);
        assertEquals(stateDir.toAbsolutePath().normalize(), result.stateRoot());
    }

    // No entry for this project → in-repo default
    @Test
    void noMatchingEntry_returnsInRepo() throws IOException {
        Path config = writeConfig(tmp, """
                [[projects]]
                remoteUrl = "https://github.com/org/other.git"
                localPath  = "/some/other/path"
                stateDir   = "/state"
                """);

        var result = new ProjectDataStoreResolver(() -> config)
                .resolve(tmp, Optional.of("https://github.com/org/repo.git"));
        assertInstanceOf(ProjectDataStore.InRepo.class, result);
    }

    // remoteUrl mismatch on same localPath → no match
    @Test
    void remoteUrlMismatch_returnsInRepo() throws IOException {
        Path config = writeConfig(tmp, """
                [[projects]]
                remoteUrl = "https://github.com/org/repo.git"
                localPath  = "%s"
                stateDir   = "/state"
                """.formatted(tmp));

        var result = new ProjectDataStoreResolver(() -> config)
                .resolve(tmp, Optional.of("https://github.com/org/OTHER.git"));
        assertInstanceOf(ProjectDataStore.InRepo.class, result);
    }

    // No remote → match on localPath alone
    @Test
    void noRemote_matchesOnLocalPathOnly() throws IOException {
        Path stateDir = tmp.resolve("state");
        Path config = writeConfig(tmp, """
                [[projects]]
                localPath = "%s"
                stateDir  = "%s"
                """.formatted(tmp, stateDir));

        var result = new ProjectDataStoreResolver(() -> config).resolve(tmp, Optional.empty());
        assertInstanceOf(ProjectDataStore.Standalone.class, result);
        assertEquals(stateDir.toAbsolutePath().normalize(), result.stateRoot());
    }

    // Inv-2: stateDir missing → hard error
    @Test
    void missingStateDir_throwsConfigException() throws IOException {
        Path config = writeConfig(tmp, """
                [[projects]]
                remoteUrl = "https://github.com/org/repo.git"
                localPath  = "%s"
                """.formatted(tmp));

        var resolver = new ProjectDataStoreResolver(() -> config);
        assertThrows(StandaloneConfigException.class,
                () -> resolver.resolve(tmp, Optional.of("https://github.com/org/repo.git")));
    }

    // First matching entry wins when multiple entries share localPath
    @Test
    void firstMatchWins() throws IOException {
        Path state1 = tmp.resolve("state1");
        Path state2 = tmp.resolve("state2");
        Path config = writeConfig(tmp, """
                [[projects]]
                remoteUrl = "https://github.com/org/repo.git"
                localPath  = "%s"
                stateDir   = "%s"

                [[projects]]
                remoteUrl = "https://github.com/org/repo.git"
                localPath  = "%s"
                stateDir   = "%s"
                """.formatted(tmp, state1, tmp, state2));

        var result = new ProjectDataStoreResolver(() -> config)
                .resolve(tmp, Optional.of("https://github.com/org/repo.git"));
        assertInstanceOf(ProjectDataStore.Standalone.class, result);
        assertEquals(state1.toAbsolutePath().normalize(), result.stateRoot());
    }

    private static Path writeConfig(Path dir, String toml) throws IOException {
        Path f = dir.resolve("ss-config.toml");
        Files.writeString(f, toml);
        return f;
    }
}