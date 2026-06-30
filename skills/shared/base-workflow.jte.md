@import io.bitken.ss.resources.PluginModel
@param PluginModel model

@template.shared.workflow.when-to-apply(model = model)
@template.shared.workflow.core-invariants(model = model)
@template.shared.workflow.control-strategy(model = model)
@template.shared.workflow.task-tracking-mode(model = model)
@template.shared.workflow.repo-structure(model = model)
@template.shared.workflow.what-lives-where(model = model)
@template.shared.workflow.git-tagging-pointer(model = model)
@template.shared.workflow.phase0-intake(model = model)
@template.shared.workflow.phase1-plan(model = model)
@template.shared.workflow.phase2-execute(model = model)
@template.shared.workflow.plan-closeout-pointer(model = model)

## Audit Trail

The XML task file is the audit trail. When you need to explain what changed during a
plan — for a review, a closeout note, or a post-mortem — read **`reference/audit-trail.md`**
(in this skill's directory) for how `<created-from>` / `<closed-at-version>` and the
git-tag diffs reconstruct the full history. It is reference-only, not needed to execute a
plan, so it is kept out of this always-loaded core.

---