package io.bitken.ss.cli;
import io.bitken.ss.conf.ShipsmoothDataLocator;

import io.bitken.ss.cli.Shipsmooth;
import io.bitken.ss.cli.conf.ExperimentalModeParser;
import io.bitken.ss.conf.AppComponents;
import io.bitken.ss.conf.DaggerAppComponents;
import io.bitken.ss.conf.ServicesModule;
import io.bitken.ss.ledger.Event;
import io.bitken.ss.ledger.EventType;
import io.bitken.ss.ledger.EventLedger;
import io.bitken.ss.gw.TaskStore;
import io.bitken.ss.jaxb.PlanTasks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ShipsmoothIntegrationTest {

    private static final int PLAN_NUM = 994;
    private final File planDir = new File(".agents/plans");
    private final File xmlFile = new File(planDir, "plan-" + PLAN_NUM + "-tasks.xml");
    private final File mdFile = new File(planDir, "plan-" + PLAN_NUM + ".md");

    @BeforeEach
    public void setUp() throws Exception {
        planDir.mkdirs();
        Files.writeString(mdFile.toPath(), "### Task 1: CLI test task [High]\n");

        List<TaskStore.Task> tasks = List.of(new TaskStore.Task(1, "CLI test task", "high"));
        TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        PlanTasks planTasks = xmlService.generatePlanTasks(PLAN_NUM, "plan-" + PLAN_NUM + "-v1", tasks);
        xmlService.writePlanTasks(planTasks, xmlFile);

        EventLedger ledger = new EventLedger(Paths.get("."));
        ledger.ensureLedgerFile();
    }

    /**
     * One-shot CLI bound to these args, seeded from the flag in args.
     * Registration and the services' ledger gate both follow the flag.
     */
    private int run(String... args) {
        AppComponents app = DaggerAppComponents.builder()
                .servicesModule(new ServicesModule(Paths.get("."), ExperimentalModeParser.fromArgs(args)))
                .build();
        return new Shipsmooth(app, args).execute();
    }

    @AfterEach
    public void tearDown() {
        xmlFile.delete();
        mdFile.delete();
    }

    @Test
    public void cliHelpRuns() {
        assertEquals(0, run("--help"));
    }

    @Test
    public void updateStatusViaCliRecordsLedgerEntry() throws Exception {
        EventLedger ledger = new EventLedger(Paths.get("."));
        int before = ledger.readHashes().size();

        int exit = run("--enable-experimental", "task", "status", "--plan", String.valueOf(PLAN_NUM), "--task", "1", "--status", "agent-coded");
        assertEquals(0, exit);

        List<String> hashes = ledger.readHashes();
        assertEquals(before + 1, hashes.size());
        Event ev = ledger.readEvent(hashes.get(hashes.size() - 1));
        assertEquals(EventType.STATUS_UPDATED, ev.eventType());
        assertEquals("1", ev.taskId());
        assertTrue(ev.payload().contains("agent-coded"));
    }

    // plan-75 Task 3: the ledger group is experimental, so it registers only under
    // --enable-experimental (mirroring worker/integrate). Its leaves work once the
    // flag is set.
    @Test
    public void ledgerListViaCliExitsZero() throws Exception {
        int exit = run("--enable-experimental", "ledger", "list");
        assertEquals(0, exit);
    }

    @Test
    public void ledgerVerifyViaCliExitsZero() throws Exception {
        int exit = run("--enable-experimental", "ledger", "verify");
        assertEquals(0, exit);
    }

    // plan-75 Task 3: the positive gating proof — without --enable-experimental the
    // ledger group is not registered, so picocli rejects it (exit 2), exactly like
    // integrate/worker. This is what keeps ledger out of the prod --help surface.
    @Test
    public void ledgerRefusedWithoutFlag() {
        int exit = run("ledger", "list");
        assertEquals(2, exit,
            "experimental 'ledger' group must be refused (exit 2) without --enable-experimental");
    }

    @Test
    public void experimentalSubcommandRefusedWithoutFlag() {
        // Without --enable-experimental, picocli should reject 'integrate' as
        // an unmatched argument (exit code 2, picocli's standard "unknown"
        // exit code). Today this passes accidentally because integrate's
        // sub-spec rejects --help; after plan-41 it passes because integrate
        // is not registered at all.
        int exit = run("integrate", "--plan", "1");
        assertEquals(2, exit,
            "experimental subcommand 'integrate' must be refused (exit 2) without --enable-experimental");
    }

    @Test
    public void experimentalSubcommandRunsWithFlag() {
        // With --enable-experimental, picocli should successfully parse
        // 'integrate'. We pass --help to short-circuit before integrate's
        // actual side effects. (After plan-41, integrate's sub-spec needs
        // its own --help support OR we rely on picocli's standard help mixin
        // being applied to it.) For now, just assert that the top-level
        // parse accepts the subcommand name without complaining.
        int exit = run("--enable-experimental", "integrate", "--plan", "0");
        // 'integrate' should at least be recognised as a subcommand; the
        // command itself may return non-zero from --plan 0, but a return of
        // 2 (unmatched argument) means the flag isn't working.
        assertNotEquals(2, exit,
            "experimental subcommand 'integrate' must be recognised when --enable-experimental is set");
    }

    @Test
    public void addCommentViaCliMutatesXmlAndRecordsLedgerEntry() throws Exception {
        EventLedger ledger = new EventLedger(Paths.get("."));
        int before = ledger.readHashes().size();

        int exit = run("--enable-experimental", "task", "comment", "--plan", String.valueOf(PLAN_NUM), "--task", "1", "--message", "via Shipsmooth");
        assertEquals(0, exit);

        TaskStore xmlService = new TaskStore(new ShipsmoothDataLocator(Paths.get(".")));
        PlanTasks planTasks = xmlService.readPlanTasks(xmlFile);
        var comments = planTasks.getTasks().getTask().get(0).getComments().getComment();
        assertEquals(1, comments.size());
        assertEquals("via Shipsmooth", comments.get(0).getMessage());

        List<String> hashes = ledger.readHashes();
        assertEquals(before + 1, hashes.size());
        Event ev = ledger.readEvent(hashes.get(hashes.size() - 1));
        assertEquals(EventType.COMMENT_ADDED, ev.eventType());
        assertEquals("1", ev.taskId());
    }
}
