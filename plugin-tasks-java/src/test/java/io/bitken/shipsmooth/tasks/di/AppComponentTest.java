package io.bitken.shipsmooth.tasks.di;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class AppComponentTest {

    @Test
    public void buildsComponentAndProvidesServices() {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get(".")))
                .build();

        assertNotNull(app.xmlService());
        assertNotNull(app.ledgerService());
        assertNotNull(app.worktreeService());
        assertNotNull(app.workflowService());
        assertNotNull(app.workflowServiceImpl());
    }

    @Test
    public void servicesAreSingletons() {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get(".")))
                .build();

        assertSame(app.xmlService(), app.xmlService());
        assertSame(app.ledgerService(), app.ledgerService());
        assertSame(app.worktreeService(), app.worktreeService());
        assertSame(app.workflowService(), app.workflowService());
        assertSame(app.workflowServiceImpl(), app.workflowServiceImpl());
    }

    @Test
    public void workflowServiceIsSameInstanceAsWorkflowServiceImpl() {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get(".")))
                .build();

        assertSame(app.workflowServiceImpl(), app.workflowService());
    }
}