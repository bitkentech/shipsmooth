# Service-Driven Orchestration: A Unified Workflow Layer

**Status:** Proposal  
**Date:** May 2026  
**Subject:** Transitioning from command-centric logic to a unified service layer to support multi-interface extensibility.  
---
## 1. Abstract

> *The CLI is a view, not the brain. The brain must live where every interface can reach it.*

As Shipsmooth evolves from a local-first CLI tool toward a broader ecosystem (Web UI, Desktop app, API), the current architecture — where business logic and orchestration are coupled to PicoCLI commands — presents a significant barrier. This proposal introduces a **Service Layer** as the primary boundary for Shipsmooth's operations. By wrapping low-level domain services into a unified `WorkflowService`, we decouple agentic workflows from their presentation, ensuring that core invariants are enforced consistently across every interface that ever calls them.

---

## 2. The Problem: The "Thick Command" Trap

Several commands in `io.bitken.ss.commands` today act as mini-orchestrators. `WorkerInitCommand`, for instance, is responsible for calculating branch names, coordinating with `WorktreeService` to modify the filesystem, and determining the state to record via `LedgerService`. The PicoCLI `call()` method is the orchestrator.

This coupling produces three concrete failure modes:

- **Logic duplication.** A Web UI added tomorrow cannot reuse the logic inside a `call()` method without shelling out to the CLI. That is inefficient (JVM cold start per action), brittle (string-parsed exit codes), and bypasses the type system.
- **Brittle invariants.** The Core Invariants defined in `SKILL.md` (e.g., "Tests precede implementation") are enforced at the CLI edge. Any interface that bypasses the CLI also bypasses the rules.
- **Transactional fragility.** Operations that span Git and the ledger (notably `worker-finish`) are not managed in a single unit of work. A worktree deletion that fails after a ledger event has been written leaves the system in an inconsistent state with no rollback story.

---

## 3. Proposed Architecture: The Workflow Service

The solution follows the Service Layer pattern, establishing a clear boundary between application logic and delivery mechanism.
archived
### 3.1 The new hierarchy

1. **Interface Layer (Thin).** `TasksCLI`, future Web controllers, future desktop event handlers. Parses input, formats output. No business logic.
2. **Service Layer (The Brain).** `WorkflowService`. Orchestrates the unit of work for a specific agentic task. Enforces invariants. Owns transactional boundaries.
3. **Domain Layer (The Muscles).** `WorktreeService` (Git operations), `LedgerService` (event persistence), `XmlService` (XML projection), `ObjectStore` (content-addressed blob storage).

### 3.2 What moves where

| Today | After |
|---|---|
| Branch-name calculation in `WorkerInitCommand` | `WorkflowService.initializeWorker` |
| Coordination of git + ledger in `WorkerFinishCommand` | `WorkflowService.finalizeWorker` |
| Resume-state inference in `IntegrateCommand` | `WorkflowService.resumeIntegration` |
| Invariant checks in `SKILL.md` prose | Pre/post-conditions in service methods |

The PicoCLI commands shrink to argument parsing and a single service call.

---

## 4. Implementation Strategy

### 4.1 The `WorkflowService` API

The service exposes high-level, goal-oriented methods named for user intent, not technical steps:

```java
public interface WorkflowService {
    /**
     * Initialize a worker for a task:
     *   1. Acquire the GitGate semaphore.
     *   2. Resolve the base SHA (from --base or parent task's COMMIT_RECORDED).
     *   3. Create the worktree via WorktreeService.
     *   4. Record the AGENT_START event via LedgerService.
     * All four steps succeed together or none of them apply.
     */
    void initializeWorker(UUID planId, String taskId, String baseSha) throws WorkflowException;

    /**
     * Finalize a worker:
     *   1. Verify invariants (worker made no commits; expected files present).
     *   2. Capture the diff and commit on agent-work/{taskId}.
     *   3. Record COMMIT_RECORDED and STATUS_UPDATED in a single ledger transaction.
     *   4. Re-materialize the XML projection.
     */
    void finalizeWorker(UUID planId, String taskId) throws WorkflowException;

    /**
     * Resume or run integration for a plan. Internally delegates state
     * detection to reconcile (see goal-oriented-impl.md) and dispatches
     * to the appropriate recovery branch.
     */
    IntegrationResult runIntegration(UUID planId, IntegrationOptions options) throws WorkflowException;
}
```

Failure modes are exposed via a single checked `WorkflowException` carrying a typed error code, not via exit codes or string parsing.

### 4.2 Refactored `TasksCLI`

Each PicoCLI command becomes a thin shell:

