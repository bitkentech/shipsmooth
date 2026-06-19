package io.bitken.ss.cli.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StandaloneConfigResolverTest {

    @TempDir Path tmp;

    // Inv-1: no config file → in-repo default
    @Test
    void noConfigFile_returnsEmpty() {
        Path absent = tmp.resolve("ss-config.toml");
        var resolver = new StandaloneConfigResolver(absent);
        assertTrue(resolver.resolve(tmp, Optional.of("https://github.com/org/repo.git")).isEmpty());
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

        var resolver = new StandaloneConfigResolver(config);
        Optional<Path> result = resolver.resolve(tmp, Optional.of("https://github.com/org/repo.git"));
        assertTrue(result.isPresent());
        assertEquals(stateDir.toAbsolutePath().normalize(), result.get());
    }

    // No entry for this project → in-repo default
    @Test
    void noMatchingEntry_returnsEmpty() throws IOException {
        Path config = writeConfig(tmp, """
                [[projects]]
                remoteUrl = "https://github.com/org/other.git"
                localPath  = "/some/other/path"
                stateDir   = "/state"
                """);

        var resolver = new StandaloneConfigResolver(config);
        assertTrue(resolver.resolve(tmp, Optional.of("https://github.com/org/repo.git")).isEmpty());
    }

    // remoteUrl mismatch on same localPath → no match
    @Test
    void remoteUrlMismatch_returnsEmpty() throws IOException {
        Path config = writeConfig(tmp, """
                [[projects]]
                remoteUrl = "https://github.com/org/repo.git"
                localPath  = "%s"
                stateDir   = "/state"
                """.formatted(tmp));

        var resolver = new StandaloneConfigResolver(config);
        assertTrue(resolver.resolve(tmp, Optional.of("https://github.com/org/OTHER.git")).isEmpty());
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

        var resolver = new StandaloneConfigResolver(config);
        Optional<Path> result = resolver.resolve(tmp, Optional.empty());
        assertTrue(result.isPresent());
        assertEquals(stateDir.toAbsolutePath().normalize(), result.get());
    }

    // Inv-2: stateDir missing → hard error
    @Test
    void missingStateDir_throwsConfigException() throws IOException {
        Path config = writeConfig(tmp, """
                [[projects]]
                remoteUrl = "https://github.com/org/repo.git"
                localPath  = "%s"
                """.formatted(tmp));

        var resolver = new StandaloneConfigResolver(config);
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

        var resolver = new StandaloneConfigResolver(config);
        Optional<Path> result = resolver.resolve(tmp, Optional.of("https://github.com/org/repo.git"));
        assertTrue(result.isPresent());
        assertEquals(state1.toAbsolutePath().normalize(), result.get());
    }

    private static Path writeConfig(Path dir, String toml) throws IOException {
        Path f = dir.resolve("ss-config.toml");
        Files.writeString(f, toml);
        return f;
    }
}
