package io.bitken.ss.conf;

import dagger.Module;
import dagger.Provides;
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

    public ServicesModule(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    @Provides
    @Singleton
    Path provideRepoRoot() {
        return repoRoot;
    }

    @Provides
    @Singleton
    TaskStore provideTaskStore() {
        return new TaskStore();
    }

    @Provides
    @Singleton
    EventLedger provideEventLedger(Path repoRoot) {
        return new EventLedger(repoRoot);
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
    GitTags provideGitTags() {
        return new GitTags();
    }

    @Provides
    @Singleton
    WorkflowServiceImpl provideWorkflowServiceImpl(
            Path repoRoot,
            ProcessRunner processes,
            WorktreeService worktreeService,
            EventLedger ledgerService,
            TaskStore taskStore,
            io.bitken.ss.workflow.ProgressReporter reporter) {
        return new WorkflowServiceImpl(repoRoot, processes, worktreeService, ledgerService, taskStore, reporter);
    }

    @Provides
    @Singleton
    io.bitken.ss.workflow.ProgressReporter provideProgressReporter() {
        return new io.bitken.ss.workflow.ConsoleProgressReporter();
    }

    @Provides
    @Singleton
    PlanService providePlanService(TaskStore taskStore, EventLedger ledger) {
        return new PlanService(taskStore, ledger);
    }

    @Provides
    @Singleton
    WorkflowService provideWorkflowService(WorkflowServiceImpl impl) {
        return impl;
    }
}
