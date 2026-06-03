package io.bitken.ss.cli.plan;

import io.bitken.ss.cli.HasSpec;
import io.bitken.ss.gw.GitState;
import io.bitken.ss.gw.TaskStore;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.concurrent.Callable;

public class Resume implements Callable<Integer>, HasSpec {

    private final CommandSpec spec;
    private final TaskStore taskStore;
    private final GitState gitState;

    public Resume(TaskStore taskStore, GitState gitState) {
        this.taskStore = taskStore;
        this.gitState = gitState;
        spec = CommandSpec.wrapWithoutInspection(this);
        spec.name("resume");
        spec.usageMessage().description("Session-resume pre-flight: task state + worktree check.");
        spec.addOption(OptionSpec.builder("--plan").required(true).type(int.class).build());
    }

    @Override
    public CommandSpec getSpec() { return spec; }

    @Override
    public Integer call() {
        System.out.println("plan resume: not yet implemented");
        return 1;
    }
}
