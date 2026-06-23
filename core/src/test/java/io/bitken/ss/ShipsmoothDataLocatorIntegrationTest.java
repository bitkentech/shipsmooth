package io.bitken.ss;

import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.conf.ShipsmoothDataLocator;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying that ShipsmoothDataLocator is the single source of
 * path truth: TaskStore resolves paths via the locator, and the Dagger component
 * wires everything correctly.
 */
public class ShipsmoothDataLocatorIntegrationTest {

    @TempDir
    Path repoRoot;

    @Test
    public void taskStoreLoadsAndSavesPlanViaLocator() throws Exception {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(repoRoot))
                .build();

        ShipsmoothDataLocator locator = app.dataLocator();
        TaskStore store = app.taskStore().get();

        // Create the plans directory so the store can write
        locator.planTasksFile(99).getParentFile().mkdirs();

        List<TaskStore.Task> tasks = List.of(new TaskStore.Task(1, "Test task", "low"));
        PlanTasks plan = store.generatePlanTasks(99, "plan-99-v1", tasks);
        store.savePlan(99, plan);

        // TaskStore must resolve the path through the locator — the file should
        // exist at the location the locator advertises, not some hardcoded relative path
        assertTrue(locator.planTasksFile(99).exists(),
                "plan file must exist at locator-advertised path");

        PlanTasks loaded = store.loadPlan(99);
        assertEquals(99, loaded.getPlan().intValue());
        assertEquals(1, loaded.getTasks().getTask().size());
    }

    @Test
    public void dataLocatorIsSingletonAndSharedWithTaskStore() throws Exception {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(repoRoot))
                .build();

        ShipsmoothDataLocator locator = app.dataLocator();

        // The path the locator advertises must use repoRoot, not the process working dir
        Path expected = repoRoot.resolve(".shipsmooth/plans/plan-42-tasks.xml");
        assertEquals(expected.toFile().getCanonicalPath(),
                locator.planTasksFile(42).getCanonicalPath(),
                "locator must resolve paths under the injected repoRoot");

        // Singleton check
        assertSame(locator, app.dataLocator());
    }
}
