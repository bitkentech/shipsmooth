package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitState;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

/**
 * {@code plan branch --issue ID --desc S}
 *
 * <p>Slugifies the description, constructs {@code t/{ID}-{slug}}, creates the
 * local branch via {@code git checkout -b}, and prints the push line.
 * Errors non-zero if the branch already exists.
 * Does not call {@code git push}.
 */
public class Branch implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final GitState gitState;

    public Branch(GitState gitState) {
        this.gitState = gitState;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("branch");
        spec.usageMessage().description("Create a task branch locally and print the push line.");
        spec.addOption(OptionSpec.builder("--issue").required(true).type(String.class).build());
        spec.addOption(OptionSpec.builder("--desc").required(true).type(String.class).build());
    }

    @Override
    public CommandSpec getSpec() { return spec; }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        String issue = pr.matchedOption("issue").getValue();
        String desc = pr.matchedOption("desc").getValue();

        String branchName = "t/" + issue.toLowerCase() + "-" + slugify(desc);

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

    static String slugify(String desc) {
        return desc.toLowerCase()
                   .replaceAll("[^a-z0-9]+", "-")
                   .replaceAll("^-|-$", "");
    }
}
