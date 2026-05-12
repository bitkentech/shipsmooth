package io.bitken.shipsmooth.tasks.workflow;

import io.bitken.shipsmooth.tasks.integration.IntegrationDefaults;
import io.bitken.shipsmooth.tasks.integration.Resolver;

/**
 * Parameter object for {@link WorkflowService#runIntegration}.
 *
 * <p>Mutable builder-style for now; callers set the fields they care about and
 * leave the rest at defaults. The CLI thin shell maps PicoCLI options to this.
 */
public class IntegrationOptions {

    /** Branch the integration tip will eventually fast-forward into. Null → auto-detect HEAD. */
    private String taskBranch;

    /** Verify command run inside the integration worktree. Null → {@code IntegrationDefaults.VERIFY_CMD}. */
    private String verifyCmd;

    /** Per-task resolver attempts before giving up. */
    private int maxLlmIterations = IntegrationDefaults.MAX_LLM_ITERATIONS;

    /** Across-tasks failure budget before integrate aborts. */
    private int maxTotalFailures = IntegrationDefaults.MAX_TOTAL_FAILURES;

    /** Delete any existing integration worktree/branch and start fresh. */
    private boolean force;

    /** Test seam: override the resolver implementation. Null → real LLM-backed resolver. */
    private ResolverFactory resolverFactory;

    public String taskBranch() { return taskBranch; }
    public IntegrationOptions taskBranch(String v) { this.taskBranch = v; return this; }

    public String verifyCmd() { return verifyCmd; }
    public IntegrationOptions verifyCmd(String v) { this.verifyCmd = v; return this; }

    public int maxLlmIterations() { return maxLlmIterations; }
    public IntegrationOptions maxLlmIterations(int v) { this.maxLlmIterations = v; return this; }

    public int maxTotalFailures() { return maxTotalFailures; }
    public IntegrationOptions maxTotalFailures(int v) { this.maxTotalFailures = v; return this; }

    public boolean force() { return force; }
    public IntegrationOptions force(boolean v) { this.force = v; return this; }

    public ResolverFactory resolverFactory() { return resolverFactory; }
    public IntegrationOptions resolverFactory(ResolverFactory v) { this.resolverFactory = v; return this; }

    /** Factory for {@link Resolver} instances, used by tests to inject doubles. */
    @FunctionalInterface
    public interface ResolverFactory {
        Resolver create(int taskId, String integrationAbsolutePath);
    }
}
