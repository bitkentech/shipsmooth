package io.bitken.ss.conf;

import dagger.Component;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.TaskStore;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;

@Singleton
@Component(modules = ServicesModule.class)
public interface AppComponents {
    ShipsmoothDataLocator dataLocator();
    ExperimentalMode experimentalMode();
    GitState gitState();
    GitTags gitTags();

    // State-dependent services are handed out as Providers so command leaves can be
    // constructed without a settled state root (the locator is only built — and the
    // state root only touched — when .get() is called inside a command's call()).
    Provider<TaskStore> taskStore();
    Provider<PlanService> planService();
}
