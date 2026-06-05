package io.bitken.ss.conf;

import dagger.Module;
import dagger.Provides;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import io.bitken.ss.git.WorktreeService;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.workflow.DefaultProcessRunner;
import io.bitken.ss.workflow.ProcessRunner;
import io.bitken.ss.workflow.WorkflowService;
import io.bitken.ss.workflow.WorkflowServiceImpl;
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
    EventLedger provideEventLedger(ShipsmoothDataLocator locator) {
        return new EventLedger(locator);
    }

    @Provides
    @Singleton
    ProcessRunner provideProcessRunner() {
        return new DefaultProcessRunner();
    }

    @Provides
    @Singleton
    WorktreeService provideWorktreeService(Path repoRoot, ProcessRunner processes) {
        return new WorktreeService(repoRoot, processes);
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
    WorkflowServiceImpl provideWorkflowServiceImpl(
            Path repoRoot,
            ShipsmoothDataLocator locator,
            ProcessRunner processes,
            WorktreeService worktreeService,
            EventLedger ledgerService,
            TaskStore taskStore,
            io.bitken.ss.workflow.ProgressReporter reporter) {
        return new WorkflowServiceImpl(repoRoot, locator, processes, worktreeService, ledgerService, taskStore, reporter);
    }

    @Provides
    @Singleton
    io.bitken.ss.workflow.ProgressReporter provideProgressReporter() {
        return new io.bitken.ss.workflow.ConsoleProgressReporter();
    }

    @Provides
    @Singleton
    PlanService providePlanService(TaskStore taskStore, EventLedger ledger, ExperimentalMode mode) {
        return new PlanService(taskStore, ledger, mode);
    }

    @Provides
    @Singleton
    WorkflowService provideWorkflowService(WorkflowServiceImpl impl) {
        return impl;
    }
}
