package io.bitken.shipsmooth.tasks.integration;

import java.io.File;

public interface Resolver {
    void resolve(File worktreeDir, ResolverContext ctx) throws Exception;
}