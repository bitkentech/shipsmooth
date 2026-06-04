package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitTags;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code plan tag --plan N --kind version|complete|abandoned}
 *
 * <p>Creates the appropriate local git tag and prints the push line.
 * For {@code --kind version}: computes the next vK, refuses if it already
 * exists, creates it, and prints {@code git push origin plan-N-vK}.
 * For {@code complete} / {@code abandoned}: creates the fixed tag and prints
 * the push line. Does not call {@code git push}.
 */
public class Tag implements Callable<Integer>, HasSpec {

    private static final Set<String> FIXED_KINDS = Set.of("complete", "abandoned");

    private final CommandSpec spec;
    private final GitTags gitTags;

    public Tag(GitTags gitTags) {
        this.gitTags = gitTags;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("tag");
        spec.usageMessage().description("Create a plan version/complete/abandoned tag.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
        spec.addOption(OptionSpec.builder("--kind").required(true).type(String.class).build());
    }

    @Override
    public CommandSpec getSpec() { return spec; }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();
        String kind = pr.matchedOption("kind").getValue();

        if ("version".equals(kind)) return createVersionTag(plan);
        if (FIXED_KINDS.contains(kind)) return createFixedTag(plan, kind);

        System.out.println("ERROR: --kind must be one of: version, complete, abandoned");
        return 1;
    }

    private int createVersionTag(int plan) {
        String tag = gitTags.nextPlanVersion(plan);
        if (gitTags.tagExists(tag)) {
            System.out.println("ERROR: tag " + tag + " already exists — commit more changes before re-tagging");
            return 1;
        }
        return createAndPrint(tag);
    }

    private int createFixedTag(int plan, String kind) {
        return createAndPrint("plan-" + plan + "-" + kind);
    }

    private int createAndPrint(String tag) {
        if (!gitTags.createTag(tag)) {
            System.out.println("ERROR: failed to create tag " + tag);
            return 1;
        }
        System.out.println("Created tag: " + tag);
        System.out.println("Run: git push origin " + tag);
        return 0;
    }
}
