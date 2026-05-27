package io.bitken.ss.workflow.integration;

import java.util.List;

public record ResolverContext(
        int taskId,
        String taskName,
        String taskMarkdown,
        String patchBlobSha,
        String diffText,
        List<String> conflictedFiles,
        String verifyError
) {}