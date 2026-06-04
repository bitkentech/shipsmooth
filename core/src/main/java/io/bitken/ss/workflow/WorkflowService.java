package io.bitken.ss.workflow;

/**
 * Service-layer boundary for ShipSmooth's agentic workflows.
 *
 * <p>Established by plan-37 as the single orchestration point that the PicoCLI
 * commands, and any future delivery mechanism (web, desktop, in-process agent),
 * route through. Methods are added one per migrated command — see plan-37 tasks
 * 2-4.
 *
 * <p>This skeleton intentionally has no methods. Migrations add them.
 */
public interface WorkflowService {

    /**
     * Initialise a coding worker for a task:
     * <ol>
     *   <li>Resolve the branch name and the base SHA (from {@code baseSha} or HEAD).</li>
     *   <li>Create the git worktree at {@code .agents/tasks/{taskId}} on branch
     *       {@code agent-work/{taskId}}.</li>
     *   <li>Append a {@code WORKTREE_CREATED} event to the ledger.</li>
     * </ol>
     *
     * @param planId  plan number (for error messages only — not yet persisted in
     *                this event type)
     * @param taskId  task identifier
     * @param baseSha optional base commit SHA to branch from; HEAD when {@code null} or blank
     * @return absolute path of the created worktree
     * @throws WorkflowException when the worktree already exists or any
     *                           subsystem call fails
     */
    java.nio.file.Path initializeWorker(int planId, String taskId, String baseSha) throws WorkflowException;

    /**
     * Finalise a coding worker for a task:
     * <ol>
     *   <li>Verify the worktree directory exists.</li>
     *   <li>Enforce the no-commits-in-worktree invariant by comparing the branch
     *       tip with the base SHA recorded at {@code initializeWorker}.</li>
     *   <li>Capture the diff; reject if empty.</li>
     *   <li>Commit the worktree changes on {@code agent-work/{taskId}}.</li>
     *   <li>Append {@code PATCH_EMITTED} and {@code COMMIT_RECORDED} events.</li>
     *   <li>Update the {@code <commit>} field in {@code plan-{N}-tasks.xml}.</li>
     * </ol>
     *
     * <p>Steps 4-6 are wrapped in a {@link Transaction}: a failure after the
     * commit rolls the branch back to its prior tip via {@code resetHard}, so
     * the ledger and the branch never disagree on whether the commit happened.
     *
     * @throws WorkflowException with a typed error code when any precondition
     *                           fails or any subsystem call errors out
     */
    void finalizeWorker(int planId, String taskId) throws WorkflowException;

    /**
     * Run integration for a plan: merge all {@code agent-work/*} branches with
     * a {@code COMMIT_RECORDED} event into {@code integration/plan-{N}}, with
     * resolver-driven recovery for conflicts and verify failures.
     *
     * <p>Resumes from the last {@code PATCH_INTEGRATED} event when an
     * integration worktree already exists. Use {@code options.force(true)} to
     * blow away an existing worktree and start fresh.
     *
     * @return {@link IntegrationResult#ok} on success (with tip SHA and
     *         fast-forward command), {@link IntegrationResult#failed} when the
     *         loop bottoms out within the failure budget but integration was
     *         not completed
     * @throws WorkflowException on hard errors (missing ledger events, ordering
     *                           failure, IO problems)
     */
    IntegrationResult runIntegration(int planId, IntegrationOptions options) throws WorkflowException;
}
