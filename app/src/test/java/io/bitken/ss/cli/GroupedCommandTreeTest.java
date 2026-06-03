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
    private final File planDir = new File(".agents/plans");
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

    /** {@code worker init} dispatches through the (all-experimental) worker group. */
    @Test
    void workerInitDispatchesThroughGroup() {
        // worker group is experimental; app runs with ExperimentalMode(true).
        // worker-init needs a --plan to do real work; here we only assert the
        // grouped path is RECOGNISED, not that the old flat name resolves.
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            run("worker", "init");
            String stderr = err.toString();
            assertFalse(stderr.contains("Unmatched argument") && stderr.contains("'worker'"),
                "worker group should be recognised under experimental mode; got: " + stderr);
        } finally {
            System.setErr(originalErr);
        }
    }

    /** The old flat {@code worker-init} name no longer resolves at the top level. */
    @Test
    void flatWorkerInitNameRemoved() {
        assertTrue(stderrOf("worker-init", "--plan", String.valueOf(PLAN_NUM)).contains("Unmatched argument"),
            "flat 'worker-init' should be an unmatched top-level argument");
    }

    /** {@code ledger watch} dispatches through the group; the flat {@code ledger-watch} is gone. */
    @Test
    void ledgerWatchFoldsIntoGroup() {
        assertTrue(stderrOf("ledger-watch").contains("Unmatched argument"),
            "flat 'ledger-watch' should be an unmatched top-level argument");

        // 'ledger watch' is experimental and lives under the ledger group; under
        // experimental mode it must be recognised (not an unknown-subcommand error).
        assertFalse(stderrOf("ledger", "watch", "--help").contains("Unmatched argument"),
            "'ledger watch' should be recognised under the ledger group");
    }

    /** Run argv and capture what the CLI writes to stderr. */
    private String stderrOf(String... args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            run(args);
            return err.toString();
        } finally {
            System.setErr(originalErr);
        }
    }

    /** Non-experimental {@code ledger list} stays available under the group. */
    @Test
    void ledgerListRemainsNonExperimental() {
        int exit = run("ledger", "list");
        assertEquals(0, exit, "non-experimental 'ledger list' must work");
    }

    /** Bare {@code shipsmooth worker} (no subcommand) prints group usage listing its verbs. */
    @Test
    void bareWorkerGroupPrintsUsage() {
        String usage = stderrOf("worker");
        assertTrue(usage.contains("base"), "expected verb list in usage: " + usage);
        assertTrue(usage.contains("init"), "expected verb list in usage: " + usage);
        assertTrue(usage.contains("cleanup"), "expected verb list in usage: " + usage);
    }

    /** Bare {@code shipsmooth ledger} (no subcommand) prints group usage listing its verbs. */
    @Test
    void bareLedgerGroupPrintsUsage() {
        String usage = stderrOf("ledger");
        assertTrue(usage.contains("list"), "expected verb list in usage: " + usage);
        assertTrue(usage.contains("verify"), "expected verb list in usage: " + usage);
        // experimental verb is present because this app runs with ExperimentalMode(true)
        assertTrue(usage.contains("watch"), "expected experimental verb in usage: " + usage);
    }
}
