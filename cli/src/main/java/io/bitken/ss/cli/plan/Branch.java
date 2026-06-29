package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.svc.plan.Slugs;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

/**
 * {@code plan branch --issue ID --desc S} or {@code plan branch --plan N --desc S}
 *
 * <p>Pass {@code --issue} (e.g. {@code pb-310}) to name the branch after an
 * external issue id; the branch is {@code t/{issue}-{slug}}. Pass {@code --plan}
 * (e.g. {@code 71}) to name it after the plan number; the branch is
 * {@code t/{N}-{slug}}. Exactly one of the two must be present.
 */
public class Branch implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final GitState gitState;

    public Branch(GitState gitState) {
        this.gitState = gitState;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("branch");
        spec.usageMessage().description("Create a task branch locally and print the push line.");
        spec.addOption(OptionSpec.builder("--issue").paramLabel("ISSUE_ID").required(false).description("Issue ID for the branch (used in the slug; defaults to the plan number)").type(String.class).build());
        spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(false).description("Plan number (used for the slug when --issue is absent)").type(Integer.class).build());
        spec.addOption(OptionSpec.builder("--desc").paramLabel("TEXT").required(true).description("Short branch description").type(String.class).build());
    }

    @Override
    public CommandSpec getSpec() { return spec; }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        String desc = pr.matchedOption("desc").getValue();

        String prefix = resolvePrefix(pr);
        if (prefix == null) {
            System.out.println("ERROR: provide exactly one of --issue or --plan");
            return 1;
        }

        String branchName = Slugs.branchName(prefix, desc);
        if (gitState.branchExists(branchName)) {
            System.out.println("ERROR: branch " + branchName + " already exists");
            return 1;
        }
        if (!gitState.createBranch(branchName)) {
            System.out.println("ERROR: failed to create branch " + branchName);
            return 1;
        }
        System.out.println("Created branch: " + branchName);
        System.out.println("Run: git push -u origin " + branchName);
        return 0;
    }

    private static String resolvePrefix(picocli.CommandLine.ParseResult pr) {
        boolean hasIssue = pr.hasMatchedOption("issue");
        boolean hasPlan  = pr.hasMatchedOption("plan");
        if (hasIssue == hasPlan) return null;
        if (hasIssue) return ((String) pr.matchedOption("issue").getValue()).toLowerCase();
        return String.valueOf((Integer) pr.matchedOption("plan").getValue());
    }
}
