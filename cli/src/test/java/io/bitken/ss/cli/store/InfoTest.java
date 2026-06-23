package io.bitken.ss.cli.store;

import io.bitken.ss.cli.conf.ds.ConfigWriter;
import io.bitken.ss.cli.conf.ds.ProjectDataStoreResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7 — {@code store info} reports where shipsmooth state lives, on demand. In external
 * mode the plan narratives live outside the project repo; this command tells the skill the
 * {@code plansDir} to read so an agent reliably loads plan context.
 */
class InfoTest {

    @TempDir
    Path tmp;

    /** Settle an external project (write config + provision the state dir), then build Info. */
    private Info infoForExternal(Path config, Path repo, Path stateDir) throws IOException {
        Files.createDirectories(stateDir.resolve("plans"));
        new ConfigWriter(() -> config).writeExternal(repo, Optional.empty(), stateDir);
        Info info = new Info(new ProjectDataStoreResolver(() -> config));
        info.bind(repo, Optional.empty());
        return info;
    }

    private static String runCapturingOut(Info info, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(out));
        try {
            int code = new CommandLine(info.getSpec()).execute(args);
            assertEquals(0, code, "store info should exit 0 when settled");
        } finally {
            System.setOut(original);
        }
        return out.toString();
    }

    @Test
    void json_settledExternal_reportsReadyModeAndPlansDir() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path stateDir = tmp.resolve("state");

        String json = runCapturingOut(infoForExternal(config, repo, stateDir), "--json");

        assertTrue(json.contains("\"status\":\"ready\""), json);
        assertTrue(json.contains("\"mode\":\"external\""), json);
        assertTrue(json.contains("\"stateRoot\":\"" + stateDir + "\""), json);
        // plansDir hangs directly off the external state root (no .shipsmooth segment).
        assertTrue(json.contains("\"plansDir\":\"" + stateDir.resolve("plans") + "\""), json);
    }
}
