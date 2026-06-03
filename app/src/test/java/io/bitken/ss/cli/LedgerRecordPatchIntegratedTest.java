package io.bitken.ss.cli;

import io.bitken.ss.cli.Shipsmooth;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ExperimentalMode;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

public class LedgerRecordPatchIntegratedTest {

    private final AppComponents app = DaggerAppComponents.builder()
            .servicesModule(new ServicesModule(Paths.get("."), new ExperimentalMode(true)))
            .build();

    @Test
    void writesPatchIntegratedEventWithRecoveryFlag() throws Exception {
        EventLedger ledger = new EventLedger(Paths.get("."));
        ledger.ensureLedgerFile();

        String[] args = {
                "--enable-experimental", "ledger", "record-patch-integrated",
                "--plan", "993",
                "--task", "7",
                "--commit", "abc1234",
                "--agent-work-sha", "def5678"
        };
        int exit = new Shipsmooth(app, args).execute();

        assertEquals(0, exit);

        Event ev = ledger.findLastEvent("7", EventType.PATCH_INTEGRATED);
        assertNotNull(ev, "PATCH_INTEGRATED event should be written");
        assertEquals("abc1234", ev.payload());
        assertEquals("def5678", ev.metadata().get("agent_work_sha"));
        assertEquals("true", ev.metadata().get("recovery"));
    }
}