```java
@Command(name = "worker-init")
public class WorkerInitCommand implements Callable<Integer> {
    private final WorkflowService workflow;  // injected

    @Option(names = "--plan") UUID planId;
    @Option(names = "--task") String taskId;
    @Option(names = "--base") String baseSha;

    @Override
    public Integer call() {
        try {
            workflow.initializeWorker(planId, taskId, baseSha);
            return 0;
        } catch (WorkflowException e) {
            System.err.println(e.getMessage());
            return e.exitCode();
        }
    }
}
```

No git calls, no ledger writes, no XML manipulation in the command. The command's only job is mapping argv to a service call.

### 4.3 Relationship to `reconcile`

The `reconcile` command (see `goal-oriented-impl.md`) is the recovery primitive; `WorkflowService` is the orchestration primitive. They compose: `WorkflowService.runIntegration` calls `reconcile` internally to determine its starting state, then dispatches to the correct recovery method. Higher-level callers (Web UI, agents) never have to walk the state machine themselves.

---

## 5. Supporting Future Interfaces

The architecture explicitly enables:

- **Web UI.** A Spring Boot controller injects `WorkflowService` and exposes a REST endpoint. A "Start Task" button calls the exact same code path as `shipsmooth worker-init`. Same invariants, same transactional guarantees.
- **Desktop app.** A JavaFX or Compose for Desktop frontend invokes the service directly. No JVM cold-start per click.
- **Automated agents.** Orchestrators (including future versions of ShipSmooth itself) call the service API to manage parallel subagents without going through a shell. The Anthropic SDK lives in the same process as the service, so tool-use round-trips are method calls, not subprocess spawns.

Each new interface is a thin adapter over the same brain.

---

## 6. Impact on Core Invariants

Moving logic into the service layer turns the SKILL's prose-level invariants into hard, in-code guards. Examples:

- **"Tests precede implementation"** becomes a pre-condition in `finalizeWorker`: if the task has no test files older than the implementation files, the call fails with `INVARIANT_VIOLATION_TESTS_AFTER_IMPL`.
- **"Worker may not run git commands"** becomes a post-condition: if the worktree's reflog shows worker-side commits, `finalizeWorker` aborts before recording anything.
- **"Every plan references at least one backlog feature issue"** becomes a pre-condition in `initializePlan` (if/when that method is added).

Each invariant is one place to read, one place to test, and impossible to bypass by switching interfaces.

---

## 7. Migration Path

The refactor is mechanical but touches many files. A safe sequencing:

1. **Define the service interface and implementation, with no command using it yet.** Behaviorally equivalent to the existing code; just relocated.
2. **Migrate one command at a time** (`worker-init` first — smallest blast radius). Keep the old code path until tests pass.
3. **Add invariant guards** as service-layer pre/post-conditions only after all commands route through the service. Avoid introducing new behavior during the relocation.
4. **Delete duplicated logic from commands** once the service path is proven.

No new feature ships during the refactor. The goal is structural, not behavioral.

---

## 8. Trade-offs and Risks

**Layer indirection.** A reader following a `worker-init` invocation now goes CLI → Service → Domain instead of CLI → Domain. This is the explicit cost of decoupling. Mitigated by keeping the service interface narrow (one method per user intent) and named for intent rather than mechanism.

**Transactional scope.** Cross-cutting operations (git + ledger + XML) need a unit-of-work abstraction. Java doesn't give us one for free across these subsystems. We will need a small `Transaction` helper that batches mutations and rolls back partial writes on failure. This is a real implementation cost, not a free lunch.

**Test surface expansion.** The service layer needs its own test suite, distinct from the per-command tests. Worth it — the service is where invariants live — but the initial migration roughly doubles the test count for affected operations.

**Premature generality risk.** Building a service layer for interfaces that don't exist yet (Web, Desktop) risks YAGNI. Mitigation: the service interface is shaped by the *current* CLI's call sites, not by hypothetical future ones. If future interfaces need different shapes, they can extend or alias service methods at that time.

---

## 9. Conclusion

The CLI is one delivery mechanism among several. Treating it as the orchestrator embeds the orchestration logic in a single delivery mechanism, which forces every other interface to either reimplement it or shell out.

Moving orchestration into `WorkflowService` makes the CLI a view, the service the brain, and the domain services the muscles. Each new interface — Web, Desktop, in-process agent — becomes a thin adapter over the brain, not a reimplementation of it.

The cost is one layer of indirection and a unit-of-work helper. The payoff is that the skill, invariants, and transactional guarantees of ShipSmooth become properties of the system rather than properties of one entry point.

For the current Git operations that this layer will wrap, see `WorktreeService`.