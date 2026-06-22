package io.bitken.ss.cli;
import io.bitken.ss.conf.ShipsmoothDataLocator;

import io.bitken.ss.cli.conf.ExperimentalModeParser;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShipsmoothIntegrationTest {

    private static final int PLAN_NUM = 994;
    private final File planDir = new File(".shipsmooth/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");

    @BeforeEach
    public void setUp() throws Exception {
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(), "### Task 1: CLI test task [High]\n");

        List<TaskStore.Task> tasks = List.of(new TaskStore.Task(1, "CLI test task", "high"));
        TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);
    }

    /** One-shot CLI bound to these args, seeded from the flag in args. */
    private int run(String... args) {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get("."), ExperimentalModeParser.fromArgs(args)))
                .build();
        return new Shipsmooth(app, args).execute();
    }

    @AfterEach
    public void tearDown() {
        xmlFile.delete();
        mdFile.delete();
    }

    @Test
    public void cliHelpRuns() {
        assertEquals(0, run("--help"));
    }

    @Test
    public void updateStatusViaCliMutatesXml() throws Exception {
        int exit = run("task", "status", "--plan", String.valueOf(PLAN_NUM), "--task", "1", "--status", "agent-coded");
        assertEquals(0, exit);

        TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        assertEquals("agent-coded", planTasks.getTasks().getTask().get(0).getStatus().value());
    }

    @Test
    public void addCommentViaCliMutatesXml() throws Exception {
        int exit = run("task", "comment", "--plan", String.valueOf(PLAN_NUM), "--task", "1", "--message", "via Shipsmooth");
        assertEquals(0, exit);

        TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        var comments = planTasks.getTasks().getTask().get(0).getComments().getComment();
        assertEquals(1, comments.size());
        assertEquals("via Shipsmooth", comments.get(0).getMessage());
    }
}
