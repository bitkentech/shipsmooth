package io.bitken.ss.cli;

import io.bitken.ss.cli.plan.Init;
import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.svc.plan.NewPlan;
import io.bitken.ss.svc.plan.PlanNumbers;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.gw.TaskStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end contract for `plan init` when the plan markdown does not (fully)
 * match the canonical task-heading grammar. A zero-task parse must fail loudly
 * and leave existing task state untouched; a partial parse must succeed but
 * surface the near-miss lines it skipped.
 */
public class PlanInitDiagnosticsIntegrationTest {

    private final int PLAN_NUM = 998;
    private final File planDir = new File(".shipsmooth/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");
    private final TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
    private PlanService planService;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    public void setUp() throws Exception {
        ShipsmoothDataLocator locator = new ShipsmoothDataLocator(Paths.get("."));
        NewPlan newPlan = new NewPlan(new PlanNumbers(locator), new GitState(Paths.get(".")), locator);
        planService = new PlanService(xmlService, newPlan);
        planDir.mkdirs();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out));
        System.setErr(new PrintStream(err));
    }

    @AfterEach
    public void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        xmlFile.delete();
        mdFile.delete();
    }

    @Test
    public void zeroTaskParseFailsLoudlyAndPreservesExistingXml() throws Exception {
        // A previously initialised, populated task XML must survive a bad re-init.
        PlanTasks existing = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1",
                List.of(new TaskStore.Task(1, "Existing task", "high")));
        xmlService.writePlanTasks(existing, xmlFile);
        String xmlBefore = Files.readString(xmlFile.toPath());

        // Plausible agent-authored plan, every heading a near-miss of the grammar.
        Files.writeString(mdFile.toPath(), """
                # Plan — add a done command

                ## Tasks

                ## Task 1: Extend the task model [Low]

                Depends-on: 1

                ### Task 2 - Show completion state in view [Low]

                **Task 3: End-to-end test** [Low]
                """);

        int exitCode = execInit();

        assertEquals(1, exitCode, "zero parsed tasks must be an error, not a silent success");
        assertEquals(xmlBefore, Files.readString(xmlFile.toPath()),
                "a failed init must not touch existing task XML");
        String stderr = err.toString();
        assertTrue(stderr.contains("### Task N:"),
                "error must state the canonical heading grammar, got: " + stderr);
        assertTrue(stderr.contains("*Depends-on:"),
                "error must state the dependency-line grammar, got: " + stderr);
    }

    @Test
    public void partialParseSucceedsButReportsNearMissLines() throws Exception {
        Files.writeString(mdFile.toPath(), """
                # Plan — add a done command

                ### Task 1: Extend the task model [Low]

                Body text.

                ## Task 2: Implement the done command [Medium]

                Body text.
                """);

        int exitCode = execInit();

        assertEquals(0, exitCode);
        PlanTasks written = xmlService.readPlanTasks(xmlFile);
        assertEquals(1, written.getTasks().getTask().size());
        String stdout = out.toString();
        assertTrue(stdout.contains("line 7"),
                "success output must point at the skipped near-miss heading, got: " + stdout);
    }

    private int execInit() {
        return new CommandLine(new Init(() -> planService, () -> xmlService,
                new GitTags(Paths.get("."))).getSpec())
                .execute("--plan", String.valueOf(PLAN_NUM), "--tasks-from", mdFile.getPath());
    }
}
