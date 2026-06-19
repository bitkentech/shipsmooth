package io.bitken.ss.cli.conf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StandaloneConfigResolverTest {

    @TempDir Path tmp;

    // Inv-1: no config file → in-repo default
    @Test
    void noConfigFile_returnsInRepo() {
        Path absent = tmp.resolve("ss-config.toml");
        var resolver = new StandaloneConfigResolver(absent);
        assertInstanceOf(ResolvedMode.InRepo.class,
                resolver.resolve(tmp, Optional.of("https://github.com/org/repo.git")));
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
        var result = resolver.resolve(tmp, Optional.of("https://github.com/org/repo.git"));
        assertInstanceOf(ResolvedMode.Standalone.class, result);
        assertEquals(stateDir.toAbsolutePath().normalize(),
                ((ResolvedMode.Standalone) result).stateDir());
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

        var resolver = new StandaloneConfigResolver(config);
        assertInstanceOf(ResolvedMode.InRepo.class,
                resolver.resolve(tmp, Optional.of("https://github.com/org/repo.git")));
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

        var resolver = new StandaloneConfigResolver(config);
        assertInstanceOf(ResolvedMode.InRepo.class,
                resolver.resolve(tmp, Optional.of("https://github.com/org/OTHER.git")));
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
        var result = resolver.resolve(tmp, Optional.empty());
        assertInstanceOf(ResolvedMode.Standalone.class, result);
        assertEquals(stateDir.toAbsolutePath().normalize(),
                ((ResolvedMode.Standalone) result).stateDir());
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
        var result = resolver.resolve(tmp, Optional.of("https://github.com/org/repo.git"));
        assertInstanceOf(ResolvedMode.Standalone.class, result);
        assertEquals(state1.toAbsolutePath().normalize(),
                ((ResolvedMode.Standalone) result).stateDir());
    }

    private static Path writeConfig(Path dir, String toml) throws IOException {
        Path f = dir.resolve("ss-config.toml");
        Files.writeString(f, toml);
        return f;
    }
}
