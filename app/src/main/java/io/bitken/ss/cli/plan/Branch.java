package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitState;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

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
        System.out.println("plan branch: not yet implemented");
        return 1;
    }
}
