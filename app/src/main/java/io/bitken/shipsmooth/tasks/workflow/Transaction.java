package io.bitken.shipsmooth.tasks.workflow;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * Narrow unit-of-work helper used inside {@link WorkflowServiceImpl} when a
 * single logical operation spans multiple subsystems (git + ledger + XML).
 *
 * <p>Not a generic transaction framework — it has no isolation, no two-phase
 * commit, no nesting. It is a stack of {@code Runnable} inverses: each
 * successful step registers a rollback action, and {@link #rollback()} runs
 * them in reverse order on failure. {@link #commit()} clears the stack on
 * success.
 *
 * <p>Used today only by {@code finalizeWorker}. If a second use site appears,
 * promote this to its own package; for now one file, one consumer.
 */
public final class Transaction {

    private final Deque<RollbackStep> rollbackStack = new ArrayDeque<>();
    private final Consumer<String> warnSink;

    public Transaction() {
        this(System.err::println);
    }

    public Transaction(Consumer<String> warnSink) {
        this.warnSink = warnSink;
    }

    /**
     * Register an inverse action to run if the transaction is rolled back.
     * Call this after the corresponding forward step has succeeded.
     */
    public void register(String description, RollbackAction action) {
        rollbackStack.push(new RollbackStep(description, action));
    }

    /** Discard all registered rollback actions. Call on success. */
    public void commit() {
        rollbackStack.clear();
    }

    /**
     * Run registered rollback actions in reverse order. Each is best-effort:
     * a failure in one inverse is reported via the warnSink but does not stop the rest.
     */
    public void rollback() {
        while (!rollbackStack.isEmpty()) {
            RollbackStep step = rollbackStack.pop();
            try {
                step.action.run();
            } catch (Exception e) {
                warnSink.accept("Transaction rollback step failed: "
                        + step.description + " — " + e.getMessage());
            }
        }
    }

    @FunctionalInterface
    public interface RollbackAction {
        void run() throws Exception;
    }

    private record RollbackStep(String description, RollbackAction action) {}
}
