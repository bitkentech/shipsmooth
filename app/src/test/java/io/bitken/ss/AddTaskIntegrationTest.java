package io.bitken.ss;
import io.bitken.ss.conf.ShipsmoothDataLocator;

import io.bitken.ss.cli.Shipsmooth;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import io.bitken.ss.jaxb.TaskType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage for the non-experimental {@code add-task} subcommand:
 * appending a task to an existing plan's XML via the CLI, without the
 * {@code --enable-experimental} flag.
 */
public class AddTaskIntegrationTest {

    private static final int PLAN_NUM = 993;
    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");

    @BeforeEach
    public void setUp() throws Exception {
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(), "### Task 1: Seed task [High]\n");

        List<TaskStore.Task> tasks = List.of(new TaskStore.Task(1, "Seed task", "high"));
        TaskStore store = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        PlanTasks planTasks = store.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        store.writePlanTasks(planTasks, xmlFile);
    }

    private int run(String... args) {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get("."), ExperimentalMode.fromArgs(args)))
                .build();
        return new Shipsmooth(app, args).execute();
    }

    @AfterEach
    public void tearDown() {
        xmlFile.delete();
        mdFile.delete();
    }

    @Test
    public void addTaskViaCliAppendsTaskToXmlWithoutExperimentalFlag() throws Exception {
        int exit = run("task", "add", "--plan", String.valueOf(PLAN_NUM),
                "--name", "Newly added task", "--risk", "medium");
        assertEquals(0, exit, "task add must succeed without --enable-experimental");

        TaskStore store = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        PlanTasks plan = store.readPlanTasks(xmlFile);
        List<TaskType> tasks = plan.getTasks().getTask();

        assertEquals(2, tasks.size(), "a second task should have been appended");
        TaskType added = tasks.get(1);
        assertEquals(2, added.getId().intValue(), "next id should be max+1");
        assertEquals("Newly added task", added.getName());
        assertEquals("medium", added.getRisk());
        assertEquals("pending", added.getStatus().value());
        assertEquals("", added.getCommit());
        assertEquals("plan-" + PLAN_NUM + "-v1", added.getCreatedFrom());
    }

    @Test
    public void addTaskViaCliRecordsDependsOn() throws Exception {
        int exit = run("task", "add", "--plan", String.valueOf(PLAN_NUM),
                "--name", "Dependent task", "--risk", "low", "--depends-on", "1");
        assertEquals(0, exit);

        TaskStore store = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        PlanTasks plan = store.readPlanTasks(xmlFile);
        assertEquals("1", store.getDependsOn(plan, 2), "depends-on should be persisted on the new task");
    }
}
