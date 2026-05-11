package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Recovery command: manually records a PATCH_INTEGRATED event when integrate died
 * mid-resolver and the task was resolved by hand in the integration worktree.
 * The recovery=true metadata flag distinguishes these from normally-written events.
 */
@Command(name = "ledger-record-patch-integrated",
        description = "Write a PATCH_INTEGRATED event directly to the ledger (recovery use only).")
public class LedgerRecordPatchIntegratedCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private int task;

    @Option(names = "--commit", required = true,
            description = "Integration branch commit SHA (the manual commit made in the worktree).")
    private String commit;

    @Option(names = "--agent-work-sha", required = true,
            description = "Tip SHA of the agent-work/{task} branch.")
    private String agentWorkSha;

    @Override
    public Integer call() throws Exception {
        LedgerService ledger = new LedgerService(Paths.get("."));
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(
                EventType.PATCH_INTEGRATED, String.valueOf(task), null, commit,
                Map.of("agent_work_sha", agentWorkSha, "recovery", "true")
        ));
        System.out.println("PATCH_INTEGRATED written for task " + task + " (recovery=true, commit=" + commit + ")");
        return 0;
    }
}