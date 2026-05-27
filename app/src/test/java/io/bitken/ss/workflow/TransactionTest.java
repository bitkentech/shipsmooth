package io.bitken.ss.workflow;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Transaction}. The Transaction primitive is small and
 * its behaviour is testable in isolation without driving a full
 * {@link WorkflowServiceImpl}.
 */
class TransactionTest {

    @Test
    void commit_clearsRollbackStack() {
        Transaction tx = new Transaction();
        boolean[] ran = {false};
        tx.register("step", () -> ran[0] = true);
        tx.commit();
        tx.rollback(); // should be a no-op after commit
        assertFalse(ran[0], "registered rollback must not run after commit");
    }

    @Test
    void rollback_runsActionsInReverseOrder() {
        Transaction tx = new Transaction();
        List<String> calls = new ArrayList<>();
        tx.register("first", () -> calls.add("first"));
        tx.register("second", () -> calls.add("second"));
        tx.register("third", () -> calls.add("third"));
        tx.rollback();
        assertEquals(List.of("third", "second", "first"), calls);
    }

    @Test
    void rollback_continuesAfterFailingStep() {
        Transaction tx = new Transaction();
        List<String> calls = new ArrayList<>();
        tx.register("first", () -> calls.add("first"));
        tx.register("failing", () -> { throw new RuntimeException("simulated"); });
        tx.register("third", () -> calls.add("third"));
        tx.rollback();
        // third pops first (LIFO), then failing throws but is swallowed, then first runs.
        assertEquals(List.of("third", "first"), calls);
    }

    @Test
    void rollback_isIdempotent() {
        Transaction tx = new Transaction();
        int[] calls = {0};
        tx.register("once", () -> calls[0]++);
        tx.rollback();
        tx.rollback();
        assertEquals(1, calls[0], "second rollback must do nothing");
    }
}