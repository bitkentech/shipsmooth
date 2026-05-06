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
 * Emergency recovery: reconstructs a COMMIT_RECORDED ledger event when the ledger
 * was wiped (e.g. by git reset --hard) and worker-cleanup has already removed the
 * worktree directory, making worker-finish impossible.
 */
@Command(name = "ledger-record-commit",
        description = "Write a COMMIT_RECORDED event directly to the ledger (recovery use only).")
public class LedgerRecordCommitCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private int task;

    @Option(names = "--commit", required = true)
    private String commit;

    @Option(names = "--branch", required = true,
            description = "Must start with agent-work/ to write integration_mode=worktree.")
    private String branch;

    @Override
    public Integer call() throws Exception {
        String integrationMode = branch.startsWith("agent-work/") ? "worktree" : "direct";
        Map<String, String> meta = Map.of(
                "branch", branch,
                "commit_sha", commit,
                "integration_mode", integrationMode
        );
        LedgerService ledger = new LedgerService(Paths.get("."));
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(EventType.COMMIT_RECORDED, String.valueOf(task), commit, commit, meta));
        System.out.println("COMMIT_RECORDED written for task " + task + " (integration_mode=" + integrationMode + ")");
        return 0;
    }
}