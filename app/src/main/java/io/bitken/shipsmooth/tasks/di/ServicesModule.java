package io.bitken.shipsmooth.tasks.di;

import dagger.Module;
import dagger.Provides;
import io.bitken.shipsmooth.tasks.git.GitTagService;
import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import io.bitken.shipsmooth.tasks.workflow.DefaultProcessRunner;
import io.bitken.shipsmooth.tasks.workflow.ProcessRunner;
import io.bitken.shipsmooth.tasks.workflow.WorkflowService;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
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
    XmlService provideXmlService() {
        return new XmlService();
    }

    @Provides
    @Singleton
    LedgerService provideLedgerService(Path repoRoot) {
        return new LedgerService(repoRoot);
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
    GitTagService provideGitTagService() {
        return new GitTagService();
    }

    @Provides
    @Singleton
    WorkflowServiceImpl provideWorkflowServiceImpl(
            Path repoRoot,
            ProcessRunner processes,
            WorktreeService worktreeService,
            LedgerService ledgerService,
            XmlService xmlService,
            io.bitken.shipsmooth.tasks.workflow.ProgressReporter reporter) {
        return new WorkflowServiceImpl(repoRoot, processes, worktreeService, ledgerService, xmlService, reporter);
    }

    @Provides
    @Singleton
    io.bitken.shipsmooth.tasks.workflow.ProgressReporter provideProgressReporter() {
        return new io.bitken.shipsmooth.tasks.workflow.ConsoleProgressReporter();
    }

    @Provides
    @Singleton
    WorkflowService provideWorkflowService(WorkflowServiceImpl impl) {
        return impl;
    }
}
