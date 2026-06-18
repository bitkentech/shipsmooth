package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code plan preflight --plan N} — four-condition verifier replacing the
 * step-6 bash block in phase1-plan.
 *
 * <p>FAIL (non-zero exit): dirty working tree, version tag absent locally.
 * <p>WARN (pass overall): branch not pushed or HEAD ahead of upstream.
 */
public class Preflight implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final GitState gitState;
    private final GitTags gitTags;

    public Preflight(GitState gitState, GitTags gitTags) {
        this.gitState = gitState;
        this.gitTags = gitTags;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("preflight");
        spec.usageMessage().description("Verify plan preconditions before Phase 2.");
        spec.addOption(OptionSpec.builder("--plan").paramLabel("PLAN_NUMBER").required(true).description("Plan number").type(int.class).build());
    }

    @Override
    public CommandSpec getSpec() {
        return spec;
    }

    @Override
    public Integer call() {
        var pr = spec.commandLine().getParseResult();
        int plan = pr.matchedOption("plan").getValue();

        List<String> warnings = new ArrayList<>();

        if (!gitState.isClean()) {
            System.out.println("FAIL: working tree has uncommitted changes (git status --porcelain)");
            return 1;
        }

        String versionTag = gitTags.getPlanVersion(plan);
        if (!gitState.tagExistsLocally(versionTag)) {
            System.out.println("FAIL: version tag " + versionTag + " not found locally");
            return 1;
        }

        if (!gitState.isBranchPushedAndNotAhead()) {
            warnings.add("WARN: branch is not pushed or HEAD is ahead of upstream");
        }

        if (!gitState.tagExistsOnRemote(versionTag)) {
            warnings.add("WARN: version tag " + versionTag + " not found on remote");
        }

        warnings.forEach(System.out::println);
        System.out.println("PASS");
        return 0;
    }
}
