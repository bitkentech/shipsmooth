@param io.bitken.ss.resources.PluginModel model

> **Commit-message convention (code commits in the project repo).** How you word a code
> commit depends on the resolved storage type. Check it once per session with
> `${model.cliBin()} store info --json` and read `storageType`.
>
> - **`same-repo` storage:** keep the prefixed convention — `task(N): <short description>` and
>   `draft(N): de-risk <task name>`. The plan/task history is shipsmooth's own and lives
>   alongside the code, so the prefixes are welcome.
> - **`separate-dir` (standalone) storage:** the project repo must stay **zero-trace**. Write
>   plain, feature-oriented messages with **no `plan(N)`/`task(N)`/`draft(N)` prefix** and
>   no plan or task references — e.g. `Add retry to upload client`, not
>   `task(3): add retry`. This applies to **every** project-repo commit, including the
>   preamble integration-test commit (write `Add end-to-end test for <feature>`, not a
>   `plan(N)`-referencing message).
>
> Traceability is **not lost** in standalone mode: the task↔commit link lives in the state
> repo's task XML, recorded via `task set-commit`. State-repo commits (the plan file and
> task XML — the `plan(N): …` commit) keep full plan/task info; that history is shipsmooth's
> own and invisible to the user. This convention governs only the **project repo's** code
> commits.
