package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.integration.IntegrationDefaults;
import io.bitken.shipsmooth.tasks.workflow.IntegrationOptions;
import io.bitken.shipsmooth.tasks.workflow.IntegrationResult;
import io.bitken.shipsmooth.tasks.workflow.WorkflowException;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Paths;
import java.util.concurrent.Callable;

@Command(name = "integrate", description = "Integrate parallel agent-work/* branches into the task branch.")
public class IntegrateCommand implements Callable<Integer> {

    @Option(names = "--plan", required = true)
    private int plan;

    @Option(names = "--task-branch")
    private String taskBranch;

    @Option(names = "--verify-cmd")
    private String verifyCmd;

    @Option(names = "--max-llm-iterations")
    private int maxLlmIterations = IntegrationDefaults.MAX_LLM_ITERATIONS;

    @Option(names = "--max-total-failures")
    private int maxTotalFailures = IntegrationDefaults.MAX_TOTAL_FAILURES;

    @Option(names = "--force", description = "Delete existing integration worktree/branch and start fresh.")
    private boolean force;

    private IntegrationOptions.ResolverFactory resolverFactory;

    /** Test seam: override the resolver implementation. */
    void setResolverFactory(IntegrationOptions.ResolverFactory factory) {
        this.resolverFactory = factory;
    }

    @Override
    public Integer call() {
        WorkflowServiceImpl workflow = new WorkflowServiceImpl(Paths.get("."));
        IntegrationOptions opts = new IntegrationOptions()
                .taskBranch(taskBranch)
                .verifyCmd(verifyCmd)
                .maxLlmIterations(maxLlmIterations)
                .maxTotalFailures(maxTotalFailures)
                .force(force)
                .resolverFactory(resolverFactory);
        try {
            IntegrationResult result = workflow.runIntegration(plan, opts);
            return result.success() ? 0 : 1;
        } catch (WorkflowException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
    }
}