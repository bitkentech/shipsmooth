# Plan 12: Risk-based task ordering and de-risk/harden execution cycle

## Context
The code-flow SKILL.md currently treats all tasks uniformly — same process, same rigor, sequential execution in plan order. This means high-risk architectural tasks may be attempted late, after significant investment in low-risk code that might need to change. Additionally, the agent has no mechanism to validate an approach before committing to full quality — every task goes through the complete TDD cycle regardless of uncertainty.

This plan adds risk-aware ordering and a two-phase execution cycle to surface problems early and reduce wasted effort.

**Linear feature issue:** PB-204

## Design decisions

- **Control Strategy equation:** Keep the full LaTeX Risk-Quality Loop equation as a conceptual framing, placed after Core Invariants.
- **Low-risk tasks skip de-risk/harden:** Only High and Medium risk tasks go through the two-step draft→approve→harden cycle. Low-risk tasks use the existing single-pass TDD flow. This keeps overhead proportional to actual risk.
- **TDD reconciliation:** The de-risk phase requires at least one failing test before implementation, preserving Core Invariant #6. The lighter requirement (one test vs. full suite) is explicit. Full test coverage comes in the harden phase.
- **Integration test preamble preserved:** The existing preamble (write integration tests before any task) is unchanged and runs before the risk-sorted per-task loop.

## Tasks

### Task 1: Add Control Strategy section after Core Invariants

Insert a new `## Control Strategy: The Risk-Quality Loop` section between "Core Invariants" and "Repository Structure". Contents:

- The control equation: $u[k] = K_S(R) \cdot \frac{\Delta e[k]}{T_s} + K_Q \cdot \frac{e[k]}{T_s}$
- Definitions of $K_S(R)$ (Spiral Risk Gain), $K_Q$ (Implementation Quality Gain), $T_s$ (Sampling Interval)
- Strategy summary: "De-risk aggressively first (High $K_S$, Low $K_Q$). Once logic is proven, harden the code (Low $K_S$, High $K_Q$)."

### Task 2: Add risk analysis and collaborative calibration steps to Phase 1

After existing step 5 (create Linear issues from plan tasks), insert three new steps before "Stop. Post to Linear...":

6. **Risk Analysis:** For every task in the plan, suggest a Default Risk Level (Low, Medium, or High) with a one-sentence justification.
7. **Collaborative Calibration:** Stop. Ask the human: "I've estimated these risk levels. Do you want to override any of them?" The human's choice becomes the Actual Risk ($R$).
8. **Risk-Sorted Task Ordering:** Re-order tasks in the plan file and Linear project in descending order of risk (High → Med → Low). Exception: if a lower-risk task is a hard technical dependency for a higher-risk task, the dependency must come first. Update the plan file to reflect the finalized sequence.

Renumber existing steps 6-7 to 9-10.

### Task 3: Replace per-task loop in Phase 2 with de-risk/harden cycle

Replace the current "### Per-task loop" section with a new section that handles two cases:

**For High and Medium risk tasks — De-risk & Harden Cycle:**

#### Step A: De-risking (Spiral Phase)
- Goal: Validate logic and architectural direction.
- Write at least one failing test that targets the core logic (preserving Core Invariant #6).
- Implement just enough to prove the approach works. Focus on core complexity.
- Commit as `draft(N): de-risk [task name]` and notify the human in Linear.
- **Wait for explicit approval of the approach.**

#### Step B: Hardening (Quality Phase)
- Goal: Achieve technical excellence, human readability, and coverage threshold.
- Refactor the de-risked code for readability, performance, and project patterns.
- Write full unit tests, ensure coverage meets the agreed threshold.
- Commit as `task(N): <short description>`. Push to branch.
- Mark the Linear issue **Agent Coded**.

**For Low risk tasks — Single-pass (current behavior):**
- Write unit tests (red), implement (green), verify coverage.
- Commit as `task(N): <short description>`. Push to branch.
- Mark the Linear issue **Agent Coded**. No draft review needed.

## Files to modify
- `skills/code-flow/SKILL.md` — all changes are in this single file

## Verification
1. Read the modified SKILL.md end-to-end and confirm logical consistency
2. Verify Core Invariant #6 (TDD) is not contradicted by the de-risk phase wording
3. Walk through a hypothetical plan with mixed risk levels (2 High, 1 Med, 3 Low) and confirm the flow produces the expected sequence of commits and human checkpoints
4. Build the dev plugin (`mvn process-resources`) and confirm SKILL.md is correctly copied to `build/`
