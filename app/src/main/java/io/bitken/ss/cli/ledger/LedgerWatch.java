package io.bitken.ss.cli.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.Callable;

public class LedgerWatch implements Callable<Integer>, HasSpec, io.bitken.ss.conf.FeatureFlags {
    @Override public boolean isExperimental() { return true; }

    static final long POLL_INTERVAL_MS = 300;

    private final ObjectMapper mapper;
    private final CommandSpec spec;

    @Inject
    public LedgerWatch() {
        this.mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.spec.name("ledger-watch");
        this.spec.usageMessage().description("Block until a RESOLVER_REQUESTED ledger event appears, then print it.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--repo").description("Repo root (default: current directory)").type(String.class).build());
        this.spec.addOption(OptionSpec.builder("--timeout-seconds").defaultValue("1800").description("Give up after N seconds (default: 1800)").type(long.class).build());
        this.spec.addOption(OptionSpec.builder("--after").defaultValue("0").description("Ignore events at indices 0..N-1").type(int.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        var plan = (int) pr.matchedOption("plan").getValue();
        String repo = pr.matchedOptionValue("repo", null);
        var timeoutSeconds = pr.matchedOptionValue("timeout-seconds", 1800L);
        var after = pr.matchedOptionValue("after", 0);

        var repoRoot = repo != null ? Paths.get(repo) : Paths.get(".");
        var ledger = new EventLedger(repoRoot);
        var ledgerPath = ledger.ledgerPath();

        Files.createDirectories(ledgerPath.getParent());
        if (!Files.exists(ledgerPath)) {
            Files.createFile(ledgerPath);
        }

        var deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        var seenCount = after;

        while (System.currentTimeMillis() < deadline) {
            var hashes = ledger.readHashes();
            var allEvents = new ArrayList<Event>(hashes.size());
            for (var h : hashes) allEvents.add(ledger.readEvent(h));

            for (var i = seenCount; i < allEvents.size(); i++) {
                var ev = allEvents.get(i);
                if (ev.eventType() == EventType.INTEGRATION_COMPLETE || ev.eventType() == EventType.INTEGRATION_FAILURE) {
                    System.err.println("ledger-watch: integrate finished (" + ev.eventType() + "), exiting.");
                    return 0;
                }

                if (ev.eventType() == EventType.RESOLVER_REQUESTED) {
                    var alreadyResolved = allEvents.stream().anyMatch(e ->
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
