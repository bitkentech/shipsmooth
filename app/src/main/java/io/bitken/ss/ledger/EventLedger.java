package io.bitken.ss.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only ledger at {@code .agents/ledger.jsonl}.
 * Each line is a SHA-1 hash of a JSON event blob stored in {@code .agents/objects/}.
 * Thread-safe via OS advisory lock + JVM monitor.
 */
public class EventLedger {

    private final Path ledgerPath;
    private final ObjectStore store;
    private final ObjectMapper mapper;
    private final Object appendMonitor = new Object();

    public EventLedger(Path repoRoot) {
        this.store = new ObjectStore(repoRoot);
        this.ledgerPath = repoRoot.resolve(".agents").resolve("ledger.jsonl");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Renders an event back to the canonical JSON representation used for storage and inspection. */
    public String renderEventJson(Event event) throws IOException {
        return mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(event);
    }

    public Path ledgerPath() {
        return ledgerPath;
    }

    public void ensureLedgerFile() throws IOException {
        Files.createDirectories(ledgerPath.getParent());
        if (!Files.exists(ledgerPath)) {
            Files.createFile(ledgerPath);
        }
    }

    public String record(Event event) throws IOException {
        byte[] json = mapper.writeValueAsBytes(event);
        String sha1 = store.writeObject(json);
        appendHash(sha1);
        return sha1;
    }

    private void appendHash(String sha1) throws IOException {
        byte[] line = (sha1 + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (appendMonitor) {
            try (FileChannel ch = FileChannel.open(ledgerPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 FileLock lock = ch.lock()) {
                ch.write(ByteBuffer.wrap(line));
                ch.force(true);
                lock.release();
            }
        }
    }

    public List<String> readHashes() throws IOException {
        if (!Files.exists(ledgerPath)) return List.of();
        try (var lines = Files.lines(ledgerPath, StandardCharsets.UTF_8)) {
            return lines.map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    public Event readEvent(String sha1) throws IOException {
        byte[] bytes = store.readObject(sha1);
        return mapper.readValue(bytes, Event.class);
    }

    /** Returns the most recent event of the given type for the given task, or null if none. */
    public Event findLastEvent(String taskId, EventType type) throws IOException {
        List<String> hashes = readHashes();
        Event result = null;
        for (String hash : hashes) {
            Event ev = readEvent(hash);
            if (type == ev.eventType() && taskId.equals(ev.taskId())) {
                result = ev;
            }
        }
        return result;
    }

    /**
     * Returns the 0-based index of the last event whose type matches and whose metadata
     * contains all entries in {@code metadataMatch}, or -1 if none found.
     */
    public int findLastEventIndex(EventType type, java.util.Map<String, String> metadataMatch)
            throws IOException {
        List<String> hashes = readHashes();
        int result = -1;
        for (int i = 0; i < hashes.size(); i++) {
            Event ev = readEvent(hashes.get(i));
            if (type == ev.eventType() && metadataMatches(ev, metadataMatch)) {
                result = i;
            }
        }
        return result;
    }

    /** Returns true if every key/value in {@code filter} is present in the event's metadata. */
    private boolean metadataMatches(Event ev, java.util.Map<String, String> filter) {
        return ev.metadata().entrySet().containsAll(filter.entrySet());
    }

    /**
     * Returns the most recent event of the given type for the given task whose ledger index
     * is strictly greater than {@code afterIndex}, or null if none.
     */
    public Event findLastEventAfter(String taskId, EventType type, int afterIndex) throws IOException {
        List<String> hashes = readHashes();
        Event result = null;
        for (int i = afterIndex + 1; i < hashes.size(); i++) {
            Event ev = readEvent(hashes.get(i));
            if (type == ev.eventType() && taskId.equals(ev.taskId())) {
                result = ev;
            }
        }
        return result;
    }

    public List<Event> verifyLedger() throws IOException {
        List<String> hashes = readHashes();
        List<Event> timeline = new ArrayList<>(hashes.size());
        System.err.printf("=== Reconstructed Timeline (%d entries from ledger.jsonl) ===%n", hashes.size());
        for (int i = 0; i < hashes.size(); i++) {
            String hash = hashes.get(i);
            Event ev = readEvent(hash);
            timeline.add(ev);
            String taskLabel = ev.taskId() != null ? ev.taskId() : "<system>";
            System.err.printf("  [%02d] %s %s | %s | %s%n",
                    i,
                    hash.substring(0, 8),
                    ev.eventType(),
                    taskLabel,
                    ev.payload() == null ? "" : ev.payload().lines().findFirst().orElse(""));
        }
        return timeline;
    }
}
