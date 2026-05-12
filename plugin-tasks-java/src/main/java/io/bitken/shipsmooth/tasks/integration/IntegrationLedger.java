package io.bitken.shipsmooth.tasks.integration;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class IntegrationLedger {

    private final LedgerService ledger;
    private final int planId;

    public IntegrationLedger(LedgerService ledger, int planId) {
        this.ledger = ledger;
        this.planId = planId;
    }

    public void recordIntegrationPlan(List<Integer> orderedTaskIds, String integrationBranch)
            throws IOException {
        ledger.record(Event.system(EventType.INTEGRATION_PLAN, null,
                String.join(",", orderedTaskIds.stream().map(String::valueOf).toList()),
                Map.of("plan_id", String.valueOf(planId), "integration_branch", integrationBranch)));
    }

    public void recordPatchIntegrated(int taskId, String integrationCommitSha, String agentWorkSha)
            throws IOException {
        ledger.record(Event.forTask(EventType.PATCH_INTEGRATED, String.valueOf(taskId), null,
                integrationCommitSha,
                Map.of("agent_work_sha", agentWorkSha)));
    }

    public void recordIntegrationFailure(int taskId, int attempts, String reason)
            throws IOException {
        ledger.record(Event.forTask(EventType.INTEGRATION_FAILURE, String.valueOf(taskId), null,
                reason,
                Map.of("attempts", String.valueOf(attempts))));
    }

    public void recordIntegrationComplete(String tipSha, List<Integer> orderedTaskIds)
            throws IOException {
        ledger.record(Event.system(EventType.INTEGRATION_COMPLETE, tipSha,
                String.join(",", orderedTaskIds.stream().map(String::valueOf).toList()),
                Map.of("plan_id", String.valueOf(planId))));
    }
}
