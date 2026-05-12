package io.bitken.shipsmooth.tasks.workflow;

/**
 * Outcome of a {@link WorkflowService#runIntegration} call.
 *
 * <p>{@code success} reflects the overall result. {@code integrationTipSha} and
 * {@code fastForwardCommand} are set when integration completes successfully;
 * both are null otherwise.
 */
public record IntegrationResult(
        boolean success,
        String integrationTipSha,
        String fastForwardCommand
) {
    public static IntegrationResult ok(String tipSha, String ffCmd) {
        return new IntegrationResult(true, tipSha, ffCmd);
    }

    public static IntegrationResult failed() {
        return new IntegrationResult(false, null, null);
    }
}