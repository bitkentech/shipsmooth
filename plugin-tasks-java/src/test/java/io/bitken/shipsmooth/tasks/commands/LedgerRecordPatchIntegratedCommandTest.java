package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.TasksCli;
import io.bitken.shipsmooth.tasks.ledger.Event;
import io.bitken.shipsmooth.tasks.ledger.EventType;
import io.bitken.shipsmooth.tasks.ledger.LedgerService;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class LedgerRecordPatchIntegratedCommandTest {

    @Test
    void writesPatchIntegratedEventWithRecoveryFlag() throws Exception {
        LedgerService ledger = new LedgerService(Paths.get("."));
        ledger.ensureLedgerFile();

        int exit = new CommandLine(new TasksCli()).execute(
                "ledger-record-patch-integrated",
                "--plan", "993",
                "--task", "7",
                "--commit", "abc1234",
                "--agent-work-sha", "def5678"
        );

        assertEquals(0, exit);

        Event ev = ledger.findLastEvent("7", EventType.PATCH_INTEGRATED);
        assertNotNull(ev, "PATCH_INTEGRATED event should be written");
        assertEquals("abc1234", ev.payload());
        assertEquals("def5678", ev.metadata().get("agent_work_sha"));
        assertEquals("true", ev.metadata().get("recovery"));
    }
}