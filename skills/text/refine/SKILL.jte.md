@import io.bitken.ss.resources.PluginModel
@param PluginModel model
@if(!model.skillFrontmatter().isEmpty())${model.skillFrontmatter()}@endif

# ${model.skillName()} — Refine your code
## Execution Contract
When this skill is invoked, the user will provide one or more parameters under an 
"Apply to" block/section (Files, Folders, Diffs, or Functional requirements).

Above all, be very ambitious, especially when the change is only across a handful of files.
The code can look *very different* after you're done with it. Only the logic should continue
to be the same. Do not patch the files in place. Instead, perform a clean-slate
re-derivation in two strictly separated phases. **You must write out the PHASE 1 extraction
in natural language and must not write a single line of new code until PHASE 1 is done.**

### PHASE 1 — Architectural Extraction (natural language only)
Read the target code **once** and extract its requirements. Split the extraction into two
labelled subsections so it is always explicit *where* each requirement came from:

**Caller's-eye view (do this first, before reading for structure)**
Ignore the existing class and method names. Identify what the calling code is *authentically 
trying to achieve* semantically. If the caller is passing loose primitives or making successive 
procedural calls (anemic behavior), design the ideal, unified verb the caller *should* be 
using instead. The existing top-level type may turn out to be an *internal collaborator* of 
that object rather than the public surface — do **not** assume the current top-level class 
is the object the caller wants. Write that ideal call site as one line.

**Requirements from production code**
- Inputs, state mutations, outputs, and downstream collaborators/renderers.
- Invariants enforced by the code itself.
- From *this* subsection — and only this one — derive the class shape that serves the
  caller's-eye view above: which class is the public surface and which become its private
  collaborators, which parameters move into the constructor, what each method does, and the
  dependency direction.

**Requirements from tests**
- The invariants the tests assert (guards, defaults, error cases).
- These constrain **behaviour only**. Test structure is **not** a template for the class's
  constructor or method shape. If your re-derived structure changes the methods or
  constructors the tests call, rewrite the tests to match — and never delete a test
  without surfacing it to the user first.

Then state the proposed structure and the dependency direction. Identify the target package
boundary (`cli/`, `conf/`, `workflow/`) for the provided parameters; if a file belongs to a
legacy unorganized package, plan to move it to its correct target package.

Write this entire PHASE 1 extraction to an ephemeral scratchpad at
`.agents/tmp/refine-<target>.md` (where `<target>` names the file or folder being refined).
The scratchpad is throwaway — do not commit it. It is the point at which the user may
review and approve the extraction before any code is generated.

### PHASE 2 — Clean-Slate Generation
**Begin by re-reading the `.agents/tmp/refine-<target>.md` scratchpad** and treat it as the
design of record for this generation. (Re-reading it here re-establishes the design in
context after the PHASE 1 discussion, so the generation stays anchored to the plan rather
than drifting back toward the original code.)
Generate the new code from the PHASE 1 *production* requirements alone. Treat the *tests*
subsection as a behaviour checklist to preserve. Do **not** keep a production method or
constructor solely because a test calls it. Apply the rule priority below throughout. If you
notice yourself making a chain of small edits that each fix the previous one — **STOP**:
that is hill-climbing. Discard the partial result and re-derive from the requirements.

### Rule priority (when rules conflict, higher wins)
1. Prefer Rich Domain Models over anemic objects — behaviour lives on the object that owns the data.
2. Class Structure — all construction (`new`, parsing, derivation) happens in the constructor.
3. Single Responsibility.
4. Single source of truth.
5. Everything else below, in the order presented.
6. **Lowest priority — mechanical limits** (method length, file length, nesting depth,
   if-block length). These are coarse heuristics; never sacrifice a higher rule to satisfy them.

When two rules pull in opposite directions, resolve toward the higher rule and say which one
you applied. Never introduce a `static` factory or a half-initialized object (e.g. an object
constructed with `null` fields just to call one method) to satisfy a lower-priority rule.

### Interaction
- Ask if the user wants a high level preview of suggested changes. If yes, show the preview
  with *actual* fragments of code inter-mixed with pseudo-code (a "BEFORE" version and
  "AFTER" version), with bits of prose. Do *not* show only English prose explanations!
- If there's no easy choice between different options, always ask the user. Provide the
  various options and a free form text field where they can provide their own input.

@template.refine.rules.rich-domain(model = model)
@template.refine.rules.class-structure(model = model)
@template.refine.rules.srp(model = model)
@template.refine.rules.single-source(model = model)
@template.refine.rules.constructor-di(model = model)
@template.refine.rules.private-final-fields(model = model)
@template.refine.rules.avoid-primitives(model = model)
@template.refine.rules.static-rare(model = model)
@template.refine.rules.method-ordering(model = model)
@template.refine.rules.method-structure(model = model)
@template.refine.rules.ternaries-booleans(model = model)
@template.refine.rules.package-structure(model = model)
@template.refine.rules.method-length(model = model)
@template.refine.rules.if-block-length(model = model)
@template.refine.rules.if-nesting(model = model)
@template.refine.rules.file-length(model = model)