package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.svc.plan.PlanService;
import jakarta.inject.Provider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class ProjectUpdate implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final Provider<PlanService> planService;

    public ProjectUpdate(Provider<PlanService> planService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.planService = planService;
        this.spec.name("update");
        this.spec.usageMessage().description("Add a project update.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class)
            .paramLabel("PLAN_NUMBER").description("Plan number").build());
        this.spec.addOption(OptionSpec.builder("--status").type(String.class)
            .paramLabel("STATUS").description("New plan status: active, complete, abandoned, in-review").build());
        this.spec.addOption(OptionSpec.builder("--blocked").type(Boolean.class)
            .description("Mark the plan blocked (major deviation)").build());
        this.spec.addOption(OptionSpec.builder("--message").type(String.class)
            .paramLabel("TEXT").description("Update message").build());
    }

    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws Exception {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String status = pr.matchedOptionValue("status", null);
        Boolean blocked = pr.matchedOptionValue("blocked", null);
        String message = pr.matchedOptionValue("message", null);

        planService.get().projectUpdate(plan, status, blocked, message);
        System.out.println("Project update added.");
        return 0;
    }
}
