package io.bitken.ss.cli;

import io.bitken.ss.cli.plan.Branch;
import io.bitken.ss.gw.GitState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PlanBranchTest {

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
        Branch cmd = new Branch(gitState);
        return new CommandLine(cmd.getSpec()).execute(args);
    }

    @Test
    void createsBranchWithSluggedDesc() {
        List<String> created = new ArrayList<>();
        GitState state = stub(false, name -> { created.add(name); return true; });
        int exit = run(state, "--issue", "pb-310", "--desc", "my cool feature");
        assertEquals(0, exit);
        assertEquals(List.of("t/pb-310-my-cool-feature"), created);
        String output = out.toString();
        assertTrue(output.contains("t/pb-310-my-cool-feature"), output);
        assertTrue(output.contains("git push"), output);
    }

    @Test
    void slugStripsSpecialCharsAndLowercases() {
        List<String> created = new ArrayList<>();
        GitState state = stub(false, name -> { created.add(name); return true; });
        run(state, "--issue", "PB-99", "--desc", "Fix: the Bug!");
        assertEquals(List.of("t/pb-99-fix-the-bug"), created);
    }

    @Test
    void failsIfBranchAlreadyExists() {
        GitState state = stub(true, name -> false);
        int exit = run(state, "--issue", "pb-99", "--desc", "existing branch");
        assertEquals(1, exit);
        assertTrue(out.toString().contains("already exists"), out.toString());
    }

    @Test
    void failsIfGitCheckoutFails() {
        GitState state = stub(false, name -> false);
        int exit = run(state, "--issue", "pb-99", "--desc", "some feature");
        assertEquals(1, exit);
    }

    @Test
    void createsBranchFromPlanNumberWithoutIssue() {
        List<String> created = new ArrayList<>();
        GitState state = stub(false, name -> { created.add(name); return true; });
        int exit = run(state, "--plan", "71", "--desc", "gradle skills trial");
        assertEquals(0, exit);
        assertEquals(List.of("t/71-gradle-skills-trial"), created);
        assertTrue(out.toString().contains("git push"), out.toString());
    }

    @Test
    void failsIfNeitherIssueNorPlanProvided() {
        GitState state = stub(false, name -> true);
        int exit = run(state, "--desc", "some feature");
        assertEquals(1, exit);
        assertTrue(out.toString().contains("ERROR"), out.toString());
    }

    @Test
    void failsIfBothIssueAndPlanProvided() {
        GitState state = stub(false, name -> true);
        int exit = run(state, "--issue", "pb-99", "--plan", "71", "--desc", "some feature");
        assertEquals(1, exit);
        assertTrue(out.toString().contains("ERROR"), out.toString());
    }

    @FunctionalInterface
    interface BranchCreator { boolean create(String name); }

    private static GitState stub(boolean exists, BranchCreator creator) {
        return new GitState(Paths.get(".")) {
            @Override public boolean branchExists(String n) { return exists; }
            @Override public boolean createBranch(String n) { return creator.create(n); }
        };
    }
}
