package io.bitken.ss.cli;

import io.bitken.ss.service.PlanService;
import jakarta.inject.Inject;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class ProjectUpdate implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final PlanService planService;

    @Inject
    public ProjectUpdate(PlanService planService) {
        this.spec = CommandSpec.wrapWithoutInspection(this);
        this.planService = planService;
        this.spec.name("project-update");
        this.spec.usageMessage().description("Add a project update.");
        this.spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        this.spec.addOption(OptionSpec.builder("--status").type(String.class).build());
        this.spec.addOption(OptionSpec.builder("--blocked").type(Boolean.class).build());
        this.spec.addOption(OptionSpec.builder("--message").type(String.class).build());
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

        planService.projectUpdate(plan, status, blocked, message);
        System.out.println("Project update added.");
        return 0;
    }
}
