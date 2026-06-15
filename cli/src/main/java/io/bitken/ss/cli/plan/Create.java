package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.svc.plan.NewPlan;
import io.bitken.ss.svc.plan.ScaffoldException;
import io.bitken.ss.svc.plan.ScaffoldResult;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.IOException;
import java.util.concurrent.Callable;

/**
 * {@code plan create --desc S} — thin-context fast-start.
 *
 * <p>Picocli adapter over {@link NewPlan}: parses {@code --desc}, asks the
 * domain to scaffold a new plan (branch + stub, no commit), and renders the
 * handoff lines. All scaffolding logic — and the deliberate absence of any
 * commit — lives in {@link NewPlan}, not here.
 */
public class Create implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final NewPlan newPlan;

    public Create(NewPlan newPlan) {
        this.newPlan = newPlan;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("create");
        spec.usageMessage().description("Thin-context fast-start: derive plan number, create branch, write stub plan file (no commit).");
        spec.addOption(OptionSpec.builder("--desc").required(true).type(String.class).build());
    }

    @Override
    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() throws IOException {
        String desc = spec.commandLine().getParseResult().matchedOption("desc").getValue();
        try {
            handoff(newPlan.scaffold(desc));
            return 0;
        } catch (ScaffoldException e) {
            System.out.println("ERROR: " + e.getMessage());
            return 1;
        }
    }

    private static void handoff(ScaffoldResult result) {
        System.out.println("Created branch: " + result.branchName());
        System.out.println("Wrote stub: " + result.planFile());
        System.out.println("Run: git push -u origin " + result.branchName());
        System.out.println("Flesh out the stub, then run: shipsmooth plan init --plan "
            + result.planId() + " --tasks-from " + result.planFile());
    }
}
