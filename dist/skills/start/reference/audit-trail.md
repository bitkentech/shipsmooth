# Audit Trail (reference)

The XML file is the audit trail. `<created-from>` and `<closed-at-version>` child elements on each `<task>` record the plan version a task was created at and closed at. The XML is versioned in git, so `git diff` between two plan tags shows exactly what changed.

If the creation version equals the closeout version, the plan never changed during execution. If they differ, the git diff between the two tag hashes shows exactly what changed and why.

Feature definitions in the permanent backlog should accumulate references to every plan that contributed to them — this gives a full delivery history across the feature's lifetime.
