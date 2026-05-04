package io.bitken.shipsmooth.tasks.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LedgerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    public void recordAndReadEvent() throws Exception {
        LedgerService ledger = new LedgerService(tempDir);
        ledger.ensureLedgerFile();

        Event event = Event.forTask(EventType.STATUS_UPDATED, "3", null, "status=agent-coded", null);
        String sha1 = ledger.record(event);

        assertNotNull(sha1);
        assertEquals(40, sha1.length());

        List<String> hashes = ledger.readHashes();
        assertEquals(1, hashes.size());
        assertEquals(sha1, hashes.get(0));

        Event read = ledger.readEvent(sha1);
        assertEquals(EventType.STATUS_UPDATED, read.eventType());
        assertEquals("3", read.taskId());
        assertEquals("status=agent-coded", read.payload());
    }

    @Test
    public void objectStoreSha1MatchesGitHashObject() throws Exception {
        ObjectStore store = new ObjectStore(tempDir);

        byte[] data = "hello ledger".getBytes(StandardCharsets.UTF_8);
        String sha1 = store.writeObject(data);

        // git hash-object format: "blob <len>\0<data>"
        byte[] header = ("blob " + data.length + "\0").getBytes(StandardCharsets.UTF_8);
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(header);
        md.update(data);
        String expected = HexFormat.of().formatHex(md.digest());

        assertEquals(expected, sha1);

        // round-trip
        byte[] read = store.readObject(sha1);
        assertArrayEquals(data, read);
    }

    @Test
    public void multipleAppendsMaintainOrder() throws Exception {
        LedgerService ledger = new LedgerService(tempDir);
        ledger.ensureLedgerFile();

        String sha1 = ledger.record(Event.forTask(EventType.TASK_REGISTRATION, "1", null, "task 1", null));
        String sha2 = ledger.record(Event.forTask(EventType.COMMENT_ADDED, "1", null, "comment", null));
        String sha3 = ledger.record(Event.forTask(EventType.STATUS_UPDATED, "1", null, "done", null));

        List<String> hashes = ledger.readHashes();
        assertEquals(List.of(sha1, sha2, sha3), hashes);
    }

    @Test
    public void verifyLedgerReturnsTimeline() throws Exception {
        LedgerService ledger = new LedgerService(tempDir);
        ledger.ensureLedgerFile();

        ledger.record(Event.forTask(EventType.TASK_REGISTRATION, "1", null, "registered", null));
        ledger.record(Event.forTask(EventType.STATUS_UPDATED, "1", null, "in-progress", null));

        List<Event> timeline = ledger.verifyLedger();
        assertEquals(2, timeline.size());
        assertEquals(EventType.TASK_REGISTRATION, timeline.get(0).eventType());
        assertEquals(EventType.STATUS_UPDATED, timeline.get(1).eventType());
    }

    @Test
    public void writeObjectIdempotent() throws Exception {
        ObjectStore store = new ObjectStore(tempDir);
        byte[] data = "idempotency check".getBytes(StandardCharsets.UTF_8);

        String sha1a = store.writeObject(data);
        String sha1b = store.writeObject(data);

        assertEquals(sha1a, sha1b);

        Path obj = tempDir.resolve(".agents/objects")
                .resolve(sha1a.substring(0, 2))
                .resolve(sha1a.substring(2));
        assertTrue(Files.exists(obj));
    }
}
