package io.bitken.shipsmooth.tasks.di;

import dagger.Component;
import io.bitken.shipsmooth.tasks.git.WorktreeService;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import io.bitken.shipsmooth.tasks.service.XmlService;
import io.bitken.shipsmooth.tasks.workflow.WorkflowService;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import jakarta.inject.Singleton;

@Singleton
@Component(modules = ServicesModule.class)
public interface AppComponents {
    XmlService xmlService();
    LedgerService ledgerService();
    WorktreeService worktreeService();
    WorkflowService workflowService();
    WorkflowServiceImpl workflowServiceImpl();
}
