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
import java.util.Optional;

@Module
public class ServicesModule {

    private final Path repoRoot;
    private final Optional<Path> stateRoot;
    private final ExperimentalMode experimentalMode;

    public ServicesModule(Path repoRoot) {
        this(repoRoot, Optional.of(repoRoot), new ExperimentalMode(false));
    }

    public ServicesModule(Path repoRoot, ExperimentalMode experimentalMode) {
        this(repoRoot, Optional.of(repoRoot), experimentalMode);
    }

    /**
     * Full constructor. {@code stateRoot} defaults to {@code repoRoot} in the
     * other constructors (in-repo mode); pass a distinct path for "separate
     * repo" mode.
     */
    public ServicesModule(Path repoRoot, Path stateRoot, ExperimentalMode experimentalMode) {
        this(repoRoot, Optional.of(stateRoot), experimentalMode);
    }

    private ServicesModule(Path repoRoot, Optional<Path> stateRoot, ExperimentalMode experimentalMode) {
        this.repoRoot = repoRoot;
        this.stateRoot = stateRoot;
        this.experimentalMode = experimentalMode;
    }

    /**
     * "Store not settled yet" mode: the project has no resolved state root (a clean first
     * run awaiting {@code store init}). The app still builds so state-independent commands
     * (e.g. {@code store}) can run; any attempt to use the data locator fails clearly.
     */
    public static ServicesModule unsettled(Path repoRoot, ExperimentalMode experimentalMode) {
        return new ServicesModule(repoRoot, Optional.empty(), experimentalMode);
    }

    @Provides
    @Singleton
    @RepoRoot
    Path provideRepoRoot() {
        return repoRoot;
    }

    @Provides
    @Singleton
    @StateRoot
    ResolvedStateRoot provideStateRoot() {
        // Mint the token here: validate the stored state root exactly once, on first use
        // (Provider.get() inside a command's call()). When the project is unsettled there is
        // no state root, so we throw instead — the cli's execution-exception handler turns
        // that into the needs-decision/unresolvable result. Help/version never get here.
        Path root = stateRoot.orElseThrow(() -> new StateRootUnsettledException(
                "shipsmooth state is not set up yet — run `store init` first"));
        return ResolvedStateRoot.of(root);
    }

    @Provides
    @Singleton
    ExperimentalMode provideExperimentalMode() {
        return experimentalMode;
    }

    @Provides
    @Singleton
    ShipsmoothDataLocator provideDataLocator(@RepoRoot Path repoRoot, @StateRoot ResolvedStateRoot stateRoot) {
        return new ShipsmoothDataLocator(repoRoot, stateRoot);
    }

    @Provides
    @Singleton
    TaskStore provideTaskStore(ShipsmoothDataLocator locator) {
        return new TaskStore(locator);
    }

    @Provides
    @Singleton
    GitTags provideGitTags(@RepoRoot Path repoRoot) {
        return new GitTags(repoRoot);
    }

    @Provides
    @Singleton
    GitState provideGitState(@RepoRoot Path repoRoot) {
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
