package io.bitken.ss.workflow.integration;

public class PromptBuilder {

    public static String build(String worktreePath, ResolverContext ctx) {
        String conflictSection = conflictSection(ctx);
        String patchSection = patchSection(ctx);
        String verifySection = verifySection(ctx);

        return """
                You are a ShipSmooth integration resolver.

                **Pre-flight check (do this before anything else):**
                Run: `ls %s` — if the directory is empty or does not exist, \
                stop immediately with: `RESOLVER ABORT: worktree %s is missing or empty.` \
                Do not write any code.

                **Your working directory is `%s`.** Use **absolute paths** for all Read/Edit/Write tool calls \
                (not relative paths). Bash calls may still use `cd %s &&` as a working-directory anchor for \
                commands that require it (e.g. running tests), but file edits must use absolute paths. \
                Do not modify any file outside that directory.

                **You are forbidden from running any git command.** \
                No `git commit`, `git add`, `git checkout`, `git branch`, `git push`, `git reset`. \
                The Lead Agent handles all git operations after you exit.

                **Resolver scope:** You may rewrite any file in the integration worktree — not just \
                the conflicted hunks — as long as the original intent of the task is preserved and \
                all tests pass. Minimal edits are preferred but not required when the conflict is structural.

                **Task %d: %s**

                Task description:
                ```
                %s
                ```
                %s%s%s
                **Verify scope:** Before running the full verify command, consider whether you can narrow it \
                to tests that directly exercise this task's changes. If the full command tests features not yet \
                present in the integration branch (e.g. a YAML test suite when only XML has landed), run a scoped \
                subset first (e.g. `-Dtest=FooTest` or `pytest tests/test_foo.py`). Only fall back to the full \
                verify command once the scoped tests pass. This avoids spurious failures from features not yet integrated.

                **When done, exit with a one-line summary** of files changed: \
                `Modified: path/a, path/b. Added: path/c.`\
                """.formatted(
                worktreePath, worktreePath,
                worktreePath, worktreePath,
                ctx.taskId(), ctx.taskName(),
                ctx.taskMarkdown(),
                conflictSection, patchSection, verifySection);
    }

    private static String conflictSection(ResolverContext ctx) {
        if (ctx.conflictedFiles() == null || ctx.conflictedFiles().isEmpty()) return "";
        var sb = new StringBuilder("\n**Conflicted files:**\n");
        for (String f : ctx.conflictedFiles()) sb.append("- ").append(f).append("\n");
        return sb.toString();
    }

    private static String patchSection(ResolverContext ctx) {
        if (ctx.diffText() == null || ctx.diffText().isBlank()) return "";
        return "\n**Original patch from subagent (for intent reference):**\n```diff\n"
                + ctx.diffText() + "\n```\n";
    }

    private static String verifySection(ResolverContext ctx) {
        if (ctx.verifyError() == null || ctx.verifyError().isBlank()) {
            return "\nResolve all conflict markers so the worktree compiles and tests pass.\n\n";
        }
        return "\n**Verify command failed with:**\n```\n" + ctx.verifyError() + "\n```\n\n"
                + "Fix the code so the verify command passes before you exit.\n\n";
    }
}
