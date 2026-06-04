package io.bitken.ss.workflow;

/**
 * Typed error codes raised by {@link WorkflowService} operations.
 *
 * <p>Each code maps to a stable CLI exit code via {@link #exitCode()} so that
 * scripts can branch on outcome without parsing error messages. Codes are
 * added as migrations land — task-specific codes are introduced alongside
 * the methods that throw them.
 */
public enum WorkflowErrorCode {

    /** Catch-all for unexpected failures inside the service layer. */
    INTERNAL_ERROR(1),

    /** {@code initializeWorker} called for a task whose worktree already exists. */
    WORKTREE_ALREADY_EXISTS(1),

    /** {@code finalizeWorker} called but the worktree directory is missing. */
    WORKTREE_MISSING(1),

    /** {@code finalizeWorker} detected commits in the worktree (contract violation). */
    SUBAGENT_COMMITTED_IN_WORKTREE(1),

    /** {@code finalizeWorker} found no changes in the worktree to commit. */
    EMPTY_DIFF(1),

    /** {@code runIntegration} found no {@code COMMIT_RECORDED} events for the plan. */
    NOTHING_TO_INTEGRATE(1),

    /** {@code runIntegration} could not compute a merge order (cycle / unknown dep). */
    INTEGRATION_ORDER_FAILED(1),

    /** {@code runIntegration} found an existing integration worktree but {@code --force} was not set. */
    INTEGRATION_WORKTREE_PRESENT(1);

    private final int exitCode;

    WorkflowErrorCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}