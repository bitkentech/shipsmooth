package io.bitken.shipsmooth.tasks.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Blocks until a RESOLVER_REQUESTED event appears in the ledger, then prints its JSON and exits 0.
 * Replaces the fragile shell pipeline (tail -f | while read | grep) used in earlier SKILL versions.
 */
@Command(name = "ledger-watch", description = "Block until a RESOLVER_REQUESTED ledger event appears, then print it.")
public class LedgerWatchCommand implements Callable<Integer> {

    static final long POLL_INTERVAL_MS = 300;

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--repo", description = "Repo root (default: current directory)")
    private String repo;

    @Option(names = "--timeout-seconds", description = "Give up after N seconds (default: 1800)")
    private long timeoutSeconds = 1800;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public Integer call() throws Exception {
        Path repoRoot = repo != null ? Paths.get(repo) : Paths.get(".");
        LedgerService ledger = new LedgerService(repoRoot);
        Path ledgerPath = ledger.ledgerPath();

        // Ensure ledger file exists so we don't fail on first poll
        Files.createDirectories(ledgerPath.getParent());
        if (!Files.exists(ledgerPath)) {
            Files.createFile(ledgerPath);
        }

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        int seenCount = 0;

        while (System.currentTimeMillis() < deadline) {
            List<String> hashes = ledger.readHashes();
            // Read all events once so we can check for matching RESOLVER_COMPLETE inline.
            // Only scan hashes we haven't seen yet to avoid re-firing on past cycles.
            List<Event> allEvents = new java.util.ArrayList<>(hashes.size());
            for (String h : hashes) allEvents.add(ledger.readEvent(h));

            for (int i = seenCount; i < allEvents.size(); i++) {
                Event ev = allEvents.get(i);
                if (ev.eventType() == EventType.INTEGRATION_COMPLETE || ev.eventType() == EventType.INTEGRATION_FAILURE) {
                    System.err.println("ledger-watch: integrate finished (" + ev.eventType() + "), exiting.");
                    return 0; // Integration finished, so we gracefully exit without a payload.
                }

                if (ev.eventType() == EventType.RESOLVER_REQUESTED) {
                    // Skip if a RESOLVER_COMPLETE for the same task already exists after this request
                    boolean alreadyResolved = allEvents.stream().anyMatch(e ->
                            e.eventType() == EventType.RESOLVER_COMPLETE
                            && ev.taskId() != null && ev.taskId().equals(e.taskId())
                            && e.timestamp().compareTo(ev.timestamp()) >= 0);
                    if (alreadyResolved) continue;
                    System.out.println(mapper.writeValueAsString(ev));
                    return 0;
                }
            }
            seenCount = allEvents.size();
            //noinspection BusyWait
            Thread.sleep(POLL_INTERVAL_MS);
        }

        System.err.printf("ledger-watch: timed out after %d seconds waiting for RESOLVER_REQUESTED event.%n", timeoutSeconds);
        System.err.println("Check that integrate is running and the ledger is being written to.");
        return 1;
    }
}