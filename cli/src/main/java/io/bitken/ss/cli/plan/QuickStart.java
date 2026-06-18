package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.svc.plan.PlanService;
import io.bitken.ss.svc.plan.ScaffoldException;
import io.bitken.ss.svc.plan.ScaffoldResult;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * {@code plan quick --desc S} — thin-context fast-start (quickstart).
 *
 * <p>Picocli adapter over {@link PlanService#quickStart}: parses {@code --desc},
 * asks the service to scaffold a new plan (branch + stub, no commit), and
 * renders the handoff lines. All scaffolding logic — and the deliberate absence
 * of any commit — lives behind the service, not here.
 */
public class QuickStart implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final PlanService planService;

    public QuickStart(PlanService planService) {
        this.planService = planService;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("quick");
        spec.usageMessage().description("Quick start mode: Derive plan number, create a branch, write a stub plan file. " +
            "No git commit.");
        spec.addOption(OptionSpec.builder("--desc").required(true).type(String.class)
            .paramLabel("TEXT").description("Short plan description (used for the branch slug)").build());
    }

    @Override
    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws IOException {
        String desc = spec.commandLine().getParseResult().matchedOption("desc").getValue();
        try {
            handoff(planService.quickStart(desc));
            return 0;
        } catch (ScaffoldException e) {
            System.out.println("ERROR: " + e.getMessage());
            return 1;
        }
    }

    private static void handoff(ScaffoldResult result) {
        System.out.println("Created branch: " + result.branchName());
        System.out.println("Wrote stub: " + result.planFile());
    }
}
