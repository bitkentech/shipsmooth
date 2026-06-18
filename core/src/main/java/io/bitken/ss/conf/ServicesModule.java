package io.bitken.ss.conf;

import dagger.Module;
import dagger.Provides;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.svc.plan.NewPlan;
import io.bitken.ss.svc.plan.PlanNumbers;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.TaskStore;
import jakarta.inject.Singleton;

import java.nio.file.Path;

@Module
public class ServicesModule {

    private final Path repoRoot;
    private final ExperimentalMode experimentalMode;

    public ServicesModule(Path repoRoot) {
        this(repoRoot, new ExperimentalMode(false));
    }

    public ServicesModule(Path repoRoot, ExperimentalMode experimentalMode) {
        this.repoRoot = repoRoot;
        this.experimentalMode = experimentalMode;
    }

    @Provides
    @Singleton
    Path provideRepoRoot() {
        return repoRoot;
    }

    @Provides
    @Singleton
    ExperimentalMode provideExperimentalMode() {
        return experimentalMode;
    }

    @Provides
    @Singleton
    ShipsmoothDataLocator provideDataLocator(Path repoRoot) {
        return new ShipsmoothDataLocator(repoRoot);
    }

    @Provides
    @Singleton
    TaskStore provideTaskStore(ShipsmoothDataLocator locator) {
        return new TaskStore(locator);
    }

    @Provides
    @Singleton
    GitTags provideGitTags(Path repoRoot) {
        return new GitTags(repoRoot);
    }

    @Provides
    @Singleton
    GitState provideGitState(Path repoRoot) {
        return new GitState(repoRoot);
    }

    @Provides
    @Singleton
    PlanNumbers providePlanNumbers(ShipsmoothDataLocator locator) {
        return new PlanNumbers(locator);
    }

    @Provides
    @Singleton
    NewPlan provideNewPlan(PlanNumbers planNumbers, GitState gitState, ShipsmoothDataLocator locator) {
        return new NewPlan(planNumbers, gitState, locator);
    }

    @Provides
    @Singleton
    PlanService providePlanService(TaskStore taskStore, NewPlan newPlan) {
        return new PlanService(taskStore, newPlan);
    }
}
