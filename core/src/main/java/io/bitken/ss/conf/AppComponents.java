package io.bitken.ss.conf;

import dagger.Component;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.TaskStore;
import jakarta.inject.Singleton;

@Singleton
@Component(modules = ServicesModule.class)
public interface AppComponents {
    ShipsmoothDataLocator dataLocator();
    ExperimentalMode experimentalMode();
    TaskStore taskStore();
    GitState gitState();
    GitTags gitTags();
    PlanService planService();
}
