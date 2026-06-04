@import io.bitken.ss.resources.PluginModel
@param PluginModel model
## Control Strategy: The Risk-Quality Loop

To maximize productivity while minimizing "hallucination drift," treat
risk and quality as two pressures that peak at different times — and never
chase both at once.

- **Spiral risk** — the chance that the architecture or core logic is
  simply wrong. It is highest at the *start* of a task, when the approach
  is unproven, and collapses once the logic is validated.
- **Implementation quality** — readability, project-pattern conformance,
  and test coverage. It matters only *after* the approach is proven; polishing
  code that may be thrown away is wasted effort.

**Strategy:** De-risk aggressively first — prove the logic works and ignore
quality rules. Once the approach is validated and approved, switch modes and
harden the code to the quality bar. The per-task **De-risk & Harden Cycle**
below operationalizes this; this section only explains *why* the two phases
are kept separate.

---
