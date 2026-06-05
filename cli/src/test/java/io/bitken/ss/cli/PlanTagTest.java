package io.bitken.ss.cli;

import io.bitken.ss.cli.plan.Tag;
import io.bitken.ss.gw.GitTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PlanTagTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void captureStreams() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private int run(GitTags gitTags, String... args) {
        return new CommandLine(new Tag(gitTags).getSpec()).execute(args);
    }

    @Test
    void versionKindCreatesNextTagAndPrintsPushLine() {
        List<String> created = new ArrayList<>();
        GitTags tags = new GitTags(java.nio.file.Paths.get(".")) {
            @Override public String nextPlanVersion(int n) { return "plan-7-v2"; }
            @Override public boolean tagExists(String t) { return false; }
            @Override public boolean createTag(String t) { created.add(t); return true; }
        };
        int exit = run(tags, "--plan", "7", "--kind", "version");
        assertEquals(0, exit);
        assertEquals(List.of("plan-7-v2"), created);
        String output = out.toString();
        assertTrue(output.contains("plan-7-v2"), "should mention tag name: " + output);
        assertTrue(output.contains("git push"), "should print push line: " + output);
    }

    @Test
    void versionKindRefusesIfTagAlreadyExists() {
        GitTags tags = new GitTags(java.nio.file.Paths.get(".")) {
            @Override public String nextPlanVersion(int n) { return "plan-7-v2"; }
            @Override public boolean tagExists(String t) { return true; }
            @Override public boolean createTag(String t) { return false; }
        };
        int exit = run(tags, "--plan", "7", "--kind", "version");
        assertEquals(1, exit);
        assertTrue(out.toString().contains("plan-7-v2"), "error should name the existing tag");
    }

    @Test
    void completeKindCreatesCompleteTag() {
        List<String> created = new ArrayList<>();
        GitTags tags = new GitTags(java.nio.file.Paths.get(".")) {
            @Override public boolean createTag(String t) { created.add(t); return true; }
        };
        int exit = run(tags, "--plan", "7", "--kind", "complete");
        assertEquals(0, exit);
        assertEquals(List.of("plan-7-complete"), created);
        assertTrue(out.toString().contains("git push"));
    }

    @Test
    void abandonedKindCreatesAbandonedTag() {
        List<String> created = new ArrayList<>();
        GitTags tags = new GitTags(java.nio.file.Paths.get(".")) {
            @Override public boolean createTag(String t) { created.add(t); return true; }
        };
        int exit = run(tags, "--plan", "7", "--kind", "abandoned");
        assertEquals(0, exit);
        assertEquals(List.of("plan-7-abandoned"), created);
    }

    @Test
    void unknownKindFails() {
        int exit = run(new GitTags(java.nio.file.Paths.get(".")), "--plan", "7", "--kind", "bogus");
        assertEquals(1, exit);
    }
}
