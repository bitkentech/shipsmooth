package io.bitken.ss.conf;

import dagger.Component;
import io.bitken.ss.git.WorktreeService;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.service.PlanService;
import io.bitken.ss.service.XmlService;
import io.bitken.ss.workflow.WorkflowService;
import io.bitken.ss.workflow.WorkflowServiceImpl;
import jakarta.inject.Singleton;

@Singleton
@Component(modules = ServicesModule.class)
public interface AppComponents {
    XmlService xmlService();
    EventLedger eventLedger();
    PlanService planService();
    WorktreeService worktreeService();
    WorkflowService workflowService();
    WorkflowServiceImpl workflowServiceImpl();
}
