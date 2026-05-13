package io.bitken.shipsmooth.tasks.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

public class LedgerWatchCommand implements Callable<Integer> {

    static final long POLL_INTERVAL_MS = 300;

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final CommandSpec spec;

    public LedgerWatchCommand() {
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.usageMessage().description("Block until a RESOLVER_REQUESTED ledger event appears, then print it.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--repo").description("Repo root (default: current directory)").type(String.class).build());
        spec.addOption(OptionSpec.builder("--timeout-seconds").defaultValue("1800").description("Give up after N seconds (default: 1800)").type(long.class).build());
        spec.addOption(OptionSpec.builder("--after").defaultValue("0").description("Ignore events at indices 0..N-1").type(int.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String repo = pr.matchedOptionValue("repo", null);
        long timeoutSeconds = pr.matchedOptionValue("timeout-seconds", 1800L);
        int after = pr.matchedOptionValue("after", 0);

        Path repoRoot = repo != null ? Paths.get(repo) : Paths.get(".");
        LedgerService ledger = new LedgerService(repoRoot);
        Path ledgerPath = ledger.ledgerPath();

        Files.createDirectories(ledgerPath.getParent());
        if (!Files.exists(ledgerPath)) {
            Files.createFile(ledgerPath);
        }

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        int seenCount = after;

        while (System.currentTimeMillis() < deadline) {
            List<String> hashes = ledger.readHashes();
            List<Event> allEvents = new ArrayList<>(hashes.size());
            for (String h : hashes) allEvents.add(ledger.readEvent(h));

            for (int i = seenCount; i < allEvents.size(); i++) {
                Event ev = allEvents.get(i);
                if (ev.eventType() == EventType.INTEGRATION_COMPLETE || ev.eventType() == EventType.INTEGRATION_FAILURE) {
                    System.err.println("ledger-watch: integrate finished (" + ev.eventType() + "), exiting.");
                    return 0;
                }

                if (ev.eventType() == EventType.RESOLVER_REQUESTED) {
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
            Thread.sleep(POLL_INTERVAL_MS);
        }

        System.err.printf("ledger-watch: timed out after %d seconds waiting for RESOLVER_REQUESTED event.%n", timeoutSeconds);
        return 1;
    }
}