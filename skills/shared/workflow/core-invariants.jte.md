@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Core Invariants — Never Violate These

1. **Features vs Plans are strictly separate.** Feature definitions live in the permanent backlog forever. Plans are transient and archived after completion. Never fold a feature definition into a plan.
2. **A committed, pushed, human-reviewed plan is the contract.** You execute against it. You do not autonomously modify it.
3. **Every plan must reference at least one permanent backlog feature.** Record it in the `<backlog-issue>` metadata element of the tasks XML file. If no backlog feature exists, stop and create one before proceeding.
4. **Task tracking is never the source of truth for plan content.** Git is. The local tasks file tracks task state only.
5. **Tags are permanent.** Never delete a plan version tag from remote, even on abandonment or squash merge.
6. **Tests precede implementation.** Write integration test(s) before any task code (Phase 2 preamble), then the unit test for each task before its implementation. Never implement without a failing test already committed. (Apply as far as possible — migrations and config may not be TDD-able.)

---
