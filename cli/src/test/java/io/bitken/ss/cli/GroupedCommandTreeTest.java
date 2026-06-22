package io.bitken.ss.cli;

import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that drive the full {@link Shipsmooth} command tree the same way
 * {@code main()} does, asserting that the regrouped noun-subcommand argv
 * ({@code plan show}, {@code task status}, …) dispatches correctly through the root
 * {@link CommandLine}.
 *
 * Plan 998 is reserved for these tests.
 */
public class GroupedCommandTreeTest {

    private static final int PLAN_NUM = 998;

    private final Path repoRoot = Paths.get(".");
    private final AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(repoRoot, new ExperimentalMode(true)))
            .build();

    private final TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
    private final File planDir = new File(".shipsmooth/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");

    /** One-shot CLI bound to these args, mirroring main(). */
    private int run(String... args) {
        return new Shipsmooth(app, args).execute();
    }

    @BeforeEach
    void setUp() throws Exception {
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(), "### Task 1: Test task [High]\n");
        List<TaskStore.Task> tasks = List.of(new TaskStore.Task(1, "Test task", "high"));
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);
    }

    @AfterEach
    void tearDown() {
        xmlFile.delete();
        mdFile.delete();
    }

    /** {@code shipsmooth plan show --plan N} routes to the read-only plan view. */
    @Test
    void planShowDispatchesThroughGroup() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(out));
        try {
            int exit = run("plan", "show", "--plan", String.valueOf(PLAN_NUM));
            assertEquals(0, exit);
            String output = out.toString();
            assertTrue(output.contains("Plan " + PLAN_NUM), "expected plan header in: " + output);
            assertTrue(output.contains("Test task"), "expected task name in: " + output);
        } finally {
            System.setOut(originalOut);
        }
    }

    /** {@code shipsmooth task status --plan N --task 1 --status ...} mutates through the group. */
    @Test
    void taskStatusDispatchesThroughGroup() throws Exception {
        int exit = run("task", "status", "--plan", String.valueOf(PLAN_NUM),
                "--task", "1", "--status", "in-progress");
        assertEquals(0, exit);
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        assertEquals("in-progress", planTasks.getTasks().getTask().get(0).getStatus().value());
    }

    /** An unrecognised {@code --status} value is rejected with exit 2 and the allowed list. */
    @Test
    void taskStatusRejectsInvalidStatus() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            int exit = run("task", "status", "--plan", String.valueOf(PLAN_NUM),
                    "--task", "1", "--status", "bogus");
            assertEquals(2, exit);
            String msg = err.toString();
            assertTrue(msg.contains("invalid status"), "expected error in: " + msg);
            // Allowed values must be the accepted CLI tokens (e.g. "agent-coded"),
            // not the JAXB enum constant names (e.g. "AGENT_CODED").
            assertTrue(msg.contains("agent-coded"), "expected CLI value form in: " + msg);
            assertFalse(msg.contains("AGENT_CODED"), "should not print enum constant names: " + msg);
        } finally {
            System.setErr(originalErr);
        }
    }

    /** Bare {@code shipsmooth plan} (no subcommand) prints group usage listing its verbs. */
    @Test
    void barePlanGroupPrintsUsage() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            int exit = run("plan");
            assertEquals(0, exit);
            String usage = err.toString();
            assertTrue(usage.contains("init"), "expected verb list in usage: " + usage);
            assertTrue(usage.contains("show"), "expected verb list in usage: " + usage);
            assertTrue(usage.contains("update"), "expected verb list in usage: " + usage);
        } finally {
            System.setErr(originalErr);
        }
    }

    /** Bare {@code shipsmooth task} (no subcommand) prints group usage listing its verbs. */
    @Test
    void bareTaskGroupPrintsUsage() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            int exit = run("task");
            assertEquals(0, exit);
            String usage = err.toString();
            assertTrue(usage.contains("add"), "expected verb list in usage: " + usage);
            assertTrue(usage.contains("status"), "expected verb list in usage: " + usage);
            assertTrue(usage.contains("set-commit"), "expected verb list in usage: " + usage);
        } finally {
            System.setErr(originalErr);
        }
    }

    /**
     * Group descriptions should summarise the group's purpose, not restate the
     * verb list that picocli already renders in the {@code Commands:} block
     * (which is redundant and drifts as verbs are added/removed).
     */
    @Test
    void groupDescriptionsDoNotRestateVerbList() {
        CommandLine root = new CommandTree(app).commandLine();
        String planDesc = String.join(" ",
                root.getSubcommands().get("plan").getCommandSpec().usageMessage().description());
        String taskDesc = String.join(" ",
                root.getSubcommands().get("task").getCommandSpec().usageMessage().description());

        assertFalse(planDesc.contains("init") || planDesc.contains("preflight"),
                "plan group description should not list its verbs: " + planDesc);
        assertFalse(taskDesc.contains("set-commit") || taskDesc.contains("deviation"),
                "task group description should not list its verbs: " + taskDesc);
        assertFalse(planDesc.isBlank(), "plan group should still have a description");
        assertFalse(taskDesc.isBlank(), "task group should still have a description");
    }

    /**
     * Every leaf under both noun groups must carry the standard help mixin so
     * {@code <group> <leaf> --help} prints usage instead of failing required-option
     * validation. Asserts at the spec level (the {@code addLeaves} seam) so a
     * regression is caught structurally, not only end-to-end.
     */
    @Test
    void everyLeafHasHelpOption() {
        CommandLine root = new CommandTree(app).commandLine();
        for (CommandLine group : root.getSubcommands().values()) {
            for (var entry : group.getSubcommands().entrySet()) {
                String leaf = group.getCommandName() + " " + entry.getKey();
                assertNotNull(entry.getValue().getCommandSpec().findOption("--help"),
                        "leaf `" + leaf + "` should have a --help option");
            }
        }
    }

}
