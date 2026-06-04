package io.bitken.ss.cli;

import io.bitken.ss.cli.plan.Resume;
import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.TaskStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PlanResumeTest {

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

    private int run(TaskStore taskStore, GitState gitState, String... args) {
        Resume cmd = new Resume(taskStore, gitState);
        return new CommandLine(cmd.getSpec()).execute(args);
    }

    @Test
    void reportsMissingXmlFile() throws Exception {
        TaskStore store = new TaskStore(new ShipsmoothDataLocator(repoRoot));
        GitState state = stubWorktrees(List.of());
        int exit = run(store, state, "--plan", "99");
        assertEquals(1, exit);
        assertTrue(out.toString().contains("not found"), out.toString());
    }

    @Test
    void printsSummaryAndWorktreeInfo() throws Exception {
        // Create the plans dir and a minimal XML file
        Path plansDir = repoRoot.resolve(".agents/plans");
        Files.createDirectories(plansDir);
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(repoRoot);
        TaskStore store = new TaskStore(locator);
        List<TaskStore.Task> tasks = List.of(new TaskStore.Task(1, "Some task", "low"));
        var planTasks = store.generatePlanTasks(99, "plan-99-v1", tasks);
        store.writePlanTasks(planTasks, locator.planTasksFile(99));

        GitState state = stubWorktrees(List.of("/some/path  abc123  [main]", "/other  def456  [integration/plan-99]"));
        int exit = run(store, state, "--plan", "99");
        assertEquals(0, exit);
        String output = out.toString();
        assertTrue(output.contains("Some task"), "should include plan summary: " + output);
        assertTrue(output.contains("integration/plan-99"), "should include relevant worktree: " + output);
    }

    @Test
    void filtersWorktreesToPlanNumber() throws Exception {
        Path plansDir = repoRoot.resolve(".agents/plans");
        Files.createDirectories(plansDir);
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(repoRoot);
        TaskStore store = new TaskStore(locator);
        List<TaskStore.Task> tasks = List.of(new TaskStore.Task(1, "Task", "low"));
        store.writePlanTasks(store.generatePlanTasks(99, "plan-99-v1", tasks), locator.planTasksFile(99));

        GitState state = stubWorktrees(List.of(
            "/main  aaa  [main]",
            "/other  bbb  [integration/plan-98]"
        ));
        int exit = run(store, state, "--plan", "99");
        assertEquals(0, exit);
        String output = out.toString();
        assertFalse(output.contains("integration/plan-98"), "should not show other plan worktrees: " + output);
    }

    private static GitState stubWorktrees(List<String> lines) {
        return new GitState(Path.of(".")) {
            @Override public List<String> worktreeList() { return lines; }
        };
    }
}
