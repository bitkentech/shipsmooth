package io.bitken.ss.workflow.integration;

import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates with the Lead Agent via the ledger rather than stdin/stdout.
 *
 * Protocol:
 *   1. Write RESOLVER_REQUESTED event (prompt as payload, worktree + task_id in metadata).
 *   2. Poll ledger for a matching RESOLVER_COMPLETE event (same task_id).
 *   3. On timeout, throw with an actionable message.
 *
 * The Lead Agent arms a Monitor on .agents/ledger.jsonl before running integrate,
 * detects the RESOLVER_REQUESTED line, performs the Agent tool call, then writes
 * RESOLVER_COMPLETE via `ledger-resolver-complete --plan N --task id`.
 */
public class LedgerSubagentRunner implements SubagentRunner {

    static final long POLL_INTERVAL_MS = 500;
    static final long TIMEOUT_MINUTES = 30;

    private final EventLedger ledger;
    private final String worktreePath;
    private final int taskId;
    private int attemptCounter = 0;

    public LedgerSubagentRunner(EventLedger ledger, String worktreePath, int taskId) {
        this.ledger = ledger;
        this.worktreePath = worktreePath;
        this.taskId = taskId;
    }

    @Override
    public void run(String prompt) throws Exception {
        attemptCounter++;
        String taskIdStr = String.valueOf(taskId);

        ledger.record(Event.forTask(
                EventType.RESOLVER_REQUESTED,
                taskIdStr,
                null,
                prompt,
                Map.of("worktree", worktreePath, "attempt", String.valueOf(attemptCounter))));

        System.out.println("integrate: RESOLVER_REQUESTED written to ledger for task " + taskId
                + " (attempt " + attemptCounter + "). Waiting for Lead Agent to call ledger-resolver-complete...");

        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES);
        // Remember the timestamp of the request so we only accept a COMPLETE written after it
        long requestedAt = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            Event complete = ledger.findLastEvent(taskIdStr, EventType.RESOLVER_COMPLETE);
            if (complete != null && complete.timestamp().toEpochMilli() >= requestedAt) {
                System.out.println("integrate: RESOLVER_COMPLETE received for task " + taskId);
                return;
            }
            //noinspection BusyWait
            Thread.sleep(POLL_INTERVAL_MS);
        }

        throw new IllegalStateException(
                "integrate: timed out after " + TIMEOUT_MINUTES + " minutes waiting for Lead Agent "
                + "to call `ledger-resolver-complete --plan <N> --task " + taskId + "`. "
                + "The RESOLVER_REQUESTED event is in the ledger — check that Monitor is armed on "
                + ".agents/ledger.jsonl before running integrate.");
    }
}