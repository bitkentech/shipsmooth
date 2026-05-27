package io.bitken.ss.cli;

import io.bitken.ss.workflow.integration.IntegrationDefaults;
import io.bitken.ss.workflow.IntegrationOptions;
import io.bitken.ss.workflow.IntegrationResult;
import io.bitken.ss.workflow.WorkflowException;
import io.bitken.ss.workflow.WorkflowService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.nio.file.Paths;
import java.util.concurrent.Callable;

public class Integrate implements Callable<Integer>, HasSpec, io.bitken.ss.conf.FeatureFlags {
    @Override public boolean isExperimental() { return true; }

    private final CommandSpec spec;
    private final WorkflowService workflowService;
    private IntegrationOptions.ResolverFactory resolverFactory;

    @Inject
    public Integrate(WorkflowService workflowService) {
        this.workflowService = workflowService;
        this.spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("integrate");
        spec.usageMessage().description("Integrate parallel agent-work/* branches into the task branch.");
        spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
        spec.addOption(OptionSpec.builder("--task-branch").paramLabel("BRANCH").description("Task branch name").type(String.class).build());
        spec.addOption(OptionSpec.builder("--verify-cmd").paramLabel("COMMAND").description("Verification command").type(String.class).build());
        spec.addOption(OptionSpec.builder("--max-llm-iterations").paramLabel("COUNT").defaultValue(String.valueOf(IntegrationDefaults.MAX_LLM_ITERATIONS)).description("Maximum LLM iterations").type(int.class).build());
        spec.addOption(OptionSpec.builder("--max-total-failures").paramLabel("COUNT").defaultValue(String.valueOf(IntegrationDefaults.MAX_TOTAL_FAILURES)).description("Maximum total failures").type(int.class).build());
        spec.addOption(OptionSpec.builder("--force").description("Delete existing integration worktree/branch and start fresh.").type(boolean.class).build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    /** Test seam: override the resolver implementation. */
    public void setResolverFactory(IntegrationOptions.ResolverFactory factory) {
        this.resolverFactory = factory;
    }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        var plan = (int) pr.matchedOption("plan").getValue();
        String taskBranch = pr.matchedOptionValue("task-branch", null);
        String verifyCmd = pr.matchedOptionValue("verify-cmd", null);
        var maxLlmIterations = pr.matchedOptionValue("max-llm-iterations", IntegrationDefaults.MAX_LLM_ITERATIONS);
        var maxTotalFailures = pr.matchedOptionValue("max-total-failures", IntegrationDefaults.MAX_TOTAL_FAILURES);
        var force = pr.hasMatchedOption("force");

        var opts = new IntegrationOptions()
            .taskBranch(taskBranch)
            .verifyCmd(verifyCmd)
            .maxLlmIterations(maxLlmIterations)
            .maxTotalFailures(maxTotalFailures)
            .force(force)
            .resolverFactory(resolverFactory);
        try {
            var result = workflowService.runIntegration(plan, opts);
            return result.success() ? 0 : 1;
        } catch (WorkflowException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
    }
}
