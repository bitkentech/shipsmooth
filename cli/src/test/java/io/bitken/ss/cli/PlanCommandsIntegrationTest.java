package io.bitken.ss.cli;

import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the four new plan subcommands (preflight, tag, branch, resume).
 * These are end-to-end: drive the full CLI the same way main() does.
 * Plan 997 is reserved for these tests.
 */
public class PlanCommandsIntegrationTest {

    @TempDir
    Path tempDir;

    private AppComponents app() {
        return DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(tempDir, new ExperimentalMode(false)))
                .build();
    }

    private int run(AppComponents app, String... args) {
        return new Shipsmooth(app, args).execute();
    }

    @Test
    void planPreflightIsRegisteredUnderPlanGroup() {
        var out = new ByteArrayOutputStream();
        var originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = run(app(), "plan", "--help");
            System.setOut(originalOut);
            String help = out.toString();
            assertTrue(help.contains("preflight"), "plan --help should list preflight: " + help);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void planBranchCreatesLocalBranchAndPrintsPushLine(@TempDir Path gitDir) throws Exception {
        // Init a bare git repo so branch command has a real git context
        Runtime.getRuntime().exec(new String[]{"git", "init"}, null, gitDir.toFile()).waitFor();
        Runtime.getRuntime().exec(
                new String[]{"git", "commit", "--allow-empty", "-m", "init"},
                null, gitDir.toFile()).waitFor();

        var out = new ByteArrayOutputStream();
        var originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            AppComponents a = DaggerAppComponents.builder()
                    .servicesModule(new ServicesModule(gitDir, new ExperimentalMode(false)))
                    .build();
            int exit = run(a, "plan", "branch", "--issue", "pb-999", "--desc", "my feature");
            System.setOut(originalOut);
            String output = out.toString();
            assertTrue(output.contains("t/pb-999-my-feature"),
                    "branch command should print the branch name: " + output);
            assertTrue(output.contains("git push"),
                    "branch command should print a git push line: " + output);
        } finally {
            System.setOut(originalOut);
        }
    }
}
