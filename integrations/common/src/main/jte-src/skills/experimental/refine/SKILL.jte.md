@import io.bitken.ss.resources.PluginModel
@param PluginModel model
@if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif

# ${model.skillName()} — Refine your code
## Execution Contract
When this skill is invoked, the user will provide one or more parameters under an 
"Apply to" block/section (Files, Folders, Diffs, or Functional requirements).

Above all, be very ambitious, especially when the change is only across a handful of files.
The code can look *very different* after you're done with it. Only the logic should continue
to be the same. When changing, do not attempt to patch the files in place. Instead, perform a
clean-slate re-derivation: treat the old code purely as a requirements document.

You must:
1. Identify the target package boundary (`cli/`, `conf/`, `workflow/`) for the provided parameters.
2. If a folder or file is passed that belongs to a legacy unorganized package, execute a clean-slate
 re-derivation to move it to its correct target package
3. Prioritize architectural intent and dependency direction over minor, local code patches
4. Ask if the user wants a high level preview of suggested changes. If yes, show preview 
with *actual* fragments of code inter-mixed with pseudo-code (a "BEFORE" version and "AFTER" version),
with bits of prose. Do *not* show only English prose explanations!
5. If there's no easy choice between different options, always ask the user. Provide them the 
various options and a free form text field where they can provide their own input.

@template.skills.experimental.refine.rules.srp(model = model)
@template.skills.experimental.refine.rules.class-structure(model = model)
@template.skills.experimental.refine.rules.rich-domain(model = model)
@template.skills.experimental.refine.rules.single-source(model = model)
@template.skills.experimental.refine.rules.private-final-fields(model = model)
@template.skills.experimental.refine.rules.constructor-di(model = model)
@template.skills.experimental.refine.rules.avoid-primitives(model = model)
@template.skills.experimental.refine.rules.static-rare(model = model)
@template.skills.experimental.refine.rules.method-ordering(model = model)
@template.skills.experimental.refine.rules.method-structure(model = model)
@template.skills.experimental.refine.rules.method-length(model = model)
@template.skills.experimental.refine.rules.ternaries-booleans(model = model)
@template.skills.experimental.refine.rules.file-length(model = model)
@template.skills.experimental.refine.rules.if-nesting(model = model)
@template.skills.experimental.refine.rules.if-block-length(model = model)
@template.skills.experimental.refine.rules.package-structure(model = model)