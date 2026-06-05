package io.bitken.ss.cli;

import io.bitken.ss.cli.plan.Preflight;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

public class PlanPreflightTest {

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

    private int run(GitState gitState, GitTags gitTags, String... args) {
        Preflight cmd = new Preflight(gitState, gitTags);
        return new CommandLine(cmd.getSpec()).execute(args);
    }

    @Test
    void failsWhenWorkingTreeIsDirty() {
        GitState dirty = stubState(false, false, false, false);
        GitTags tags = stubTags("plan-7-v1");
        int exit = run(dirty, tags, "--plan", "7");
        assertEquals(1, exit);
        assertTrue(out.toString().contains("FAIL"));
        assertTrue(out.toString().contains("uncommitted"));
    }

    @Test
    void failsWhenVersionTagAbsent() {
        GitState cleanNoTag = stubState(true, false, false, false);
        GitTags tags = stubTags("plan-7-v1");
        int exit = run(cleanNoTag, tags, "--plan", "7");
        assertEquals(1, exit);
        assertTrue(out.toString().contains("FAIL"));
        assertTrue(out.toString().contains("plan-7-v1"));
    }

    @Test
    void passesWithWarningsWhenBranchNotPushed() {
        GitState cleanTaggedUnpushed = stubState(true, true, false, false);
        GitTags tags = stubTags("plan-7-v1");
        int exit = run(cleanTaggedUnpushed, tags, "--plan", "7");
        assertEquals(0, exit);
        String output = out.toString();
        assertTrue(output.contains("PASS"));
        assertTrue(output.contains("WARN"));
    }

    @Test
    void passesCleanlyWhenAllConditionsMet() {
        GitState allGood = stubState(true, true, true, true);
        GitTags tags = stubTags("plan-7-v1");
        int exit = run(allGood, tags, "--plan", "7");
        assertEquals(0, exit);
        String output = out.toString();
        assertTrue(output.contains("PASS"));
        assertFalse(output.contains("WARN"));
        assertFalse(output.contains("FAIL"));
    }

    private static GitState stubState(boolean clean, boolean tagLocal, boolean pushed, boolean tagRemote) {
        return new GitState(java.nio.file.Paths.get(".")) {
            @Override public boolean isClean() { return clean; }
            @Override public boolean tagExistsLocally(String t) { return tagLocal; }
            @Override public boolean isBranchPushedAndNotAhead() { return pushed; }
            @Override public boolean tagExistsOnRemote(String t) { return tagRemote; }
        };
    }

    private static GitTags stubTags(String version) {
        return new GitTags(java.nio.file.Paths.get(".")) {
            @Override public String getPlanVersion(int n) { return version; }
        };
    }
}
