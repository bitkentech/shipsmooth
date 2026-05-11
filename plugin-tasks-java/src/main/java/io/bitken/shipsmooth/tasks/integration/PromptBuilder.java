package io.bitken.shipsmooth.tasks.integration;

public class PromptBuilder {

    public static String build(String worktreePath, ResolverContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a ShipSmooth integration resolver.\n\n");

        sb.append("**Pre-flight check (do this before anything else):**\n")
          .append("Run: `ls ").append(worktreePath).append("` — if the directory is empty or does not exist, ")
          .append("stop immediately with: `RESOLVER ABORT: worktree ").append(worktreePath)
          .append(" is missing or empty.` Do not write any code.\n\n");

        sb.append("**Your working directory is `").append(worktreePath)
          .append("`.** Use **absolute paths** for all Read/Edit/Write tool calls (not relative paths). ")
          .append("Bash calls may still use `cd ").append(worktreePath)
          .append(" &&` as a working-directory anchor for commands that require it (e.g. running tests), ")
          .append("but file edits must use absolute paths. ")
          .append("Do not modify any file outside that directory.\n\n");

        sb.append("**You are forbidden from running any git command.** ")
          .append("No `git commit`, `git add`, `git checkout`, `git branch`, `git push`, `git reset`. ")
          .append("The Lead Agent handles all git operations after you exit.\n\n");

        sb.append("**Resolver scope:** You may rewrite any file in the integration worktree — not just ")
          .append("the conflicted hunks — as long as the original intent of the task is preserved and ")
          .append("all tests pass. Minimal edits are preferred but not required when the conflict is structural.\n\n");

        sb.append("**Task ").append(ctx.taskId()).append(": ").append(ctx.taskName()).append("**\n\n");
        sb.append("Task description:\n```\n").append(ctx.taskMarkdown()).append("\n```\n\n");

        if (ctx.conflictedFiles() != null && !ctx.conflictedFiles().isEmpty()) {
            sb.append("**Conflicted files:**\n");
            for (String f : ctx.conflictedFiles()) {
                sb.append("- ").append(f).append("\n");
            }
            sb.append("\n");
        }

        if (ctx.diffText() != null && !ctx.diffText().isBlank()) {
            sb.append("**Original patch from subagent (for intent reference):**\n```diff\n")
              .append(ctx.diffText()).append("\n```\n\n");
        }

        if (ctx.verifyError() != null && !ctx.verifyError().isBlank()) {
            sb.append("**Verify command failed with:**\n```\n")
              .append(ctx.verifyError()).append("\n```\n\n");
            sb.append("Fix the code so the verify command passes before you exit.\n\n");
        } else {
            sb.append("Resolve all conflict markers so the worktree compiles and tests pass.\n\n");
        }

        sb.append("**Verify scope:** Before running the full verify command, consider whether you can narrow it ")
          .append("to tests that directly exercise this task's changes. If the full command tests features not yet ")
          .append("present in the integration branch (e.g. a YAML test suite when only XML has landed), run a scoped ")
          .append("subset first (e.g. `-Dtest=FooTest` or `pytest tests/test_foo.py`). Only fall back to the full ")
          .append("verify command once the scoped tests pass. This avoids spurious failures from features not yet integrated.\n\n");

        sb.append("**When done, exit with a one-line summary** of files changed: ")
          .append("`Modified: path/a, path/b. Added: path/c.`");

        return sb.toString();
    }
}
