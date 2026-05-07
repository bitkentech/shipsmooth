package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "ledger-resolver-complete",
        description = "Signal that the Lead Agent's resolver subagent has finished (unblocks integrate).")
public class LedgerResolverCompleteCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task", required = true)
    private int task;

    @Override
    public Integer call() throws Exception {
        LedgerService ledger = new LedgerService(Paths.get("."));
        ledger.ensureLedgerFile();
        ledger.record(Event.forTask(
                EventType.RESOLVER_COMPLETE,
                String.valueOf(task),
                null,
                null,
                Map.of("task_id", String.valueOf(task))));
        System.out.println("Resolver complete recorded for task " + task);
        return 0;
    }
}