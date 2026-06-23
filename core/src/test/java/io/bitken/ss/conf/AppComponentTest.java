package io.bitken.ss.conf;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class AppComponentTest {

    @Test
    public void buildsComponentAndProvidesServices() {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get(".")))
                .build();

        assertNotNull(app.taskStore());
        assertNotNull(app.experimentalMode());
        assertNotNull(app.gitState());
        assertNotNull(app.gitTags());
        assertNotNull(app.planService());
    }

    @Test
    public void servicesAreSingletons() {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get(".")))
                .build();

        // State-dependent services are handed out via Provider; the singleton guarantee is
        // that repeated get() calls return the same instance.
        assertSame(app.taskStore().get(), app.taskStore().get());
        assertSame(app.gitState(), app.gitState());
        assertSame(app.planService().get(), app.planService().get());
    }
}
