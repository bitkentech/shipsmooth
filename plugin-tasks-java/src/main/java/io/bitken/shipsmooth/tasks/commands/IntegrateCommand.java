package io.bitken.shipsmooth.tasks.commands;

import io.bitken.shipsmooth.tasks.integration.IntegrationDefaults;
import io.bitken.shipsmooth.tasks.workflow.IntegrationOptions;
import io.bitken.shipsmooth.tasks.workflow.IntegrationResult;
import io.bitken.shipsmooth.tasks.workflow.WorkflowException;
import io.bitken.shipsmooth.tasks.workflow.WorkflowServiceImpl;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.ParseResult;

import java.nio.file.Paths;

public class IntegrateCommand {

    private IntegrationOptions.ResolverFactory resolverFactory;

    public IntegrateCommand() {
    }

    /** Test seam: override the resolver implementation. */
    public void setResolverFactory(IntegrationOptions.ResolverFactory factory) {
        this.resolverFactory = factory;
    }

    public int execute(int plan, String taskBranch, String verifyCmd, int maxLlmIterations, int maxTotalFailures, boolean force) {
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

    public static CommandSpec getSpec() {
        CommandSpec spec = CommandSpec.create();
        spec.usageMessage().description("Integrate parallel agent-work/* branches into the task branch.");

        spec.addOption(OptionSpec.builder("--plan")
            .paramLabel("PLAN_NUMBER")
            .required(true)
            .description("Plan number")
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--task-branch")
            .paramLabel("BRANCH")
            .description("Task branch name")
            .type(String.class).build());

        spec.addOption(OptionSpec.builder("--verify-cmd")
            .paramLabel("COMMAND")
            .description("Verification command")
            .type(String.class).build());

        spec.addOption(OptionSpec.builder("--max-llm-iterations")
            .paramLabel("COUNT")
            .defaultValue(String.valueOf(IntegrationDefaults.MAX_LLM_ITERATIONS))
            .description("Maximum LLM iterations")
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--max-total-failures")
            .paramLabel("COUNT")
            .defaultValue(String.valueOf(IntegrationDefaults.MAX_TOTAL_FAILURES))
            .description("Maximum total failures")
            .type(int.class).build());

        spec.addOption(OptionSpec.builder("--force")
            .description("Delete existing integration worktree/branch and start fresh.")
            .type(boolean.class).build());

        return spec;
    }

    public static int run(ParseResult pr) {
        int plan = pr.matchedOption("plan").getValue();
        String taskBranch = pr.matchedOptionValue("task-branch", null);
        String verifyCmd = pr.matchedOptionValue("verify-cmd", null);
        int maxLlmIterations = pr.matchedOptionValue("max-llm-iterations", IntegrationDefaults.MAX_LLM_ITERATIONS);
        int maxTotalFailures = pr.matchedOptionValue("max-total-failures", IntegrationDefaults.MAX_TOTAL_FAILURES);
        boolean force = pr.hasMatchedOption("force");

        return new IntegrateCommand().execute(plan, taskBranch, verifyCmd, maxLlmIterations, maxTotalFailures, force);
    }
}