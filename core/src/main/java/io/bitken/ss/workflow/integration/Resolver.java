package io.bitken.ss.workflow.integration;

import java.io.File;

public interface Resolver {
    void resolve(File worktreeDir, ResolverContext ctx) throws Exception;
}