package io.bitken.ss.workflow;

/**
 * Checked exception raised by {@link WorkflowService} methods.
 *
 * <p>Carries a typed {@link WorkflowErrorCode} so callers (CLI commands today,
 * other delivery mechanisms later) can map outcomes to exit codes or
 * presentation without string-parsing the message.
 */
public class WorkflowException extends Exception {

    private final WorkflowErrorCode errorCode;

    public WorkflowException(WorkflowErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public WorkflowException(WorkflowErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public WorkflowErrorCode errorCode() {
        return errorCode;
    }

    /** Convenience accessor for {@code errorCode().exitCode()}. */
    public int exitCode() {
        return errorCode.exitCode();
    }
}