package io.bitken.shipsmooth.tasks.integration;

import java.io.File;

public class SubagentResolver implements Resolver {

    private final SubagentRunner runner;
    private final String worktreePath;

    public SubagentResolver(SubagentRunner runner, String worktreePath) {
        this.runner = runner;
        this.worktreePath = worktreePath;
    }

    @Override
    public void resolve(File worktreeDir, ResolverContext ctx) throws Exception {
        String prompt = PromptBuilder.build(worktreePath, ctx);
        runner.run(prompt);
    }
}
