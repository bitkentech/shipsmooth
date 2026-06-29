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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return boundInfo(config, repo);
    }

    /** Settle an in-repo project (write config + provision .shipsmooth/plans), then build Info. */
    private Info infoForInRepo(Path config, Path repo) throws IOException {
        Files.createDirectories(repo.resolve(".shipsmooth").resolve("plans"));
        new ConfigWriter(() -> config).writeInRepo(repo, Optional.empty());
        return boundInfo(config, repo);
    }

    private Info boundInfo(Path config, Path repo) {
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
        assertTrue(json.contains("\"storageType\":\"separate-dir\""), json);
        assertTrue(json.contains("\"stateRoot\":\"" + stateDir + "\""), json);
        // plansDir hangs directly off the external state root (no .shipsmooth segment).
        assertTrue(json.contains("\"plansDir\":\"" + stateDir.resolve("plans") + "\""), json);
    }

    @Test
    void json_settledInRepo_plansDirIncludesShipsmoothSegment() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        String json = runCapturingOut(infoForInRepo(config, repo), "-j");

        assertTrue(json.contains("\"storageType\":\"same-repo\""), json);
        // Same-repo layout inserts the .shipsmooth segment between repo root and plans/.
        Path expectedPlans = repo.resolve(".shipsmooth").resolve("plans");
        assertTrue(json.contains("\"plansDir\":\"" + expectedPlans + "\""), json);
    }

    @Test
    void noFlag_settledExternal_printsHumanTextWithPlansPath() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        Path stateDir = tmp.resolve("state");

        String text = runCapturingOut(infoForExternal(config, repo, stateDir));

        assertTrue(text.contains("separate-dir storage at " + stateDir), text);
        assertTrue(text.contains("plans: " + stateDir.resolve("plans")), text);
        assertFalse(text.contains("{"), "default output is human text, not JSON: " + text);
    }

    @Test
    void json_unsettled_emitsResolutionShapeNotReady() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");           // no config written
        Path repo = Files.createDirectories(tmp.resolve("repo")); // clean first run

        String json = runCapturingOut(boundInfo(config, repo), "--json");

        assertFalse(json.contains("\"status\":\"ready\""), json);
        assertTrue(json.contains("\"status\":\"needs-decision\""), json);
    }

    @Test
    void noFlag_unsettled_printsHumanNotSetUpMessage() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));

        String text = runCapturingOut(boundInfo(config, repo));

        assertTrue(text.contains("not set up yet"), text);
        assertFalse(text.contains("{"), "default output is human text, not JSON: " + text);
    }

    @Test
    void unresolvable_legacyTree_isReported() throws IOException {
        Path config = tmp.resolve("shipsmooth.toml");
        Path repo = Files.createDirectories(tmp.resolve("repo"));
        // A legacy .agents/plans/ tree makes the project unresolvable.
        Files.createDirectories(repo.resolve(".agents").resolve("plans"));

        String json = runCapturingOut(boundInfo(config, repo), "--json");
        assertTrue(json.contains("\"status\":\"unresolvable\""), json);

        String text = runCapturingOut(boundInfo(config, repo));
        assertTrue(text.contains("unresolvable"), text);
    }
}
