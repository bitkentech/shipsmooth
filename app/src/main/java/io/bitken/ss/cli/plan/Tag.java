package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.GitTags;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class Tag implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final GitTags gitTags;
    private final GitState gitState;

    public Tag(GitTags gitTags, GitState gitState) {
        this.gitTags = gitTags;
        this.gitState = gitState;
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
        System.out.println("plan tag: not yet implemented");
        return 1;
    }
}
