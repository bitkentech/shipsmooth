package io.bitken.ss.cli;

import io.bitken.ss.cli.plan.QuickStart;
import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.svc.plan.NewPlan;
import io.bitken.ss.svc.plan.PlanNumbers;
import io.bitken.ss.svc.plan.PlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlanQuickStartTest {

    @TempDir
    Path repoRoot;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void captureOut() {
        originalOut = System.out;
        System.setOut(new PrintStream(out));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private int run(GitState gitState, String... args) {
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(repoRoot);
        NewPlan newPlan = new NewPlan(new PlanNumbers(locator), gitState, locator);
        PlanService planService = new PlanService(new TaskStore(locator), newPlan);
        QuickStart cmd = new QuickStart(() -> planService);
        return new CommandLine(cmd.getSpec()).execute(args);
    }

    @Test
    void printsHandoffLinesOnSuccess() {
        int exit = run(branchOk(), "--desc", "desktop ui");

        assertEquals(0, exit);
        String output = out.toString();
        assertTrue(output.contains("Created branch: t/1-desktop-ui"), output);
        assertTrue(output.contains("Wrote stub:"), output);
    }

    @Test
    void reportsErrorAndExitsOneOnCollision() {
        int exit = run(branchExists(), "--desc", "desktop ui");

        assertEquals(1, exit);
        assertTrue(out.toString().contains("ERROR"), out.toString());
        assertTrue(out.toString().contains("already exists"), out.toString());
    }

    @Test
    void exitsOneWhenGitRefusesBranch() {
        int exit = run(branchRefused(), "--desc", "desktop ui");

        assertEquals(1, exit);
        assertTrue(out.toString().contains("ERROR"), out.toString());
    }

    private GitState branchOk() {
        return stub(false, name -> true);
    }

    private GitState branchExists() {
        return stub(true, name -> true);
    }

    private GitState branchRefused() {
        return stub(false, name -> false);
    }

    @FunctionalInterface
    interface BranchCreator { boolean create(String name); }

    private GitState stub(boolean exists, BranchCreator creator) {
        return new GitState(repoRoot) {
            @Override public boolean branchExists(String n) { return exists; }
            @Override public boolean createBranch(String n) { return creator.create(n); }
        };
    }
}
